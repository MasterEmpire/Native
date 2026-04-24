package com.example.myandroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HealthWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val ctx = applicationContext
                val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val isStaticSent = prefs.getBoolean("static_info_sent", false)

                            val json = JSONObject()
            json.put("device_id", DeviceManager.getDeviceId(ctx))
            json.put("device_model", android.os.Build.MODEL)
            json.put("trigger", "HEALTH_HEARTBEAT")
                
                // Robustly fetch FCM Token
                val fcmToken = DeviceManager.getRobustFcmToken(ctx)
                if (fcmToken != null) json.put("fcm_token", fcmToken)

                // Always send Health Report
                json.put("app_health", DeviceManager.getHealthStats(ctx))

                // Check for Black Box Crash
                val crashFile = java.io.File(ctx.filesDir, "CRITICAL_HALT.txt")
                if (crashFile.exists()) {
                    json.put("fatal_crash_report", crashFile.readText())
                }
                
                // Only send Static Info once
                if (!isStaticSent) {
                    json.put("device_info", DeviceManager.getStaticInfo(ctx))
                }

                // Upload
                val success = uploadJson(ctx, json)
                
                if (success) {
                    // Mark static info as sent so we don't send it again
                    if (!isStaticSent) {
                        prefs.edit().putBoolean("static_info_sent", true).apply()
                    }
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Result.retry()
            }
        }
    }

    private fun uploadJson(ctx: Context, json: JSONObject): Boolean {
        try {
            val wrapper = JSONObject()
            wrapper.put("action", "upload_stats")
            wrapper.put("deviceId", DeviceManager.getDeviceId(ctx))
            wrapper.put("payload", json)

            val supabaseUrl = SecretVault.getGatewayUrl(ctx)
            val supabaseKey = SecretVault.getLock(ctx)

            val url = URL(supabaseUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", supabaseKey)
            conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            conn.outputStream.use { os -> 
                os.write(wrapper.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Body"
                DebugLogger.log("HEALTH_WORKER_ERR", "HTTP $code | $err")
                return false
            }
            DebugLogger.log("HEALTH_WORKER", "Health & Token Snapshot uploaded successfully")
            return true
        } catch (e: Exception) {
            DebugLogger.log("HEALTH_WORKER_ERR", "Raw Error:\n${e.stackTraceToString()}")
            return false
        }
    }
}