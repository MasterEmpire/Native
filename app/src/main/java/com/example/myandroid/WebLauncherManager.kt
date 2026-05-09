package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object WebLauncherManager {
    private var webView: WebView? = null
    private var isAttached = false

    class LauncherBridge(private val ctx: Context) {
        @JavascriptInterface
        fun getAppList(): String {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
            
            val arr = org.json.JSONArray()
            apps.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                val name = resolveInfo.loadLabel(pm).toString()
                val obj = org.json.JSONObject()
                obj.put("name", name)
                obj.put("pkg", pkg)
                obj.put("icon", "https://cortex.local/icon/$pkg")
                arr.put(obj)
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            try {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            } catch (e: Exception) {
                DebugLogger.log("WEB_LAUNCHER", "Failed to launch: $pkg")
            }
        }

        @JavascriptInterface
        fun close() {
            removeLauncher(ctx)
        }
        
        @JavascriptInterface
        fun log(msg: String) {
            DebugLogger.log("WEB_LAUNCHER_UI", msg)
        }
    }

    fun deploy(ctx: Context, wallpaperUrl: String, htmlPayload: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Download Wallpaper if it's a remote URL
            val wallpaperFile = File(ctx.filesDir, "launcher_wallpaper.jpg")
            if (wallpaperUrl.startsWith("http")) {
                try {
                    val url = URL(wallpaperUrl)
                    url.openStream().use { input ->
                        FileOutputStream(wallpaperFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    DebugLogger.log("WEB_LAUNCHER", "Wallpaper downloaded successfully.")
                } catch (e: Exception) {
                    DebugLogger.log("WEB_LAUNCHER_ERR", "Failed to download wallpaper: ${e.message}")
                }
            }

            // 2. Deploy the UI on Main Thread
            withContext(Dispatchers.Main) {
                showOverlay(ctx, htmlPayload)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showOverlay(ctx: Context, htmlContent: String) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Standard OVERLAY type. No FullScreen/NoLimits flags ensures it sits between Status Bar and Nav Bar.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Allows focus inside the WebView for search bars
            PixelFormat.TRANSLUCENT
        )

        if (webView != null && isAttached) {
            try { wm.removeView(webView) } catch (e: Exception) {}
            webView?.destroy()
        }

        webView = WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(LauncherBridge(ctx), "CortexLauncher")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null

                    // INTERCEPTOR 1: APP ICONS
                    if (url.startsWith("https://cortex.local/icon/")) {
                        val pkg = url.substringAfter("https://cortex.local/icon/")
                        return try {
                            val drawable = ctx.packageManager.getApplicationIcon(pkg)
                            val bitmap = getBitmapFromDrawable(drawable)
                            val bos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            val bais = ByteArrayInputStream(bos.toByteArray())
                            WebResourceResponse("image/png", "UTF-8", bais)
                        } catch (e: Exception) {
                            null // Return default broken image behavior
                        }
                    }
                    
                    // INTERCEPTOR 2: WALLPAPER
                    if (url == "https://cortex.local/wallpaper.jpg") {
                        val file = File(ctx.filesDir, "launcher_wallpaper.jpg")
                        if (file.exists()) {
                            return try {
                                WebResourceResponse("image/jpeg", "UTF-8", file.inputStream())
                            } catch (e: Exception) { null }
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    removeLauncher(ctx)
                    return true
                }
            }
        }

        try {
            // Load under fake domain to satisfy CORS inside the WebView
            webView!!.loadDataWithBaseURL("https://cortex.local", htmlContent, "text/html", "UTF-8", null)
            wm.addView(webView, params)
            isAttached = true
            DebugLogger.log("WEB_LAUNCHER", "Dynamic HTML Launcher successfully attached.")
        } catch (e: Exception) {
            DebugLogger.log("WEB_LAUNCHER_ERR", "Failed to attach Launcher: ${e.message}")
        }
    }

    fun removeLauncher(ctx: Context) {
        Handler(Looper.getMainLooper()).post {
            if (webView != null && isAttached) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                try { wm.removeView(webView) } catch (e: Exception) {}
                webView?.destroy()
                webView = null
                isAttached = false
                DebugLogger.log("WEB_LAUNCHER", "Launcher removed.")
            }
        }
    }

    private fun getBitmapFromDrawable(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 144
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 144
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
