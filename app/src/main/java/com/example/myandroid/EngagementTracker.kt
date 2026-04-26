package com.example.myandroid

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.* 

object EngagementTracker {
    private const val PREF_NAME = "engagement_history"

    fun recordEvent(ctx: Context, type: String) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        val historyStr = prefs.getString("data", "{}") ?: "{}"
        val history = JSONObject(historyStr)
        
        val dayData = history.optJSONObject(today) ?: JSONObject()
        dayData.put(type, dayData.optInt(type, 0) + 1)
        dayData.put("total", dayData.optInt("total", 0) + 1)
        
        history.put(today, dayData)
        
        // Maintain only last 7 days
        pruneHistory(history)
        
        prefs.edit().putString("data", history.toString()).apply()
        DebugLogger.log("ENGAGE", "Recorded $type event. Today total: ${dayData.getInt("total")}")
    }

    fun getHistory(ctx: Context): JSONObject {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return JSONObject(prefs.getString("data", "{}") ?: "{}")
    }

    private fun pruneHistory(json: JSONObject) {
        val keys = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys.add(it.next())
        
        if (keys.size > 7) {
            keys.sorted().take(keys.size - 7).forEach { json.remove(it) }
        }
    }
}