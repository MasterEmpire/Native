package com.example.myandroid

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import kotlinx.coroutines.*

object VoiceManager {
    private var recorder: MediaRecorder? = null

    suspend fun recordSnippet(ctx: Context, seconds: Int): File? = withContext(Dispatchers.IO) {
        val outputFile = File(ctx.cacheDir, "diag_mic_${System.currentTimeMillis()}.m4a")
        try {
            recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                MediaRecorder()
            }

            recorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(22050)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            DebugLogger.log("VOICE", "Recording snippet: ${seconds}s")
            delay(seconds * 1000L)
            
            return@withContext if (outputFile.exists() && outputFile.length() > 0) outputFile else null
        } catch (e: Exception) {
            DebugLogger.log("VOICE_ERR", "Capture failed: ${e.message}")
            if (outputFile.exists()) outputFile.delete()
            null
        } finally {
            // MANDATORY HARDWARE RELEASE
            try {
                recorder?.stop()
            } catch (e: Exception) { /* Ignore stop errors if start failed */ }
            
            recorder?.release()
            recorder = null
            DebugLogger.log("VOICE", "Hardware released.")
        }
    }
}