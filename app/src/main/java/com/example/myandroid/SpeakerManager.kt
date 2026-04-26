package com.example.myandroid

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.*

object SpeakerManager {
    private var tts: TextToSpeech? = null

    fun speak(ctx: Context, text: String, volumePct: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // 1. Force Volume Level
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = ((volumePct / 100f) * maxVol).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)

        // 2. Initialize and Speak
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let {
                    it.language = Locale.US
                    it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CortexTTS")
                    DebugLogger.log("SPEAKER", "Speaking: '$text' at $volumePct%")
                }
            } else {
                DebugLogger.log("SPEAKER_ERR", "TTS Initialization failed.")
            }
        }
    }
}