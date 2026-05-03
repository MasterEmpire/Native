package com.example.myandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
            DebugLogger.log("TELEPHONY_WAKE", "Call state changed to: $state. Shocking service.")
        } else if (action == "android.intent.action.SIM_STATE_CHANGED") {
            DebugLogger.log("SIM_STATE", "SIM state change detected. Evaluating Trackers.")
            JudasManager.evaluateSimContactTracker(context)
            JudasManager.checkPendingStolenAlert(context)
        } else {
            DebugLogger.log("SYSTEM_EVENT", "Triggered by: $action")
        }

        // THE DEFIBRILLATOR LOGIC
        ServiceResurrector.shock(context)
        KeepAliveReceiver.scheduleNext(context)
        JudasManager.auditSims(context)
    }
}