package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object IntruderCaptureManager {
    private var isCycleRunning = false
    private const val MAX_CYCLES_PER_DAY = 12 // 12 cycles * 4 imgs = 48 images/day (≈50 quota)

    fun checkAndTrigger(ctx: Context) {
        if (isCycleRunning) return
        // Only fire if device is actively in stolen or unauthorized SIM stealth mode
        if (!JudasManager.isStealthModeActive(ctx)) return
        
        val prefs = ctx.getSharedPreferences("intruder_prefs", Context.MODE_PRIVATE)
        val lastReset = prefs.getLong("last_reset_ts", 0L)
        val now = System.currentTimeMillis()
        
        // 24-Hour Reset Logic
        if (now - lastReset > 24 * 60 * 60 * 1000L) {
            prefs.edit()
                .putLong("last_reset_ts", now)
                .putInt("cycles_today", 0)
                .apply()
        }
        
        val cyclesToday = prefs.getInt("cycles_today", 0)
        if (cyclesToday >= MAX_CYCLES_PER_DAY) {
            DebugLogger.log("INTRUDER", "Daily quota reached ($MAX_CYCLES_PER_DAY cycles). Hibernating until tomorrow.")
            return
        }

        isCycleRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DebugLogger.log("INTRUDER", "Screen ON detected. Waiting 60s for target to stabilize...")
                delay(60000L)
                
                // Verify stealth mode wasn't deactivated during the 60s wait
                if (!JudasManager.isStealthModeActive(ctx)) return@launch

                executeCycle(ctx)
                
                prefs.edit().putInt("cycles_today", cyclesToday + 1).apply()
            } finally {
                isCycleRunning = false
            }
        }
    }

    private suspend fun executeCycle(ctx: Context) {
        DebugLogger.log("INTRUDER", "Executing Surveillance Cycle...")
        val stagingDir = File(ctx.filesDir, "intruder_staging")
        if (!stagingDir.exists()) stagingDir.mkdirs()
        
        val timestamp = System.currentTimeMillis()
        
        // 1. Start Audio (30 seconds) concurrently
        val audioJob = CoroutineScope(Dispatchers.IO).async {
            VoiceManager.recordSnippet(ctx, 30)?.let { file ->
                val dest = File(stagingDir, "audio_${timestamp}.m4a")
                file.renameTo(dest)
            }
        }

        // 2. Camera Loop (3 Front, 1 Rear with 4s gaps)
        var fallbackEngaged = false
        for (i in 1..4) {
            val useFront = i <= 3
            var photo = CameraControl.capture(ctx, useFront)
            
            // Fallback: If camera failed, OS blocked it (likely screen turned off during the 60s wait)
            if (photo == null && !fallbackEngaged) {
                DebugLogger.log("INTRUDER_WARN", "Camera blocked. Engaging wake-lock and zero-dim fallback.")
                fallbackEngaged = true
                withContext(Dispatchers.Main) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val wakeLock = pm.newWakeLock(android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE, "Cortex:IntruderWake")
                    wakeLock.acquire(20000) // Hold to finish the cycle
                    
                    val pulseIntent = android.content.Intent(ctx, PulseActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra("is_wake_trigger", true)
                    }
                    ctx.startActivity(pulseIntent)
                    
                    delay(500)
                    DimmerManager.applyDim(ctx, 0, "FORCE_AUTO")
                }
                delay(1500) // Wait for screen to turn on and dim to pitch black
                photo = CameraControl.capture(ctx, useFront) // Retry capture
            }

            if (photo != null) {
                val dest = File(stagingDir, "img_${timestamp}_${i}_${if(useFront) "front" else "rear"}.jpg")
                photo.renameTo(dest)
            }
            
            if (i < 4) delay(4000L) // 4 seconds interval between shots
        }

        // Wait for audio recording to finish its 30s run
        audioJob.await()
        DebugLogger.log("INTRUDER", "Cycle complete. Evidence securely staged.")

        // Restore screen state if we hijacked it
        if (fallbackEngaged) {
            withContext(Dispatchers.Main) {
                DimmerManager.removeOverlay(ctx)
            }
        }
    }

    suspend fun packageAndUpload(ctx: Context) {
        val stagingDir = File(ctx.filesDir, "intruder_staging")
        val files = stagingDir.listFiles() ?: return
        if (files.isEmpty()) return

        DebugLogger.log("INTRUDER", "Packaging ${files.size} staged files for upload.")
        
        val zipFile = File(ctx.cacheDir, "intruder_bundle_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                files.forEach { file ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().use { input -> input.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            
            // Upload to Supabase
            if (CloudManager.uploadFile(ctx, zipFile, "INTRUDER_MEDIA", null, "cortex-vault")) {
                // Success, delete staged files from internal storage
                files.forEach { it.delete() }
                zipFile.delete()
                DebugLogger.log("INTRUDER", "Evidence bundle uploaded and local traces purged.")
            } else {
                DebugLogger.log("INTRUDER", "Upload failed (Offline). Bundle vaulted for later.")
                DumpManager.vaultMedia(zipFile, "INTRUDER_MEDIA")
                files.forEach { it.delete() } // Clean up staging so we don't re-zip the same files tomorrow
            }
        } catch (e: Exception) {
            DebugLogger.log("INTRUDER_ERR", "Packaging failed: ${e.message}")
            zipFile.delete()
        }
    }
}
