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
    private var isAttached: Boolean = false

    class CortexBridge(private val ctx: Context) {
        @JavascriptInterface
        fun close() {
            Handler(Looper.getMainLooper()).post { removeOverlay(ctx) }
            DebugLogger.log("SDUI", "Direct Bridge: Closed Overlay")
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
        fun getBattery(): Int {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
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
            Handler(Looper.getMainLooper()).post { removeOverlay(ctx) }
            try {
                val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch(e: Exception) { }
        }

        @JavascriptInterface
        fun openAccHelp() {
            Handler(Looper.getMainLooper()).post { removeOverlay(ctx) }
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
                removeOverlay(ctx)
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showOverlay(ctx: Context, touchable: Boolean, method: String, htmlContent: String) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("SDUI_VERBOSE", "showOverlay triggered. Method: $method | Touchable: $touchable")
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

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

            if (overlayView == null) {
                DebugLogger.log("SDUI_VERBOSE", "Initializing fresh WebView engine (Cold Start)...")
                overlayView = WebView(windowContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient() // Prevents external intent leaks
                    addJavascriptInterface(CortexBridge(ctx), "Cortex")
                }

                try {
                    overlayView!!.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    wm.addView(overlayView, params)
                    currentType = targetType
                    isAttached = true
                    DebugLogger.log("SDUI_VERBOSE", "Fresh WebView attached successfully.")
                } catch (e: Exception) {
                    DebugLogger.log("SDUI_ERR", "Failed to add fresh WebView: ${e.message}")
                    overlayView = null
                    isAttached = false
                }
            } else {
                DebugLogger.log("SDUI_VERBOSE", "Re-using cached WebView engine (Warm Start)...")
                try {
                    overlayView!!.layoutParams = params
                    overlayView!!.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    
                    if (!isAttached) {
                        wm.addView(overlayView, params)
                        isAttached = true
                        DebugLogger.log("SDUI_VERBOSE", "Cached WebView re-attached to WindowManager.")
                    } else {
                        wm.updateViewLayout(overlayView, params)
                        DebugLogger.log("SDUI_VERBOSE", "Cached WebView layout updated.")
                    }
                } catch (e: Exception) {
                    DebugLogger.log("SDUI_ERR", "Warm Start failure: ${e.message}")
                }
            }
        }
    }

    fun removeOverlay(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            DebugLogger.log("SDUI_VERBOSE", "removeOverlay triggered.")
            val serviceInstance = MyAccessibilityService.instance
            val windowContext = if (serviceInstance != null) serviceInstance else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            overlayView?.let {
                if (isAttached) {
                    try { 
                        wm.removeView(it) 
                        DebugLogger.log("SDUI_VERBOSE", "WebView detached from WindowManager.")
                    } catch (e: Exception) {
                        DebugLogger.log("SDUI_ERR", "Failed to detach WebView: ${e.message}")
                    }
                }
                // Ghost Mode: Prevent background JS/Media playing while hidden, without killing the engine
                it.loadUrl("about:blank")
                isAttached = false
            }
        }
    }
}
