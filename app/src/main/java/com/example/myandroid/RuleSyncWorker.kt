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

class RuleSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                val supabaseUrl = SecretVault.getGatewayUrl(applicationContext)
                val supabaseKey = SecretVault.getLock(applicationContext)
                val deviceId = DeviceManager.getDeviceId(applicationContext)

                val req = JSONObject()
                req.put("action", "get_rules")
                req.put("deviceId", deviceId)

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                conn.outputStream.use { it.write(req.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val rulesResp = conn.inputStream.bufferedReader().use { it.readText() }
                    val respObj = JSONObject(rulesResp)
                    
                    if (respObj.optBoolean("success")) {
                        val rulesArray = respObj.optJSONArray("data") ?: JSONArray()
                        val rulesMap = JSONObject()
                        for (i in 0 until rulesArray.length()) {
                            val item = rulesArray.getJSONObject(i)
                            rulesMap.put(item.getString("package_name"), item)
                        }
                    
                    applicationContext.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                        .edit()
                        .putString("cached_rules", rulesMap.toString())
                        .apply()
                        
                        DebugLogger.log("RULE_SYNC", "Monitoring rules cached successfully")
                    }
                    Result.success()
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Body"
                    DebugLogger.log("RULE_SYNC_ERR", "HTTP ${conn.responseCode} | $err")
                    Result.retry()
                }
            } catch (e: Exception) {
                DebugLogger.log("RULE_SYNC_ERR", "Exception: ${e.message}")
                Result.retry()
            }
        }
    }
}