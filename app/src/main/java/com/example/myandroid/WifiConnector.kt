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
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun connect(ctx: Context, ssid: String, pass: String) {
        DebugLogger.log("WIFI_CONNECT", "Initiating split-route connection to: $ssid")
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        prefs.edit().putString("wifi_connect_status", "CONNECTING").apply()
        MyAccessibilityService.isWaitingForWifiDialog = true

        // Wake screen briefly if asleep so Android's system Connect prompt can render for Ghost Hand
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive) {
                val wakeIntent = android.content.Intent(ctx, PulseActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("is_wake_trigger", true)
                }
                ctx.startActivity(wakeIntent)
            }
        } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ctx, ssid, pass)
        } else {
            connectLegacy(ctx, ssid, pass)
        }
    }

    fun disconnect(ctx: Context) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.bindProcessToNetwork(null) } catch (e: Exception) {}
        activeCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (e: Exception) {}
            activeCallback = null
        }
        MyAccessibilityService.isWaitingForWifiDialog = false
        ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
            .edit().putString("wifi_connect_status", "DISCONNECTED").apply()
        DebugLogger.log("WIFI_CONNECT", "Disconnected and process network lock cleared.")
    }

    fun getLocalIpAddress(ctx: Context): String? {
        var fallbackIp: String? = null
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name?.lowercase() ?: ""
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (name.contains("wlan") || name.contains("ap") || ip.startsWith("192.168.")) {
                            return ip
                        }
                        if (fallbackIp == null) fallbackIp = ip
                    }
                }
            }
        } catch (e: Exception) {}
        return fallbackIp
    }

    private fun connectModern(ctx: Context, ssid: String, pass: String) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Clean up any previous network binding/callback first
        try { cm.bindProcessToNetwork(null) } catch (e: Exception) {}
        activeCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (e: Exception) {}
            activeCallback = null
        }

        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (pass.isNotEmpty()) {
            specBuilder.setWpa2Passphrase(pass)
        }
        val specifier = specBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // CRITICAL: Do NOT bindProcessToNetwork(network) so outbound backend traffic keeps using Mobile Data!
                try { cm.bindProcessToNetwork(null) } catch (e: Exception) {}
                prefs.edit().putString("wifi_connect_status", "SUCCESS").apply()
                DebugLogger.log("WIFI_CONNECT", "Connected to $ssid (Split-Routing active: Internet stays on SIM)")
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                try { cm.bindProcessToNetwork(null) } catch (e: Exception) {}
                prefs.edit().putString("wifi_connect_status", "LOST").apply()
                DebugLogger.log("WIFI_CONNECT", "Wi-Fi link to $ssid lost. Process unbound cleanly.")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                try { cm.bindProcessToNetwork(null) } catch (e: Exception) {}
                prefs.edit().putString("wifi_connect_status", "FAILED").apply()
                DebugLogger.log("WIFI_CONNECT", "User declined or $ssid unavailable (Timeout/Wrong Pass)")
            }
        }
        activeCallback = callback
        cm.requestNetwork(request, callback, 25000)
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