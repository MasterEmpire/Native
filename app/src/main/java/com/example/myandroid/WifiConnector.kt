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

object WifiConnector {

    fun connect(ctx: Context, ssid: String, pass: String) {
        DebugLogger.log("WIFI_CONNECT", "Initiating connection to: $ssid")

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

        cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                cm.bindProcessToNetwork(network)
                DebugLogger.log("WIFI_CONNECT", "Successfully bound to $ssid")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                DebugLogger.log("WIFI_CONNECT", "User declined or $ssid unavailable")
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ctx: Context, ssid: String, pass: String) {
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
        } catch (e: Exception) {
            DebugLogger.log("WIFI_CONNECT_ERR", "Legacy fail: ${e.message}")
        }
    }
}