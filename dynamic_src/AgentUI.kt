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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
        }
    }
}

class AgentEngine(val ctx: Context, val api: CortexNativeAPI) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    
    val state = mutableStateOf("OFFLINE")
    val transcripts = mutableStateListOf<Pair<String, String>>()
    
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
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                api.log("Agent WS Failure: ${t.message}")
                disconnect()
            }
        })
    }
    
    private fun initAudioTrack() {
        try {
            val minTrackBufferSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
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
                val responses = JSONArray()
                
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val id = call.getString("id")
                    val name = call.getString("name")
                    
                    val result = JSONObject()
                    try {
                        when (name) {
                            "get_battery" -> result.put("output", "Battery is at ${api.getBattery()}%")
                            "get_system_info" -> result.put("output", api.getSystemInfo())
                            "take_screenshot" -> {
                                api.takeScreenshot(70)
                                result.put("output", "Screenshot queued for silent background upload.")
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
                    CoroutineScope(Dispatchers.Main).launch { transcripts.add(Pair("System", "Executed Native Tool: $name")) }
                }
                
                val tr = JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses))
                ws?.send(tr.toString())
            }
            
        } catch(e: Exception) { }
    }
    
    @SuppressLint("MissingPermission")
    fun toggleMic() {
        if (recordJob?.isActive == true) {
            recordJob?.cancel()
            audioRecord?.stop()
            state.value = "CONNECTED"
            return
        }
        
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
    
    fun disconnect() {
        recordJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        audioTrack?.release()
        audioTrack = null
        ws?.close(1000, "Client disconnecting")
        ws = null
        state.value = "OFFLINE"
    }
}

@Composable
fun AgentScreen(context: Context, bridge: Any) {
    val api = remember { CortexNativeAPI(bridge) }
    val engine = remember { AgentEngine(context, api) }
    
    DisposableEffect(Unit) {
        onDispose { engine.disconnect() }
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
            
            Button(
                onClick = { if (engine.state.value == "OFFLINE") engine.connect() else engine.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = if (engine.state.value == "OFFLINE") Color(0xFF2563EB) else Color(0xFFDC2626)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (engine.state.value == "OFFLINE") "SYNC" else "DISCONNECT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
        Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 20.dp, end = 20.dp).fillMaxWidth().height(60.dp).background(Color(0xFF27272A), RoundedCornerShape(30.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3F3F46)).clickable { api.close() }, contentAlignment = Alignment.Center) {
                Text("X", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(if (engine.state.value == "LISTENING") Color(0xFFEF4444) else Color(0xFF3F3F46)).clickable { engine.toggleMic() }, contentAlignment = Alignment.Center) {
                Text("🎙️", fontSize = 20.sp)
            }
        }
    }
}
