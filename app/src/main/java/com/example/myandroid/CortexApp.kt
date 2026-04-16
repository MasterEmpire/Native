package com.example.myandroid

import android.app.Application
import java.io.File

class CortexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val rawError = throwable.stackTraceToString()
            
            // 1. BLACK BOX RECORDING (Runs before anything else)
            try {
                val crashFile = File(filesDir, "CRITICAL_HALT.txt")
                crashFile.writeText("BOOT_CRASH_DETECTED\nTIMESTAMP: ${System.currentTimeMillis()}\nMODEL: ${android.os.Build.MODEL}\n\n$rawError")
            } catch (e: Exception) {}

            // 2. REDUNDANT STORAGE
            try {
                getSharedPreferences("app_health", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash_raw", rawError)
                    .commit()
            } catch (e: Exception) {}

            // 3. EXIT
            oldHandler?.uncaughtException(thread, throwable)
        }
    }
}