package com.example.myandroid

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SocketManager {
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var currentDeviceId: String? = null
    private var isConnected = false

    fun connect(ctx: Context) {
        if (isConnected) return
        
        val deviceId = DeviceManager.getDeviceId(ctx)
        currentDeviceId = deviceId
        val supabaseUrl = SecretVault.getGatewayUrl(ctx)
            .replace("functions/v1/cortex-gateway", "realtime/v1/websocket")
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val apiKey = SecretVault.getLock(ctx)
        
        val url = "$supabaseUrl?apikey=$apiKey&vsn=1.0.0"
        
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        
        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                DebugLogger.log("WS", "Socket Connected. Joining Channel...")
                // Join the tracking channel
                val joinMsg = JSONObject()
                joinMsg.put("topic", "realtime:tracking:$deviceId")
                joinMsg.put("event", "phx_join")
                joinMsg.put("payload", JSONObject())
                joinMsg.put("ref", "1")
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Heartbeat/Ack handling if needed
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                DebugLogger.log("WS_ERR", "Socket Failure: ${t.message}")
            }
        })
    }

    fun streamLocation(lat: Double, lon: Double, acc: Float) {
        if (!isConnected || webSocket == null) return
        
        try {
            val payload = JSONObject()
            payload.put("lat", lat)
            payload.put("lon", lon)
            payload.put("acc", acc)
            payload.put("ts", System.currentTimeMillis())

            val outer = JSONObject()
            outer.put("topic", "realtime:tracking:$currentDeviceId")
            outer.put("event", "broadcast")
            val wrap = JSONObject()
            wrap.put("type", "location_update")
            wrap.put("data", payload)
            outer.put("payload", wrap)
            outer.put("ref", "2")

            webSocket?.send(outer.toString())
        } catch (e: Exception) {}
    }

    fun disconnect() {
        webSocket?.close(1000, "Session Finished")
        webSocket = null
        isConnected = false
        DebugLogger.log("WS", "Socket Disconnected")
    }
}