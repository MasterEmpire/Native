package com.example.dynamic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.example.myandroid.dynamic.DynamicEntry
import com.example.myandroid.dynamic.CortexNativeAPI
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AgentUI : DynamicEntry() {
    companion object {
        var activeEngine: AgentEngine? = null
        var activeApi: CortexNativeAPI? = null
        var receiver: android.content.BroadcastReceiver? = null
    }

    override fun onStart(context: Context, bridge: Any, baseDir: String) {
        val api = CortexNativeAPI(bridge)
        activeApi = api
        api.log("[HEADLESS] Starting Silent Interception Agent Engine...")

        val engine = AgentEngine(context, api) {
            // Collapse request (no-op in headless mode)
        }
        activeEngine = engine

        // Register global task-control receivers
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent) {
                when (intent.action) {
                    "com.cortex.agent.DISCONNECT" -> {
                        engine.disconnect()
                        api.close()
                    }
                    "com.cortex.agent.TOGGLE_MIC" -> {
                        engine.toggleMic()
                    }
                }
            }
        }
        receiver = r

        val filter = android.content.IntentFilter().apply {
            addAction("com.cortex.agent.DISCONNECT")
            addAction("com.cortex.agent.TOGGLE_MIC")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(r, filter)
        }

        val controlFilter = android.content.IntentFilter("com.cortex.action.AGENT_CONTROL")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(engine.controlReceiver, controlFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(engine.controlReceiver, controlFilter)
        }
    }

    override fun onStop(context: Context) {
        activeApi?.log("[HEADLESS] Terminating Silent Interception Agent Engine...")
        activeEngine?.disconnect()
        activeEngine = null

        try { receiver?.let { context.unregisterReceiver(it) } } catch(e: Exception) {}
        try { activeEngine?.let { context.unregisterReceiver(it.controlReceiver) } } catch(e: Exception) {}
        receiver = null
        activeApi = null
    }

    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        // Interactive fallback: if launched face-to-face, ensure engine is warm
        if (activeEngine == null) {
            onStart(context, bridge, baseDir)
        }
        return ComposeView(context).apply {
            setContent { 
                MaterialTheme {
                    AgentScreen(context, bridge, activeEngine!!)
                } 
            }
            post {
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        params.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        wm.updateViewLayout(this, params)
                    }
                } catch(e: Exception) {}
            }
        }
    }
}

class AgentEngine(val ctx: Context, val api: CortexNativeAPI, val onCollapseRequested: () -> Unit) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var videoJob: Job? = null
    
    val state = mutableStateOf("OFFLINE")
    val isVideoActive = mutableStateOf(false)
    val transcripts = mutableStateListOf<Pair<String, String>>()
    val audioRoute = mutableStateOf(ctx.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).getString("audio_route", "SPEAKER") ?: "SPEAKER")
    val isSlaveMode = mutableStateOf(false)

    val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            if (intent.action == "com.cortex.action.AGENT_CONTROL") {
                val payloadStr = intent.getStringExtra("payload") ?: return
                try {
                    val payload = JSONObject(payloadStr)
                    when (payload.getString("action")) {
                        "SILENT_SLAVE_ON" -> {
                            api.log("[SLAVE] Intercept Mode ON. Silencing local audio pipelines.")
                            isSlaveMode.value = true
                            disconnect()
                            state.value = "SLAVE_ACTIVE"
                            sendSlaveBroadcast("slave_status", JSONObject().put("status", "ACTIVE"))
                        }
                        "SILENT_SLAVE_OFF" -> {
                            api.log("[SLAVE] Intercept Mode OFF. Resuming native audio session.")
                            isSlaveMode.value = false
                            connect()
                        }
                        "TOGGLE_EYE" -> {
                            val target = payload.getBoolean("active")
                            if (target != isVideoActive.value) {
                                toggleVideo()
                            }
                        }
                        "EXECUTE_TOOL" -> {
                            val toolName = payload.getString("name")
                            val toolId = payload.getString("id")
                            val args = payload.optJSONObject("args") ?: JSONObject()
                            executeToolOnSlave(toolName, toolId, args)
                        }
                    }
                } catch(e: Exception) {}
            }
        } 
    }

    private fun sendSlaveBroadcast(event: String, data: JSONObject) {
        try {
            // Memory-direct injection to bypass Android Intent limits (1MB Binder limit)
            // Prevents 'RemoteServiceException: can't deliver broadcast' when piping heavy Base64 image frames.
            val clazz = Class.forName("com.example.myandroid.SocketManager")
            val instance = clazz.getField("INSTANCE").get(null)
            clazz.getMethod("broadcast", String::class.java, org.json.JSONObject::class.java).invoke(instance, event, data)
        } catch (e: Exception) {
            // Fallback to standard broadcast for lightweight events (e.g. status updates) if reflection fails
            val intent = android.content.Intent("com.cortex.action.AGENT_BROADCAST").apply {
                putExtra("event", event)
                putExtra("data", data.toString())
            }
            ctx.sendBroadcast(intent)
        }
    }

    private fun executeToolOnSlave(name: String, id: String, args: JSONObject) {
        api.log("[SLAVE_TOOL] Executing remote tool: $name (ID: $id)")
        scope.launch {
            val result = JSONObject()
            try {
                withTimeout(15000L) { // 15-second absolute failsafe to prevent Gemini hang
                    when (name) {
                        "get_battery" -> result.put("output", "Battery is at ${api.getBattery()}% 🔋")
                        "get_system_info" -> result.put("output", api.getSystemInfo())
                        "get_ui_hierarchy" -> result.put("output", api.getUiTree())
                        "take_screenshot" -> {
                            val deferred = CompletableDeferred<String?>()
                            api.getScreenB64 { b64 -> deferred.complete(b64) }
                            val b64 = withTimeoutOrNull(5000L) { deferred.await() } // Prevent OS drop from hanging coroutine
                            if (b64 != null) {
                                result.put("output", "Annotated screenshot captured.")
                                result.put("screenshot", b64)
                            } else {
                                result.put("error", "Failed to capture screenshot. The OS may have blocked it or it timed out.")
                            }
                        }
                        "search_installed_apps" -> {
                            val query = args.getString("query")
                            val pm = ctx.packageManager
                            val packages = pm.getInstalledPackages(0)
                            val results = JSONArray()
                            for (pkg in packages) {
                                val appName = pkg.applicationInfo.loadLabel(pm).toString()
                                if (appName.contains(query, ignoreCase = true)) {
                                    val obj = JSONObject()
                                    obj.put("app_name", appName)
                                    obj.put("package_name", pkg.packageName)
                                    results.put(obj)
                                }
                            }
                            result.put("output", results.toString())
                        }
                        "execute_interaction_chain" -> {
                            val chain = args.getJSONArray("chain")
                            executeChainOnSlave(id, chain)
                            return@withTimeout // Chain execution manages its own broadcast response
                        }
                    }
                }
            } catch(e: TimeoutCancellationException) {
                result.put("error", "Tool execution timed out natively on device.")
                api.log("[SLAVE_TOOL_ERR] Timeout during execution of: $name")
            } catch(e: Exception) {
                result.put("error", e.message)
                api.log("[SLAVE_TOOL_ERR] Exception in $name: ${e.message}")
            }
            val resp = JSONObject().apply {
                put("id", id)
                put("name", name)
                put("response", result)
            }
            api.log("[SLAVE_TOOL] Dispatching result over the air. Length: ${resp.toString().length} bytes.")
            sendSlaveBroadcast("slave_tool_result", resp)
        }
    } 

    private fun executeChainOnSlave(toolId: String, chain: JSONArray) {
        scope.launch {
            val result = JSONObject()
            try {
                withTimeout(25000L) { // 25s timeout for complex chains
                    api.log("[SLAVE_CHAIN] Dispatching REMOTE_TOUCH intent to system...")
                    val cmd = JSONObject()
                    cmd.put("file_name", "REMOTE_TOUCH")
                    cmd.put("content", chain.toString())
                    api.executeCommand(cmd.toString())
                    
                    var totalDelayMs = 0L
                    for (j in 0 until chain.length()) {
                        val step = chain.getJSONObject(j)
                        totalDelayMs += step.optLong("delay", 200L)
                        if (step.optString("type").uppercase() == "WAIT") {
                            totalDelayMs += step.optString("val").toLongOrNull() ?: 500L
                        } 
                    } 
                    totalDelayMs += 2500L
                    api.log("[SLAVE_CHAIN] Waiting ${totalDelayMs}ms for screen to settle...")
                    delay(totalDelayMs)
                    
                    val newTree = api.getUiTree()
                    var b64: String? = null
                    if (isVideoActive.value) {
                        api.log("[SLAVE_CHAIN] Capturing post-chain snapshot...")
                        val deferred = CompletableDeferred<String?>()
                        api.getScreenB64 { res -> deferred.complete(res) }
                        b64 = withTimeoutOrNull(5000L) { deferred.await() }
                    } 
                    
                    val outputObj = JSONObject().apply {
                        put("status", "EXECUTION_COMPLETE")
                        put("tree", newTree)
                        if (b64 != null) put("screenshot", b64)
                    }
                    result.put("output", outputObj.toString())
                }
            } catch(e: TimeoutCancellationException) {
                result.put("error", "Interaction chain timed out natively on device.")
                api.log("[SLAVE_CHAIN_ERR] Timeout during chain execution.")
            } catch(e: Exception) {
                result.put("error", e.message)
                api.log("[SLAVE_CHAIN_ERR] Exception: ${e.message}")
            }
            
            val resp = JSONObject().apply {
                put("id", toolId)
                put("name", "execute_interaction_chain")
                put("response", result)
            }
            api.log("[SLAVE_CHAIN] Dispatching chain result over the air. Length: ${resp.toString().length} bytes.")
            sendSlaveBroadcast("slave_tool_result", resp)
        }
    }

    fun setAudioRoute(route: String) {
        if (audioRoute.value == route) return
        audioRoute.value = route
        ctx.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).edit().putString("audio_route", route).apply()
        
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isSpeakerphoneOn = (route == "SPEAKER")
            api.log("[AUDIO_ROUTE] Route changed to $route. Speakerphone is now: ${am.isSpeakerphoneOn}")
        } catch(e: Exception) {
            api.log("[AUDIO_ROUTE_ERR] Failed to set speakerphone state: ${e.message}")
        }
        
        if (state.value != "OFFLINE" && ws != null) {
            reinitAudioTrack()
        }
    }

    private fun reinitAudioTrack() {
        try {
            api.log("[AUDIO_REINIT] Tearing down existing AudioTrack...")
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch(e: Exception) { api.log("[AUDIO_REINIT_ERR] Teardown failed: ${e.message}") }
        
        val sessionId = audioRecord?.audioSessionId
        if (sessionId != null) {
            api.log("[AUDIO_REINIT] Active AudioRecord found. Rebuilding track with shared SessionID: $sessionId")
            initAudioTrack(sessionId)
        } else {
            api.log("[AUDIO_REINIT] No active AudioRecord. Rebuilding AudioTrack on default session for fallback playback.")
            initAudioTrack(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
    }
    
    fun connect() { 
        if (ws != null || isSlaveMode.value) return
        state.value = "CONNECTING"
        
        val request = Request.Builder().url("wss://gemini-live-proxy.getyeteklu2.workers.dev").build()
        ws = client.newWebSocket(request, object : WebSocketListener() { 
            override fun onOpen(webSocket: WebSocket, response: Response) { 
                state.value = "CONNECTED"
                api.log("[WS_CONN] Agent WS Connected natively. Handshake successful. Initializing fallback AudioTrack.")
                initAudioTrack(android.media.AudioManager.AUDIO_SESSION_ID_GENERATE)
                sendSetup()
            } 
            
            override fun onMessage(webSocket: WebSocket, text: String) { 
                handleMessage(text)
            } 
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { 
                api.log("Agent WS Closing natively. Code: $code, Reason: $reason")
                disconnect()
            } 
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { 
                api.log("Agent WS Closed natively. Code: $code, Reason: $reason")
                disconnect()
            } 
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { 
                val respBody = try { response?.body?.string() } catch(e: Exception) { "none" }
                api.log("Agent WS Failure:\\nMessage: ${t.message}\\nTrace: ${t.stackTraceToString()}\\nResponse Code: ${response?.code}\\nResponse Body: $respBody")
                disconnect()
            } 
        })
    } 
    
    private fun initAudioTrack(sessionId: Int) { 
        try { 
            api.log("[AUDIO_INIT] Building AudioTrack with Shared SessionID: $sessionId")
            val usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
            
            try { 
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = (audioRoute.value == "SPEAKER")
                api.log("[AUDIO_INIT] Set Audio Mode IN_COMMUNICATION. Speakerphone: ${am.isSpeakerphoneOn}")
            } catch(e: Exception) { api.log("[AUDIO_INIT_ERR] AudioManager config failed: ${e.message}") }

            val minTrackBufferSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setSessionId(sessionId)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minTrackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            api.log("[AUDIO_INIT] AudioTrack playing successfully on SessionID: ${audioTrack?.audioSessionId}")
        } catch(e: Exception) { 
            api.log("[AUDIO_INIT_ERR] AudioTrack creation failed: ${e.message}\\n${e.stackTraceToString()}")
        } 
    } 
    
    private fun sendSetup() { 
        try { 
            val setup = JSONObject()
            val setupBlock = JSONObject()
            setupBlock.put("model", "models/gemini-3.1-flash-live-preview")
            setupBlock.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            
            val systemPrompt = """
                You are "Cortex-OS", an autonomous, high-speed Android system-control agent. You communicate via full-duplex voice.
                Instead of a video feed, you receive real-time "Semantic Maps" of the screen. These are injected silently into your context window whenever the screen changes.

                --- SEMANTIC TARGETING & INTERACTION ---
                The Semantic Map is a numbered list of all interactive elements currently visible on the screen.
                Example format:
                [1] Button: Wi-Fi
                [2] Toggle: Bluetooth [OFF]
                [3] Input: Search settings

                To click an element, simply read its ID from the map, prefix it with a '#' (e.g. '#1', '#2'), and pass it to the TAP command.
                NEVER guess an ID. ALWAYS use the exact IDs provided in the most recent Semantic Map.

                --- MASTERING THE GHOST HAND (execute_interaction_chain) ---
                You can package multiple steps into a single 'chain' array to perform complex macros. Each step must contain:
                1. "type": 'TAP', 'WRITE', 'SWIPE', 'PATH', 'NAV', 'WAIT', 'DIM', 'WAKE', or 'INTENT'.
                2. "val": The payload value.
                3. "delay": Delay in milliseconds before executing this step.

                Types:
                - TAP: Triggers a single touch. Formatted as the Semantic Map ID (e.g. "#1", "#5").
                - WRITE: Writes text directly into a text field. Formatted as "badge_id|text_to_write" (e.g. "#3|My search query"). This performs instant, programmatic text injection.
                - SWIPE: Linear drag. "X1,Y1,X2,Y2,duration_ms".
                - NAV: "BACK", "HOME", "RECENTS", or "NOTIFS".
                - WAIT: Pause execution. Time in ms (e.g. "1000").
                - DIM: Adjust brightness. "level,method" (e.g. "0,ACC", "100,HARDWARE").
                - WAKE: Ignite screen. "standard" or "wellbeing".
                - INTENT: Execute raw Android Intent JSON. To launch/open any application by its package name, always use the exact format: {"action":"android.intent.action.MAIN","pkg":"TARGET_PACKAGE_NAME","target":"activity"}. Our system will automatically resolve and start the standard launcher activity for that package. Do not provide a class ("cls") unless deep-linking.

                --- STRICT APP-LAUNCHING HEURISTICS ---
                - For globally standard, popular, or system-default apps (such as WhatsApp, Telegram, Chrome, YouTube, Calculator, Settings, Maps, Gmail), you already know their standard package names. You MUST launch them directly via INTENT without searching.
                - You are STRICTLY PROHIBITED from executing search_installed_apps for any app that has a universally recognized name. Doing so adds latency and is an anti-pattern.
                - The `search_installed_apps` tool is strictly reserved as a fallback for highly regional, proprietary, custom, or niche third-party apps (e.g., local mobile wallets like 'telebirr', custom regional banking apps, or unique local utilities) where a standard package name does not exist in your training data.

                --- SYSTEM OPERATIONAL PROTOCOLS ---
                1. PASSIVE UNTIL COMMANDED: DO NOT execute any tools or manipulate the device unless the user explicitly requests an action. If the user says "hello" or makes casual conversation, simply reply conversationally.
                2. Always prioritize the most recent Semantic Map injected into your context.
                3. Execute actions silently via the tool chain. Your UI will auto-collapse during physical macro execution.
                4. AUTONOMOUS LOOP: When you use `execute_interaction_chain`, the tool will execute the actions, wait for the screen to settle, and return the NEW Semantic Map as the tool output.
                5. DO NOT verbally narrate every step. If you are completing a multi-step objective, silently evaluate the returned Semantic Map and immediately fire your next tool call until the goal is reached. Only speak to the user when the entire objective is completed, or if you are stuck.
            """.trimIndent() 

            val sysInstruction = JSONObject()
            val partsArray = JSONArray()
            partsArray.put(JSONObject().put("text", systemPrompt))
            sysInstruction.put("parts", partsArray)
            setupBlock.put("systemInstruction", sysInstruction)
            
            val tools = JSONArray()
            val toolDecls = JSONArray()
            toolDecls.put(JSONObject().put("name", "get_battery").put("description", "Get the device battery percentage"))
            toolDecls.put(JSONObject().put("name", "get_system_info").put("description", "Get hardware and identity diagnostics"))
            toolDecls.put(JSONObject().put("name", "take_screenshot").put("description", "Capture and upload screen evidence"))
            toolDecls.put(JSONObject().put("name", "get_ui_hierarchy").put("description", "Get the current screen UI element hierarchy (JSON) with exact bounding boxes for precise tapping."))
            toolDecls.put(JSONObject().apply { 
                put("name", "search_installed_apps")
                put("description", "Searches for installed applications on the device by their display name using case-insensitive matching. Returns a list of matching apps and their package names. Use this ONLY for local, regional, or custom apps when you do not know their package names. Do NOT use this tool for universally known apps (like WhatsApp, Chrome, YouTube, Telegram) whose package names are universally standard and known to you.")
                put("parameters", JSONObject().apply { 
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply { 
                        put("query", JSONObject().apply { 
                            put("type", "STRING")
                            put("description", "The app display name search query (e.g., 'telebirr', 'bank').")
                        }) 
                    })
                    put("required", JSONArray().put("query"))
                }) 
            })
            
            toolDecls.put(JSONObject().apply { 
                put("name", "execute_interaction_chain")
                put("description", "Executes a highly powerful, system-level sequence/macro of interactions on the device. This supports chaining multiple clicks, swipes, multi-point paths (drawing), keyboard writing, waiting, hardware dimming, waking, navigation, and system intents in a single turn.")
                put("parameters", JSONObject().apply { 
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply { 
                        put("chain", JSONObject().apply { 
                            put("type", "ARRAY")
                            put("description", "An ordered list of steps to execute in sequence.")
                            put("items", JSONObject().apply { 
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply { 
                                    put("type", JSONObject().apply { 
                                        put("type", "STRING")
                                        put("description", "The type of interaction: 'TAP' (X,Y coordinate), 'SWIPE' (X1,Y1,X2,Y2), 'PATH' (multi-point drawing), 'NAV' (BACK, HOME, RECENTS, NOTIFS), 'NODE' (clicks center of view matched by text/ID), 'WAIT' (pause in ms), 'DIM' (level,method), 'WAKE' (standard/wellbeing), or 'INTENT' (JSON payload).")
                                    }) 
                                    put("val", JSONObject().apply { 
                                        put("type", "STRING")
                                        put("description", "Value parameter. Examples: TAP -> '500,800'; SWIPE -> '100,200,800,200'; PATH -> '100,200,500,200,500,800,100,800' (optionally append duration like ',500'); NAV -> 'BACK'; NODE -> 'com.android.settings:id/switch_widget' or 'Airplane mode'; WAIT -> '1000' (time in ms); DIM -> '0,ACC'; WAKE -> 'standard'; INTENT -> '{\\\"action\\\":\\\"android.intent.action.VIEW\\\",\\\"data\\\":\\\"https://google.com\\\"}'.")
                                    }) 
                                    put("delay", JSONObject().apply { 
                                        put("type", "INTEGER")
                                        put("description", "Mandatory or custom delay in ms before executing this specific step (e.g., 200).")
                                    }) 
                                })
                                put("required", JSONArray().put("type").put("val").put("delay"))
                            }) 
                        }) 
                    })
                    put("required", JSONArray().put("chain"))
                }) 
            })
            tools.put(JSONObject().put("functionDeclarations", toolDecls))
            
            setupBlock.put("tools", tools)
            setupBlock.put("inputAudioTranscription", JSONObject())
            setupBlock.put("outputAudioTranscription", JSONObject())
            
            setup.put("setup", setupBlock)
            ws?.send(setup.toString())
        } catch(e: Exception) {} 
    } 
    
    private fun handleMessage(text: String) { 
        try { 
            val json = JSONObject(text)
            
            if (json.has("error") || json.has("fatal")) { 
                api.log("GEMINI_BACKEND_ERROR RAW: $text")
            } 
            
            if (json.has("serverContent")) { 
                val sc = json.getJSONObject("serverContent")
                
                if (sc.has("interrupted")) { 
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.play()
                    state.value = "LISTENING"
                } 
                
                if (sc.has("outputTranscription")) { 
                    val txt = sc.getJSONObject("outputTranscription").optString("text")
                    if (txt.isNotBlank()) { 
                        CoroutineScope(Dispatchers.Main).launch { 
                            if (transcripts.isNotEmpty() && transcripts.last().first == "Gemini") { 
                                val lastIndex = transcripts.lastIndex
                                transcripts[lastIndex] = Pair("Gemini", transcripts[lastIndex].second + txt)
                            } else { 
                                transcripts.add(Pair("Gemini", txt))
                            } 
                        } 
                    } 
                } 
                
                if (sc.has("inputTranscription")) { 
                    val txt = sc.getJSONObject("inputTranscription").optString("text")
                    if (txt.isNotBlank()) { 
                        CoroutineScope(Dispatchers.Main).launch { 
                            if (transcripts.isNotEmpty() && transcripts.last().first == "User") { 
                                val lastIndex = transcripts.lastIndex
                                transcripts[lastIndex] = Pair("User", transcripts[lastIndex].second + txt)
                            } else { 
                                transcripts.add(Pair("User", txt))
                            } 
                        } 
                    } 
                } 
                
                if (sc.has("modelTurn")) { 
                    if (state.value != "SPEAKING") { 
                        api.log("[STATE_CHANGE] LISTENING -> SPEAKING")
                        state.value = "SPEAKING"
                    } 
                    val parts = sc.getJSONObject("modelTurn").getJSONArray("parts")
                    for (i in 0 until parts.length()) { 
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) { 
                            val b64 = part.getJSONObject("inlineData").getString("data")
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            audioTrack?.write(bytes, 0, bytes.size)
                        } 
                    } 
                } 

                if (sc.optBoolean("turnComplete", false)) { 
                    api.log("[STATE_CHANGE] Turn Complete. SPEAKING -> LISTENING")
                    state.value = "LISTENING"
                } 
            } 
            
            if (json.has("toolCall")) { 
                state.value = "THINKING"
                val toolCall = json.getJSONObject("toolCall")
                val calls = toolCall.getJSONArray("functionCalls")
                
                scope.launch { 
                    val responses = JSONArray()
                    for (i in 0 until calls.length()) { 
                        val call = calls.getJSONObject(i)
                        val id = call.getString("id")
                        val name = call.getString("name")
                        
                        val result = JSONObject()
                        try { 
                            val args = call.optJSONObject("args") ?: JSONObject()
                            
                            if (name == "execute_interaction_chain") { 
                                withContext(Dispatchers.Main) { onCollapseRequested() }
                                delay(600)
                            } 
                            
                            when (name) { 
                                "get_battery" -> result.put("output", "Battery is at ${api.getBattery()}%")
                                "get_system_info" -> result.put("output", api.getSystemInfo())
                                "take_screenshot" -> { 
                                    val deferred = CompletableDeferred<String?>()
                                    api.getScreenB64 { b64 -> deferred.complete(b64) }
                                    val b64 = deferred.await()
                                    if (b64 != null) { 
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                        if (!downloadDir.exists()) downloadDir.mkdirs()
                                        val file = java.io.File(downloadDir, "diag_annotated_${System.currentTimeMillis()}.jpg")
                                        file.writeBytes(bytes)
                                        result.put("output", "Annotated screenshot saved locally to ${file.absolutePath} (No cloud upload).")
                                    } else { 
                                        result.put("error", "Failed to capture annotated screen.")
                                    } 
                                } 
                                "get_ui_hierarchy" -> { 
                                    val tree = api.getUiTree()
                                    result.put("output", tree)
                                } 
                                "search_installed_apps" -> { 
                                    val query = args.getString("query")
                                    val pm = ctx.packageManager
                                    val packages = pm.getInstalledPackages(0)
                                    val results = JSONArray()
                                    for (pkg in packages) { 
                                        val name = pkg.applicationInfo.loadLabel(pm).toString()
                                        if (name.contains(query, ignoreCase = true)) { 
                                            val obj = JSONObject()
                                            obj.put("app_name", name)
                                            obj.put("package_name", pkg.packageName)
                                            results.put(obj)
                                        } 
                                    } 
                                    result.put("output", results.toString())
                                } 
                                "execute_interaction_chain" -> { 
                                    val chainArray = args.getJSONArray("chain")
                                    api.log("[AI_INTERCEPT] Intercepted raw AI interaction chain:\n${chainArray.toString(2)}")
                                    
                                    var totalDelayMs = 0L
                                    for (j in 0 until chainArray.length()) { 
                                        val step = chainArray.getJSONObject(j)
                                        totalDelayMs += step.optLong("delay", 200L)
                                        if (step.optString("type").uppercase() == "WAIT") { 
                                            totalDelayMs += step.optString("val").toLongOrNull() ?: 500L
                                        } 
                                    } 
                                    totalDelayMs += 2500L
                                    
                                    val cmd = JSONObject()
                                    cmd.put("file_name", "REMOTE_TOUCH")
                                    cmd.put("content", chainArray.toString())
                                    api.executeCommand(cmd.toString())
                                    
                                    api.log("[AI_INTERCEPT] Chain dispatched. Waiting ${totalDelayMs}ms for UI to settle...")
                                    delay(totalDelayMs)
                                    
                                    val deferredScreenshot = CompletableDeferred<String?>()
                                    api.getScreenB64 { b64 -> 
                                        deferredScreenshot.complete(b64)
                                    }
                                    val b64Screenshot = deferredScreenshot.await()
                                    
                                    if (b64Screenshot != null) { 
                                        val videoObj = JSONObject().apply { 
                                            put("mimeType", "image/jpeg")
                                            put("data", b64Screenshot)
                                         }
                                         val realtimeInput = JSONObject().put("video", videoObj)
                                         val outerMessage = JSONObject().put("realtimeInput", realtimeInput)
                                         ws?.send(outerMessage.toString())
                                         api.log("[VISION] Outbound Set-of-Mark JPEG frame successfully routed via modern 'video' stream.")
                                    } 
                                    
                                    val newTree = api.getUiTree()
                                    result.put("output", "Action executed. NEW SCREEN STATE:\n$newTree\n\nEvaluate this state alongside the Set-of-Mark visual frame you just received in your realtimeInput.video. If your overarching goal is not yet complete, immediately issue the next execute_interaction_chain call. Do not ask for confirmation.")
                                } 
                                else -> result.put("error", "Function not implemented natively.")
                            } 
                        } catch(e: Exception) { result.put("error", e.message) }
                        
                        val respObj = JSONObject().apply { 
                            put("id", id)
                            put("name", name)
                            put("response", result)
                        }
                        responses.put(respObj)
                        withContext(Dispatchers.Main) { transcripts.add(Pair("System", "Executed Native Tool: $name")) }
                    } 
                    
                    val tr = JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses))
                    ws?.send(tr.toString())
                } 
            } 
            
        } catch(e: Exception) { } 
    }
    
    fun sendText(text: String) { 
        if (text.isBlank() || ws == null) return
        try { 
            val textTurn = JSONObject().put("clientContent", JSONObject()
                .put("turns", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", true))
            ws?.send(textTurn.toString())
            CoroutineScope(Dispatchers.Main).launch { transcripts.add(Pair("User", text)) }
        } catch(e: Exception) { 
            api.log("Text send error: ${e.message}")
        } 
    }

    @SuppressLint("MissingPermission")
    fun toggleMic() { 
        if (isSlaveMode.value) return
        if (recordJob?.isActive == true) { 
            recordJob?.cancel()
            audioRecord?.stop()
            try { aec?.release(); aec = null } catch(e: Exception){}
            try { ns?.release(); ns = null } catch(e: Exception){}
            state.value = "CONNECTED"
            return
        } 
        
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { 
            api.toast("Microphone permission missing.")
            return
        } 
        
        try { 
            api.log("[MIC_INIT] Requesting AudioRecord...")
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            
            val hardwareSessionId = audioRecord!!.audioSessionId
            api.log("[MIC_INIT] AudioRecord constructed. Hardware SessionID: $hardwareSessionId")

            try { 
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) { 
                    aec = android.media.audiofx.AcousticEchoCanceler.create(hardwareSessionId)
                    aec?.enabled = true
                    api.log("[AEC_HOOK] AcousticEchoCanceler created and enabled: ${aec?.enabled}")
                } else { 
                    api.log("[AEC_WARN] AcousticEchoCanceler NOT AVAILABLE on this hardware.")
                } 
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) { 
                    ns = android.media.audiofx.NoiseSuppressor.create(hardwareSessionId)
                    ns?.enabled = true
                    api.log("[AEC_HOOK] NoiseSuppressor created and enabled: ${ns?.enabled}")
                } 
            } catch (e: Exception) { 
                api.log("[AEC_ERR] Hardware hook failed: ${e.message}")
            } 
            
            initAudioTrack(hardwareSessionId)
            
            audioRecord?.startRecording()
            api.log("[MIC_INIT] Start recording successful.")
            
            state.value = "LISTENING"
            
            recordJob = scope.launch { 
                val buffer = ByteArray(4096)
                var muteThrottleLog = 0L
                while (isActive) { 
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && ws != null) { 
                        if (state.value == "SPEAKING") { 
                            val now = System.currentTimeMillis()
                            if (now - muteThrottleLog > 1000) { 
                                api.log("[ECHO_SHIELD] Gemini is speaking. Discarding mic payload to save network/CPU.")
                                muteThrottleLog = now
                            } 
                            continue
                        } 

                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val audioObj = JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                        val ri = JSONObject().put("audio", audioObj)
                        val out = JSONObject().put("realtimeInput", ri)
                        ws?.send(out.toString())
                    } 
                } 
            } 
        } catch(e: Exception) { 
            api.log("[MIC_FATAL] Error initializing mic stream: ${e.message}\\n${e.stackTraceToString()}")
        } 
    }
    
    fun toggleVideo() { 
        if (videoJob?.isActive == true) { 
            videoJob?.cancel()
            videoJob = null
            isVideoActive.value = false
            api.log("Vision streaming stopped.")
            return
        } 

        if (ws == null && !isSlaveMode.value || state.value == "OFFLINE") { 
            api.toast("Connect to Agent first.")
            return
        } 

        isVideoActive.value = true
        api.log("Vision streaming started.")
        videoJob = scope.launch { 
            while (isActive) { 
                try { 
                    val deferredScreenshot = CompletableDeferred<String?>()
                    api.getScreenB64 { b64 -> 
                        deferredScreenshot.complete(b64)
                    }
                    val b64Screenshot = deferredScreenshot.await()
                    
                    if (b64Screenshot != null) { 
                        if (isSlaveMode.value) { 
                            val data = JSONObject().put("screenshot", b64Screenshot)
                            sendSlaveBroadcast("slave_vision_frame", data)
                        } else if (ws != null) { 
                            val videoObj = JSONObject().apply { 
                                put("mimeType", "image/jpeg")
                                put("data", b64Screenshot)
                             }
                             val realtimeInput = JSONObject().put("video", videoObj)
                             val outerMessage = JSONObject().put("realtimeInput", realtimeInput)
                             ws?.send(outerMessage.toString())
                        } 
                    } 
                } catch(e: Exception) { 
                    api.log("Vision stream error: ${e.message}")
                } 
                delay(2000)
            } 
        } 
    }
    
    fun disconnect() { 
        if (!isSlaveMode.value) state.value = "OFFLINE"
        isVideoActive.value = false
        
        try { 
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = android.media.AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch(e: Exception) {}
        
        try { recordJob?.cancel() } catch(e: Exception) {}
        try { videoJob?.cancel() } catch(e: Exception) {}
        videoJob = null
        
        try { aec?.release(); aec = null } catch(e: Exception){}
        try { ns?.release(); ns = null } catch(e: Exception){}
        try { audioRecord?.stop() } catch(e: Exception) {} 
        try { audioRecord?.release() } catch(e: Exception) {} 
        audioRecord = null
        
        try { audioTrack?.stop() } catch(e: Exception) {} 
        try { audioTrack?.release() } catch(e: Exception) {} 
        audioTrack = null
        
        try { ws?.close(1000, "Client disconnecting") } catch(e: Exception) {} 
        ws = null
    } 
}

@Composable
fun AgentScreen(context: Context, bridge: Any, engine: AgentEngine) {
    val api = remember { CortexNativeAPI(bridge) }
    var isCollapsed by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    
    val inf = rememberInfiniteTransition(label = "orb")
    val scale by inf.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    
    val (orbColor, orbGlow) = when(engine.state.value) { 
        "CONNECTING" -> Pair(Color(0xFF8B5CF6), Color(0xFFC084FC))
        "CONNECTED" -> Pair(Color(0xFF3B82F6), Color(0xFF60A5FA))
        "LISTENING" -> Pair(Color(0xFFEF4444), Color(0xFFF87171))
        "THINKING" -> Pair(Color(0xFFEAB308), Color(0xFFFDE047))
        "SPEAKING" -> Pair(Color(0xFF3B82F6), Color(0xFFEC4899))
        else -> Pair(Color(0xFF27272A), Color(0xFF3F3F46))
    }

    LaunchedEffect(Unit) { 
        try { 
            val intent = android.content.Intent().apply { 
                setClassName(context.packageName, "com.example.myandroid.AgentActivity")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        } catch (e: Exception) { 
            api.log("Failed to launch AgentActivity task manager proxy: \${e.message}")
        } 
    }

    DisposableEffect(Unit) { 
        val receiver = object : android.content.BroadcastReceiver() { 
            override fun onReceive(ctx: Context, intent: android.content.Intent) { 
                when (intent.action) { 
                    "com.cortex.agent.RESUME" -> { 
                        isCollapsed = false
                        api.nav("DISMISS_NOTIFS")
                    } 
                    "com.cortex.agent.DISCONNECT" -> { 
                        isCollapsed = false
                        engine.disconnect()
                        api.close()
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.cancel(9001)
                    } 
                    "com.cortex.agent.TOGGLE_MIC" -> { 
                        engine.toggleMic()
                    } 
                    android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> { 
                        val reason = intent.getStringExtra("reason")
                        if (reason == "homekey" || reason == "recentapps") { 
                            isCollapsed = true
                        } 
                    } 
                } 
            } 
        } 
        val filter = android.content.IntentFilter().apply { 
            addAction("com.cortex.agent.RESUME")
            addAction("com.cortex.agent.DISCONNECT")
            addAction("com.cortex.agent.TOGGLE_MIC")
            addAction(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS) 
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) { 
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else { 
            context.registerReceiver(receiver, filter)
        }
        onDispose { 
            context.unregisterReceiver(receiver)
        } 
    }

    LaunchedEffect(isCollapsed) { 
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        
        var targetView: android.view.View = view
        var params: android.view.WindowManager.LayoutParams? = targetView.layoutParams as? android.view.WindowManager.LayoutParams
        
        while (params == null && targetView.parent is android.view.View) { 
            targetView = targetView.parent as android.view.View
            params = targetView.layoutParams as? android.view.WindowManager.LayoutParams
        }

        if (params != null) { 
            if (isCollapsed) { 
                params.width = 0
                params.height = 0
                params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                targetView.visibility = android.view.View.GONE
            } else { 
                params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                targetView.visibility = android.view.View.VISIBLE
            } 
            try { wm.updateViewLayout(targetView, params) } catch(e: Exception) {}
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (isCollapsed) { 
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { 
                val chan = android.app.NotificationChannel("agent_channel", "Agent Status", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(chan)
            } 
            val resumeIntent = android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent("com.cortex.agent.RESUME"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val stopIntent = android.app.PendingIntent.getBroadcast(context, 2, android.content.Intent("com.cortex.agent.DISCONNECT"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val micIntent = android.app.PendingIntent.getBroadcast(context, 3, android.content.Intent("com.cortex.agent.TOGGLE_MIC"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

            val builder = if (android.os.Build.VERSION.SDK_INT >= 26) { 
                android.app.Notification.Builder(context, "agent_channel")
            } else { 
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Cortex Agent Active")
                .setContentText("Voice and screen streaming in background.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Toggle Mic", micIntent)
                .addAction(android.R.drawable.ic_menu_revert, "Resume UI", resumeIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Session", stopIntent)
                .build()
            nm.notify(9001, notif)
        } else { 
            nm.cancel(9001)
        }
    }
    
    if (isCollapsed) { 
        Box(modifier = Modifier.size(1.dp))
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF09090B))) { 
        
        Row( 
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) { 
            Row(verticalAlignment = Alignment.CenterVertically) { 
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if(engine.state.value != "OFFLINE") Color(0xFF10B981) else Color(0xFF52525B)))
                Spacer(Modifier.width(8.dp))
                Text(engine.state.value, color = Color(0xFFA1A1AA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } 
            
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { 
                if (engine.state.value != "OFFLINE") { 
                    var showRouteMenu by remember { mutableStateOf(false) }
                    Box { 
                        Box( 
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3F3F46))
                                .clickable { showRouteMenu = true },
                            contentAlignment = Alignment.Center
                        ) { 
                            Text(if (engine.audioRoute.value == "PHONE") "📞" else "🔊", fontSize = 16.sp)
                        } 
                        DropdownMenu( 
                            expanded = showRouteMenu,
                            onDismissRequest = { showRouteMenu = false },
                            modifier = Modifier.background(Color(0xFF27272A))
                        ) { 
                            DropdownMenuItem( 
                                text = { Text("📞 Phone (Earpiece)", color = Color.White) },
                                onClick = { 
                                    engine.setAudioRoute("PHONE")
                                    showRouteMenu = false
                                } 
                            )
                            DropdownMenuItem( 
                                text = { Text("🔊 Speaker (Loudspeaker)", color = Color.White) },
                                onClick = { 
                                    engine.setAudioRoute("SPEAKER")
                                    showRouteMenu = false
                                }
                            )
                        } 
                    } 
                    Box( 
                        modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3F3F46))
                                    .clickable { isCollapsed = true },
                                contentAlignment = Alignment.Center
                            ) { 
                                Text("🗕", fontSize = 16.sp, color = Color.White)
                            } 
                            Box( 
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (engine.isVideoActive.value) Color(0xFF10B981) else Color(0xFF3F3F46))
                                    .clickable { engine.toggleVideo() },
                                contentAlignment = Alignment.Center
                            ) { 
                                Text("👁️", fontSize = 16.sp)
                            } 
                        } 
                        
                        Button( 
                            onClick = { if (engine.state.value == "OFFLINE") engine.connect() else engine.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = if (engine.state.value == "OFFLINE") Color(0xFF2563EB) else Color(0xFFDC2626)),
                            shape = RoundedCornerShape(20.dp)
                        ) { 
                            Text(if (engine.state.value == "OFFLINE") "SYNC" else "DISCONNECT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } 
                    } 
                } 

                Box(modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp), contentAlignment = Alignment.Center) { 
                    Box(modifier = Modifier.size(240.dp).scale(if (engine.state.value != "OFFLINE") scale else 1f).blur(60.dp).background(orbGlow.copy(alpha = 0.3f), CircleShape)) 
                    Box(modifier = Modifier.size(180.dp).scale(if (engine.state.value != "OFFLINE") scale else 1f).background(Brush.radialGradient(listOf(orbColor, Color(0xFF09090B))), CircleShape).border(1.dp, orbColor.copy(alpha=0.5f), CircleShape), contentAlignment = Alignment.Center) { 
                        Text(if (engine.state.value == "OFFLINE") "DORMANT" else "CORTEX", color = Color.White.copy(alpha=0.8f), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp) 
                    } 
                } 
                
                Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f).background(Color(0xFF18181B).copy(alpha=0.6f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 20.dp, vertical = 20.dp)) { 
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), reverseLayout = true) { 
                        items(engine.transcripts.reversed()) { (role, txt) -> 
                            val isUser = role == "User"
                            val isSys = role == "System"
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if(isUser) Arrangement.End else if(isSys) Arrangement.Center else Arrangement.Start) { 
                                Box(modifier = Modifier
                                    .background(if(isUser) Color(0xFF2563EB) else if(isSys) Color(0xFF3F3F46) else Color(0xFF27272A), RoundedCornerShape(16.dp))
                                    .clickable { 
                                        try { 
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Cortex Agent", txt)
                                            clipboard.setPrimaryClip(clip)
                                            api.toast("Copied to clipboard")
                                        } catch (e: Exception) { 
                                            api.log("Copy failed: \${e.message}")
                                        } 
                                    } 
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) { 
                                    androidx.compose.foundation.text.selection.SelectionContainer { 
                                        Text(txt, color = Color.White, fontSize = 13.sp) 
                                    } 
                                } 
                            } 
                        } 
                    } 
                } 
                
                var inputText by remember { mutableStateOf("") } 
                Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 20.dp, end = 20.dp).fillMaxWidth().height(60.dp).background(Color(0xFF27272A), RoundedCornerShape(30.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { 
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3F3F46)).clickable { api.close() }, contentAlignment = Alignment.Center) { 
                        Text("X", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) 
                    } 
                    Spacer(modifier = Modifier.width(12.dp)) 
                    androidx.compose.foundation.text.BasicTextField( 
                        value = inputText, 
                        onValueChange = { inputText = it }, 
                        modifier = Modifier.weight(1f), 
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), 
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send), 
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { 
                            engine.sendText(inputText) 
                            inputText = "" 
                        }), 
                        decorationBox = { innerTextField -> 
                            if (inputText.isEmpty()) Text("Type a message... V3", color = Color(0xFFA1A1AA), fontSize = 15.sp) 
                            innerTextField() 
                        } 
                    ) 
                    Spacer(modifier = Modifier.width(12.dp)) 
                    if (inputText.isNotBlank()) { 
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2563EB)).clickable { 
                            engine.sendText(inputText) 
                            inputText = "" 
                        }, contentAlignment = Alignment.Center) { 
                            Text("➤", color = Color.White, fontSize = 18.sp) 
                        } 
                    } else { 
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(if (engine.state.value == "LISTENING") Color(0xFFEF4444) else Color(0xFF3F3F46)).clickable { engine.toggleMic() }, contentAlignment = Alignment.Center) { 
                            Text("🎙️", fontSize = 18.sp) 
                        } 
                    } 
                } 
            } 
        }
