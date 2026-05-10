package com.example.myandroid

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.webkit.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object WebLauncherManager {

    fun isEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).getBoolean("active", false)
    }

    fun setEnabled(ctx: Context, active: Boolean) {
        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).edit().putBoolean("active", active).apply()
    }

    fun getStoredHtml(ctx: Context): String {
        val file = File(ctx.filesDir, "launcher_ui.html")
        return if (file.exists()) file.readText() else "<html><body><h1>Launcher Not Configured</h1></body></html>"
    }

    fun saveHtml(ctx: Context, html: String) {
        File(ctx.filesDir, "launcher_ui.html").writeText(html)
    }

    class LauncherBridge(private val activity: Activity) {
        @JavascriptInterface
        fun getAppList(): String {
            val cacheFile = File(activity.filesDir, "apps_cache.json")

            // Trigger background refresh for next time
            CoroutineScope(Dispatchers.IO).launch {
                refreshAppListCache(activity)
            }

            if (cacheFile.exists()) {
                return try {
                    cacheFile.readText()
                } catch (e: Exception) {
                    refreshAppListCache(activity)
                }
            }

            // Cold start fallback
            return refreshAppListCache(activity)
        }

        private fun refreshAppListCache(ctx: Context): String {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, 0)
            
            val arr = org.json.JSONArray()
            apps.forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                val name = resolveInfo.loadLabel(pm).toString()
                val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                
                val obj = org.json.JSONObject().apply {
                    put("name", name)
                    put("pkg", pkg)
                    put("icon", "https://cortex.local/icon/$pkg")
                    put("isSystem", isSystem)
                }
                arr.put(obj)
            }
            val result = arr.toString()
            try {
                File(ctx.filesDir, "apps_cache.json").writeText(result)
                // Migration Cleanup: Remove from SharedPreferences to free RAM
                ctx.getSharedPreferences("launcher_cache", Context.MODE_PRIVATE).edit().clear().apply()
            } catch (e: Exception) { }
            return result
        }

        @JavascriptInterface
        fun openAppInfo(pkg: String) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = android.net.Uri.parse("package:$pkg")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun requestUninstall(pkg: String) {
            try {
                val intent = Intent(Intent.ACTION_DELETE)
                intent.data = android.net.Uri.parse("package:$pkg")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun launch(pkg: String) {
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun requestDefault() {
            val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            activity.startActivity(intent)
        }

        @JavascriptInterface
        fun close() {
            setEnabled(activity, false)
            val i = Intent(activity, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity.startActivity(i)
        }
        
        @JavascriptInterface
        fun log(msg: String) { DebugLogger.log("WEB_LAUNCHER", msg) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWebView(activity: Activity): WebView {
        val webView = WebView(activity)
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // PERFORMANCE: Set to NO_CACHE to ensure local assets (wallpaper/icons) are always re-intercepted
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.addJavascriptInterface(LauncherBridge(activity), "CortexLauncher")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                if (url.startsWith("https://cortex.local/icon/")) {
                    val pkg = url.substringAfter("https://cortex.local/icon/")
                    val iconCacheDir = File(activity.cacheDir, "icons")
                    if (!iconCacheDir.exists()) iconCacheDir.mkdirs()
                    val iconFile = File(iconCacheDir, "$pkg.png")

                    // 1. Serve from Disk Cache
                    if (iconFile.exists()) {
                        return WebResourceResponse("image/png", "UTF-8", iconFile.inputStream())
                    }

                    // 2. Generate and Store
                    return try {
                        val drawable = activity.packageManager.getApplicationIcon(pkg)
                        val bitmap = getBitmapFromDrawable(drawable)
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 80, bos) // 80% quality is enough for icons
                        val bytes = bos.toByteArray()
                        
                        // Save to disk for next time
                        iconFile.writeBytes(bytes)
                        
                        WebResourceResponse("image/png", "UTF-8", ByteArrayInputStream(bytes))
                    } catch (e: Exception) { null }
                }

                if (url == "https://cortex.local/wallpaper.jpg") {
                    val file = File(activity.filesDir, "launcher_wallpaper.jpg")
                    if (file.exists()) return WebResourceResponse("image/jpeg", "UTF-8", file.inputStream())
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
        
        val html = getStoredHtml(activity)
        webView.loadDataWithBaseURL("https://cortex.local", html, "text/html", "UTF-8", null)
        return webView
    }

    private fun getBitmapFromDrawable(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun applyWallpaper(ctx: Context, pathOrUrl: String) {
        val targetFile = File(ctx.filesDir, "launcher_wallpaper.jpg")
        try {
            if (pathOrUrl.startsWith("http")) {
                // Case 1: Remote URL
                URL(pathOrUrl).openStream().use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
            } else if (pathOrUrl.startsWith("/")) {
                // Case 2: Local File Path
                val srcFile = File(pathOrUrl)
                if (srcFile.exists()) {
                    srcFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) { 
            DebugLogger.log("WEB_LAUNCHER_ERR", "Wallpaper apply failed: ${e.message}")
        }
    }
}
