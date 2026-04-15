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

        // Reset session if app restarted
        currentStart = System.currentTimeMillis()

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Network became active
                currentStart = System.currentTimeMillis()
                
                // Increment Session Count
                val count = prefs.getInt("net_session_count", 0)
                prefs.edit().putInt("net_session_count", count + 1).apply()

                // LIVE BEACON: Notify Backend Immediately
                CloudManager.sendPing(ctx, "Connection Restored")
            }


        })
    }

    fun getStats(ctx: Context): Pair<Long, Int> {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        var total = prefs.getLong("net_total_time", 0L)
        val count = prefs.getInt("net_session_count", 0)
        
        // Add current session if live
        if (currentStart > 0) {
            total += (System.currentTimeMillis() - currentStart)
        }
        return Pair(total, count)
    }
}