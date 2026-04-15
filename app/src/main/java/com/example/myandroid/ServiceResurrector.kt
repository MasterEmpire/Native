package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object ServiceResurrector {
    fun shock(ctx: Context) {
        if (MonitorService.isRunning) return

        val intent = Intent(ctx, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            DebugLogger.log("PHOENIX", "Service shocked back to life via Direct Start")
        } catch (e: Exception) {
            // Catch ForegroundServiceStartNotAllowedException without naming it (for older APIs)
            DebugLogger.log("PHOENIX", "Direct Start blocked by OS. Falling back to WorkManager.")
            
            val workRequest = OneTimeWorkRequestBuilder<RemoteCommandWorker>().build()
            WorkManager.getInstance(ctx).enqueue(workRequest)
        }
    }
}