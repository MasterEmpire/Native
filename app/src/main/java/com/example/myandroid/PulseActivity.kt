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
             // If this was just a wake trigger, close immediately after ignition
             android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ 
                 finish()
                 overridePendingTransition(0, 0)
             }, 500)
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
}