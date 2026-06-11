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
}

class DynamicTaskActivity : Activity() {
    private var nativeLifecycleOwner: DynamicUIManager.OverlayLifecycleOwner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                webViewClient = WebViewClient()
                loadDataWithBaseURL("file:///android_asset/reset_ui/", html, "text/html", "UTF-8", null)
            }
            DynamicUIManager.appModeWebView = webView
            setContentView(webView)
            DynamicAppHandoff.pendingHtml = null
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nativeLifecycleOwner?.destroy()
        if (DynamicUIManager.appModeWebView != null) {
            DynamicUIManager.appModeWebView = null
        }
    }
}
