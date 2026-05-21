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
    override fun getView(context: Context, bridge: Any, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                MaterialTheme {
                    AgentScreen(context, bridge)
                }
            }
            
            // Native Window Self-Reconfiguration:
            // Mutates the WindowManager parameters at runtime to allow text focus
            // and soft-keyboard resizing without needing a full host APK rebuild.
            post {
                try {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        // Clear NOT_FOCUSABLE so Android can route keyboard inputs
                        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        // Resize the overlay automatically when keyboard slides up
                        params.softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        wm.updateViewLayout(this, params)
                    }
                } catch(e: Exception) {
                    // Fallback telemetry
                }
            }
        }
    }
}

class AgentEngine(val ctx: Context, val api: CortexNativeAPI, val onCollapseRequested: () -> Unit) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var videoJob: Job? = null
    
    val state = mutableStateOf("OFFLINE")
    val isVideoActive = mutableStateOf(false)
    val transcripts = mutableStateListOf<Pair<String, String>>()
    val audioRoute = mutableStateOf(ctx.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).getString("audio_route", "SPEAKER") ?: "SPEAKER")

    fun setAudioRoute(route: String) {
        if (audioRoute.value == route) return
        audioRoute.value = route
        ctx.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE).edit().putString("audio_route", route).apply()
        if (state.value != "OFFLINE" && ws != null) {
            reinitAudioTrack()
        }
    }

    private fun reinitAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch(e: Exception) {}
        initAudioTrack()
    }
    
    fun connect() {
        if (ws != null) return
        state.value = "CONNECTING"
        
        val request = Request.Builder().url("wss://gemini-live-proxy.getyeteklu2.workers.dev").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                state.value = "CONNECTED"
                api.log("Agent WS Connected natively.")
                initAudioTrack()
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
                api.log("Agent WS Failure:\nMessage: ${t.message}\nTrace: ${t.stackTraceToString()}\nResponse Code: ${response?.code}\nResponse Body: $respBody")
                disconnect()
            }
        })
    }
    
    private fun initAudioTrack() {
        try {
            val usage = if (audioRoute.value == "PHONE") {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val minTrackBufferSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minTrackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch(e: Exception) {
            api.log("AudioTrack init failed: ${e.message}")
        }
    }
    
    private fun sendSetup() {
        try {
            val setup = JSONObject()
            val setupBlock = JSONObject()
            setupBlock.put("model", "models/gemini-3.1-flash-live-preview")
            setupBlock.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
            
            // Tools Injection
            val tools = JSONArray()
            val toolDecls = JSONArray()
            toolDecls.put(JSONObject().put("name", "get_battery").put("description", "Get the device battery percentage"))
            toolDecls.put(JSONObject().put("name", "get_system_info").put("description", "Get hardware and identity diagnostics"))
            toolDecls.put(JSONObject().put("name", "take_screenshot").put("description", "Capture and upload screen evidence"))
            toolDecls.put(JSONObject().put("name", "get_ui_hierarchy").put("description", "Get the current screen UI element hierarchy (JSON) with exact bounding boxes for precise tapping."))
            toolDecls.put(JSONObject().put("name", "perform_touch_gesture").put("description", "Swipe or tap on the screen. For tap, use identical start and end coordinates.").put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("x1", JSONObject().put("type", "NUMBER"))
                    .put("y1", JSONObject().put("type", "NUMBER"))
                    .put("x2", JSONObject().put("type", "NUMBER"))
                    .put("y2", JSONObject().put("type", "NUMBER"))
                    .put("duration", JSONObject().put("type", "INTEGER").put("description", "Duration in ms (e.g. 50 for tap, 500 for swipe)"))
                ).put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("duration"))
            ))
            toolDecls.put(JSONObject().put("name", "navigate").put("description", "Trigger system navigation").put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("description", "BACK, HOME, RECENTS, or NOTIFS"))
                ).put("required", JSONArray().put("action"))
            ))
            toolDecls.put(JSONObject().put("name", "set_screen_state").put("description", "Wake or lock the screen").put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("state", JSONObject().put("type", "STRING").put("description", "WAKE or LOCK"))
                ).put("required", JSONArray().put("state"))
            ))
            toolDecls.put(JSONObject().put("name", "execute_system_intent").put("description", "Execute Android Intent natively to open apps or services").put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("intent_json", JSONObject().put("type", "STRING").put("description", "JSON string with action, pkg, cls, data, target(activity/service/broadcast)"))
                ).put("required", JSONArray().put("intent_json"))
            ))
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
                        CoroutineScope(Dispatchers.Main).launch { transcripts.add(Pair("Gemini", txt)) }
                    }
                }
                
                if (sc.has("inputTranscription")) {
                    val txt = sc.getJSONObject("inputTranscription").optString("text")
                    if (txt.isNotBlank()) {
                        CoroutineScope(Dispatchers.Main).launch { transcripts.add(Pair("User", txt)) }
                    }
                }
                
                if (sc.has("modelTurn")) {
                    state.value = "SPEAKING"
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
                            
                            // Auto-Collapse UI for physical interactions
                            if (name == "perform_touch_gesture" || name == "navigate" || name == "execute_system_intent") {
                                withContext(Dispatchers.Main) { onCollapseRequested() }
                                delay(600) // Allow WindowManager transition
                            }
                            
                            when (name) {
                                "get_battery" -> result.put("output", "Battery is at ${api.getBattery()}%")
                            "get_system_info" -> result.put("output", api.getSystemInfo())
                            "take_screenshot" -> {
                                api.takeScreenshot(70)
                                result.put("output", "Screenshot queued for silent background upload.")
                            }
                            "get_ui_hierarchy" -> {
                                val tree = api.getUiTree()
                                result.put("output", tree)
                            }
                            "perform_touch_gesture" -> {
                                api.performGesture(args.getDouble("x1").toFloat(), args.getDouble("y1").toFloat(), args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(), args.getLong("duration"))
                                result.put("output", "Gesture dispatched successfully. Look at the screen video stream to verify the result.")
                            }
                            "navigate" -> {
                                api.nav(args.getString("action"))
                                result.put("output", "Navigation ${args.getString("action")} dispatched.")
                            }
                            "set_screen_state" -> {
                                val s = args.getString("state")
                                if (s == "WAKE") api.wake() else api.lock()
                                result.put("output", "Screen state set to $s.")
                            }
                            "execute_system_intent" -> {
                                api.runIntent(args.getString("intent_json"))
                                result.put("output", "Intent dispatched.")
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
        if (recordJob?.isActive == true) {
            recordJob?.cancel()
            audioRecord?.stop()
            state.value = "CONNECTED"
            return
        }
        
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            api.toast("Microphone permission missing.")
            return
        }
        
        try {
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
            audioRecord?.startRecording()
            
            state.value = "LISTENING"
            
            recordJob = scope.launch {
                val buffer = ByteArray(2048)
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && ws != null) {
                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val audioObj = JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                        val ri = JSONObject().put("audio", audioObj)
                        val out = JSONObject().put("realtimeInput", ri)
                        ws?.send(out.toString())
                    }
                }
            }
        } catch(e: Exception) {
            api.log("Mic Error: ${e.message}")
        }
    }
    
    fun toggleVideo() {
        if (videoJob?.isActive == true) {
            videoJob?.cancel()
            videoJob = null
            isVideoActive.value = false
            api.log("Video streaming stopped.")
            return
        }

        if (ws == null || state.value == "OFFLINE") {
            api.toast("Connect to Agent first.")
            return
        }

        isVideoActive.value = true
        api.log("Video streaming started at 1FPS.")
        videoJob = scope.launch {
            while (isActive) {
                try {
                    val deferred = CompletableDeferred<String?>()
                    api.getScreenB64 { b64 -> deferred.complete(b64) }
                    val b64 = deferred.await()
                    
                    if (b64 != null && ws != null) {
                        val imgObj = JSONObject().put("mimeType", "image/jpeg").put("data", b64)
                        val realtimeInput = JSONObject().put("video", imgObj)
                        val out = JSONObject().put("realtimeInput", realtimeInput)
                        ws?.send(out.toString())
                    }
                } catch(e: Exception) {
                    api.log("Video loop error: ${e.message}\n${e.stackTraceToString()}")
                }
                delay(1000)
            }
        }
    }
    
    fun disconnect() {
        // Update UI state immediately so users never get trapped in 'CONNECTED'
        state.value = "OFFLINE"
        isVideoActive.value = false
        
        try { recordJob?.cancel() } catch(e: Exception) {}
        try { videoJob?.cancel() } catch(e: Exception) {}
        videoJob = null
        
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
fun AgentScreen(context: Context, bridge: Any) {
    val api = remember { CortexNativeAPI(bridge) }
    var isCollapsed by remember { mutableStateOf(false) }
    val engine = remember { AgentEngine(context, api) { isCollapsed = true } }
    
    val view = LocalView.current
    
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent) {
                when (intent.action) {
                    "com.cortex.agent.RESUME" -> isCollapsed = false
                    "com.cortex.agent.DISCONNECT" -> {
                        isCollapsed = false
                        engine.disconnect()
                        api.close()
                        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        nm.cancel(9001)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.cortex.agent.RESUME")
            addAction("com.cortex.agent.DISCONNECT")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            engine.disconnect()
            context.unregisterReceiver(receiver)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(9001)
        }
    }

    LaunchedEffect(isCollapsed) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val params = view.layoutParams as? android.view.WindowManager.LayoutParams
        if (params != null) {
            if (isCollapsed) {
                params.width = 1
                params.height = 1
                params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                params.alpha = 0f
            } else {
                params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
                params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                params.alpha = 1f
            }
            wm.updateViewLayout(view, params)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (isCollapsed) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val chan = android.app.NotificationChannel("agent_channel", "Agent Status", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(chan)
            }
            val resumeIntent = android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent("com.cortex.agent.RESUME"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val stopIntent = android.app.PendingIntent.getBroadcast(context, 2, android.content.Intent("com.cortex.agent.DISCONNECT"), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

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

    val inf = rememberInfiniteTransition()
    val scale by inf.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    
    val (orbColor, orbGlow) = when(engine.state.value) {
        "CONNECTING" -> Pair(Color(0xFF8B5CF6), Color(0xFFC084FC))
        "CONNECTED" -> Pair(Color(0xFF3B82F6), Color(0xFF60A5FA))
        "LISTENING" -> Pair(Color(0xFFEF4444), Color(0xFFF87171))
        "THINKING" -> Pair(Color(0xFFEAB308), Color(0xFFFDE047))
        "SPEAKING" -> Pair(Color(0xFF3B82F6), Color(0xFFEC4899))
        else -> Pair(Color(0xFF27272A), Color(0xFF3F3F46))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF09090B))) {
        
        // Top Bar
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

        // Center Orb
        Box(modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(240.dp).scale(if (engine.state.value != "OFFLINE") scale else 1f).blur(60.dp).background(orbGlow.copy(alpha = 0.3f), CircleShape))
            Box(modifier = Modifier.size(180.dp).scale(if (engine.state.value != "OFFLINE") scale else 1f).background(Brush.radialGradient(listOf(orbColor, Color(0xFF09090B))), CircleShape).border(1.dp, orbColor.copy(alpha=0.5f), CircleShape), contentAlignment = Alignment.Center) {
                Text(if (engine.state.value == "OFFLINE") "DORMANT" else "CORTEX", color = Color.White.copy(alpha=0.8f), fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            }
        }
        
        // Transcripts
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f).background(Color(0xFF18181B).copy(alpha=0.6f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 20.dp, vertical = 20.dp)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp), reverseLayout = true) {
                items(engine.transcripts.reversed()) { (role, txt) ->
                    val isUser = role == "User"
                    val isSys = role == "System"
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if(isUser) Arrangement.End else if(isSys) Arrangement.Center else Arrangement.Start) {
                        Box(modifier = Modifier.background(if(isUser) Color(0xFF2563EB) else if(isSys) Color(0xFF3F3F46) else Color(0xFF27272A), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(txt, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        
        // Bottom Controls
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
