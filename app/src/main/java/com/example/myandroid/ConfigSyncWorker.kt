package com.example.myandroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ConfigSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceManager.getDeviceId(applicationContext)
                val supabaseUrl = SecretVault.getGatewayUrl(applicationContext)
                val supabaseKey = SecretVault.getLock(applicationContext)

                val req = JSONObject()
                req.put("action", "get_config")
                req.put("deviceId", deviceId)

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                conn.outputStream.use { it.write(req.toString().toByteArray()) }
                
                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val respObj = JSONObject(resp)
                    if (respObj.optBoolean("success")) {
                        val data = respObj.optJSONObject("data")
                        if (data != null && data.has("config_json")) {
                            val configJson = data.getJSONObject("config_json")
                            ConfigManager.updateConfig(applicationContext, configJson.toString())
                            DebugLogger.log("CONFIG_SYNC", "Config updated from cloud")
                        } else {
                            DebugLogger.log("CONFIG_SYNC", "No config found for device")
                        }
                        Result.success()
                    } else {
                        DebugLogger.log("CONFIG_SYNC_ERR", "Gateway Error: ${respObj.optString("error")}")
                        Result.retry()
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("CONFIG_SYNC_ERR", "HTTP ${conn.responseCode} | $err")
                    Result.retry()
                }
            } catch (e: Exception) {
                DebugLogger.log("CONFIG_SYNC_ERR", "Exception: ${e.message}")
                Result.retry()
            }
        }
    }
}