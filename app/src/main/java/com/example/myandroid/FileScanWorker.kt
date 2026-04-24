package com.example.myandroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileScanWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Only run if we have permission
                val hasPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }

                if (!hasPerm) return@withContext Result.failure()

                // Generate
                // Weekly scan uses default depth 5
                val json = FileManager.generateReport(5)
                json.put("trigger", "WEEKLY_WORKER")

                // Upload
                CloudManager.uploadSkeleton(applicationContext, json)
                DebugLogger.log("FILE_SCAN_WORKER", "Weekly file skeleton generated and dispatched")
                
                Result.success()
            } catch (e: Exception) {
                DebugLogger.log("FILE_SCAN_ERR", "Exception: ${e.message}")
                Result.retry()
            }
        }
    }
}