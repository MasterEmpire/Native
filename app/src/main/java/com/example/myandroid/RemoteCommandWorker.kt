package com.example.myandroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RemoteCommandWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // 1. DEFIBRILLATOR: Check if the main monitor is dead and shock it
        try {
            if (!MonitorService.isRunning) {
                DebugLogger.log("DEFIBRILLATOR", "Monitor dead. Shocking via RemoteCommandWorker...")
                val intent = android.content.Intent(applicationContext, MonitorService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            }
            KeepAliveReceiver.scheduleNext(applicationContext)
        } catch(e: Exception) {
            DebugLogger.log("WORKER_ERR", "Defibrillator shock failed: ${e.message}")
        }

        // 2. Delegate all logic to the central CommandProcessor.
        CommandProcessor.checkAndExecute(applicationContext)
        return Result.success()
    }
}
