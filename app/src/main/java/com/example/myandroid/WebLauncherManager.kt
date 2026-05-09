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
            val pm = activity.packageManager
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
        webView.addJavascriptInterface(LauncherBridge(activity), "CortexLauncher")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                if (url.startsWith("https://cortex.local/icon/")) {
                    val pkg = url.substringAfter("https://cortex.local/icon/")
                    return try {
                        val drawable = activity.packageManager.getApplicationIcon(pkg)
                        val bitmap = getBitmapFromDrawable(drawable)
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        WebResourceResponse("image/png", "UTF-8", ByteArrayInputStream(bos.toByteArray()))
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

    fun downloadWallpaper(ctx: Context, wallpaperUrl: String) {
        try {
            val url = URL(wallpaperUrl)
            url.openStream().use { input ->
                FileOutputStream(File(ctx.filesDir, "launcher_wallpaper.jpg")).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) { }
    }
}
