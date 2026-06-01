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
                // Join the tracking channel and explicitly request Broadcast permissions
                val joinMsg = JSONObject()
                joinMsg.put("topic", "realtime:tracking:$deviceId")
                joinMsg.put("event", "phx_join")
                val configObj = JSONObject().put("broadcast", JSONObject().put("self", true).put("ack", false))
                joinMsg.put("payload", JSONObject().put("config", configObj))
                joinMsg.put("ref", "1")
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("phx_reply") && text.contains("\"status\":\"ok\"")) {
                    if (text.contains("\"ref\":\"1\"")) {
                        DebugLogger.log("WS", "Channel Join Confirmed by Supabase.")
                    }
                } else if (text.contains("phx_error")) {
                    DebugLogger.log("WS_ERR", "Channel Error: $text")
                }
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
        if (!isConnected || webSocket == null) {
            DebugLogger.log("WS_WARN", "Cannot stream: Socket not connected.")
            return
        }
        
        try {
            // Send Phoenix Heartbeat to keep connection alive
            val hb = JSONObject().apply {
                put("topic", "phoenix")
                put("event", "heartbeat")
                put("payload", JSONObject())
                put("ref", "hb")
            }
            webSocket?.send(hb.toString())

            // Build Broadcast
            val payload = JSONObject()
            payload.put("lat", lat)
            payload.put("lon", lon)
            payload.put("acc", acc)
            payload.put("ts", System.currentTimeMillis())

            // Strict Supabase JSON Structure
            val innerPayload = JSONObject().put("data", payload)
            val wrap = JSONObject()
            wrap.put("type", "broadcast")
            wrap.put("event", "location_update")
            wrap.put("payload", innerPayload)

            val outer = JSONObject()
            outer.put("topic", "realtime:tracking:$currentDeviceId")
            outer.put("event", "broadcast")
            outer.put("payload", wrap)
            outer.put("ref", "2")

            webSocket?.send(outer.toString())
            DebugLogger.log("WS_STREAM", "Broadcasted location: $lat, $lon")
        } catch (e: Exception) {
            DebugLogger.log("WS_ERR", "Stream failed: ${e.message}")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Session Finished")
        webSocket = null
        isConnected = false
        DebugLogger.log("WS", "Socket Disconnected")
    }
}