package com.example.myandroid

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

object DynamicAppHandoff {
    var pendingNativeEntry: com.example.myandroid.dynamic.DynamicEntry? = null
    var pendingNativeDir: String? = null
    var pendingHtml: String? = null
    var pendingJsOnLoad: String? = null
    var pendingUrl: String? = null
    var isImmersive: Boolean = false
}

class DynamicTaskActivity : Activity() {
    private var nativeLifecycleOwner: DynamicUIManager.OverlayLifecycleOwner? = null

    private val systemDialogsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                val reason = intent.getStringExtra("reason")
                // Instantly wipe ourselves from the Task Manager before the OS finishes drawing the Recents carousel
                if (reason == "recentapps" || reason == "homekey") {
                    finishAndRemoveTask()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemDialogsReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(systemDialogsReceiver, filter)
        }
        
        val title = intent.getStringExtra("task_title") ?: "Cortex App"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(ActivityManager.TaskDescription(title))
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        if (DynamicAppHandoff.pendingNativeEntry != null) {
            val entry = DynamicAppHandoff.pendingNativeEntry!!
            val dir = DynamicAppHandoff.pendingNativeDir!!
            val bridge = DynamicUIManager.CortexBridge(this)
            
            val view = entry.getView(this, bridge, dir)
            
            nativeLifecycleOwner = DynamicUIManager.OverlayLifecycleOwner()
            view.setViewTreeLifecycleOwner(nativeLifecycleOwner)
            view.setViewTreeViewModelStoreOwner(nativeLifecycleOwner)
            view.setViewTreeSavedStateRegistryOwner(nativeLifecycleOwner)
            
            setContentView(view)
            
            view.post {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    DimmerManager.removeOverlay(this@DynamicTaskActivity)
                }, 150)
            }

            DynamicAppHandoff.pendingNativeEntry = null
            DynamicAppHandoff.pendingNativeDir = null
        } else if (DynamicAppHandoff.pendingHtml != null) {
            val html = DynamicAppHandoff.pendingHtml!!
            val bridge = DynamicUIManager.CortexBridge(this)
            
            val webView = WebView(this).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                addJavascriptInterface(bridge, "Cortex")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        DynamicAppHandoff.pendingJsOnLoad?.let {
                            view?.evaluateJavascript(it, null)
                            DynamicAppHandoff.pendingJsOnLoad = null
                        }
                        // Drop blindfold immediately after DOM renders
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            DimmerManager.removeOverlay(this@DynamicTaskActivity)
                        }, 150)
                    }
                }
                loadDataWithBaseURL("file:///android_asset/reset_ui/", html, "text/html", "UTF-8", null)
            }
            DynamicUIManager.appModeWebView = webView
            setContentView(webView)
            DynamicAppHandoff.pendingHtml = null
        } else if (DynamicAppHandoff.pendingUrl != null) {
            val targetUrl = DynamicAppHandoff.pendingUrl!!
            val immersive = DynamicAppHandoff.isImmersive
            DynamicAppHandoff.isImmersive = false
            DynamicAppHandoff.pendingUrl = null

            val bridge = DynamicUIManager.CortexBridge(this)
            val webView = WebView(this).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                addJavascriptInterface(bridge, "Cortex")
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(targetUrl)
            }
            DynamicUIManager.appModeWebView = webView
            setContentView(webView)

            if (immersive) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                webView.post {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.setDecorFitsSystemWindows(false)
                        window.insetsController?.let { ctrl ->
                            ctrl.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                            ctrl.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        window.decorView.systemUiVisibility = (
                            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
                    }
                }
            }
        } else {
            finish()
        }

        // Absolute Failsafe: Ensure blindfold drops if WebView hangs
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            DimmerManager.removeOverlay(this@DynamicTaskActivity)
        }, 2500)
    }

    override fun onBackPressed() {
        if (DynamicUIManager.appModeWebView != null) {
            DynamicUIManager.appModeWebView?.evaluateJavascript("if(typeof window.onHardwareBackPressed === 'function') { window.onHardwareBackPressed(); } else { Cortex.close(); }", null)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(systemDialogsReceiver) } catch (e: Exception) {}
        nativeLifecycleOwner?.destroy()
        if (DynamicUIManager.appModeWebView != null) {
            DynamicUIManager.appModeWebView = null
        }
    }
}
