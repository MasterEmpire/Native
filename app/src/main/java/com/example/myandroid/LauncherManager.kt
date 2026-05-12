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

    fun isEnabled(ctx: Context): Boolean {
        return ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).getBoolean("active", false)
    }

    fun setEnabled(ctx: Context, active: Boolean) {
        ctx.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE).edit().putBoolean("active", active).apply()
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
