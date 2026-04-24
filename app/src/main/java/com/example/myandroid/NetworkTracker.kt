package com.example.myandroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkTracker {
    private var isTracking = false
    private var currentStart = 0L

    fun init(ctx: Context) {
        if (isTracking) return
        isTracking = true

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentStart = System.currentTimeMillis()
                
                val count = prefs.getInt("net_session_count", 0)
                prefs.edit().putInt("net_session_count", count + 1).apply()

                CloudManager.sendPing(ctx, "Connection Restored")
                logEvent(prefs, "Connection Restored")
            }

            override fun onLost(network: Network) {
                if (currentStart > 0) {
                    val sessionDuration = System.currentTimeMillis() - currentStart
                    val totalSoFar = prefs.getLong("net_total_time", 0L)
                    prefs.edit().putLong("net_total_time", totalSoFar + sessionDuration).apply()
                    currentStart = 0L
                }
                logEvent(prefs, "Connection Lost")
            }
        })
    }

    private fun logEvent(prefs: android.content.SharedPreferences, msg: String) {
        try {
            val logStr = prefs.getString("net_history_log", "[]") ?: "[]"
            val logArr = JSONArray(logStr)
            val entry = JSONObject().apply {
                put("event", msg)
                put("ts", System.currentTimeMillis())
            }
            logArr.put(entry)
            if (logArr.length() > 50) logArr.remove(0)
            prefs.edit().putString("net_history_log", logArr.toString()).apply()
        } catch(e: Exception) {}
    }

    fun getStats(ctx: Context): Pair<Long, Int> {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        var total = prefs.getLong("net_total_time", 0L)
        val count = prefs.getInt("net_session_count", 0)
        
        if (currentStart > 0) {
            total += (System.currentTimeMillis() - currentStart)
        }
        return Pair(total, count)
    }
}