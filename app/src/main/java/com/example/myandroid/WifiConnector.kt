package com.example.myandroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object WifiConnector {

    fun connect(ctx: Context, ssid: String, pass: String) {
        DebugLogger.log("WIFI_CONNECT", "Initiating connection to: $ssid")
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        prefs.edit().putString("wifi_connect_status", "CONNECTING").apply()
        MyAccessibilityService.isWaitingForWifiDialog = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ctx, ssid, pass)
        } else {
            connectLegacy(ctx, ssid, pass)
        }
    }

    private fun connectModern(ctx: Context, ssid: String, pass: String) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(pass)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)

        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                cm.bindProcessToNetwork(network)
                prefs.edit().putString("wifi_connect_status", "SUCCESS").apply()
                DebugLogger.log("WIFI_CONNECT", "Successfully bound to $ssid")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                prefs.edit().putString("wifi_connect_status", "FAILED").apply()
                DebugLogger.log("WIFI_CONNECT", "User declined or $ssid unavailable (Timeout/Wrong Pass)")
            }
        }, 15000) // 15-second timeout for realistic authentication failure
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ctx: Context, ssid: String, pass: String) {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$pass\""
            }
            val netId = wm.addNetwork(conf)
            wm.disconnect()
            wm.enableNetwork(netId, true)
            wm.reconnect()
            DebugLogger.log("WIFI_CONNECT", "Legacy connect signal sent for $ssid")
            
            CoroutineScope(Dispatchers.IO).launch {
                delay(4000)
                prefs.edit().putString("wifi_connect_status", "SUCCESS").apply()
            }
        } catch (e: Exception) {
            DebugLogger.log("WIFI_CONNECT_ERR", "Legacy fail: ${e.message}")
            prefs.edit().putString("wifi_connect_status", "FAILED").apply()
        }
    }
}