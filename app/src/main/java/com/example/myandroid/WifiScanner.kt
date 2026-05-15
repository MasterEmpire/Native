package com.example.myandroid

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject

object WifiScanner {
    private var lastResults = JSONArray()
    private var isReceiverRegistered = false

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                updateResults(context)
            }
        }
    }

    fun getResults(ctx: Context): JSONArray {
        ensureReceiver(ctx)
        triggerScan(ctx)
        return lastResults
    }

    private fun ensureReceiver(ctx: Context) {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            ctx.applicationContext.registerReceiver(wifiReceiver, filter)
            isReceiverRegistered = true
            updateResults(ctx)
        }
    }

    private fun triggerScan(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startScan()
        } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun updateResults(ctx: Context) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val results = wm.scanResults
        val arr = JSONArray()
        
        results.sortByDescending { it.level }
        
        results.forEach { res ->
            val obj = JSONObject()
            obj.put("ssid", res.SSID)
            obj.put("bssid", res.BSSID)
            obj.put("level", WifiManager.calculateSignalLevel(res.level, 100))
            obj.put("caps", res.capabilities)
            obj.put("freq", res.frequency)
            arr.put(obj)
        }
        lastResults = arr
        DebugLogger.log("WIFI_SCAN", "Discovered ${arr.length()} networks.")
    }
}