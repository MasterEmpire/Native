package com.example.myandroid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

class AgentActivity : Activity() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.cortex.agent.DISCONNECT") {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val filter = IntentFilter().apply {
            addAction("com.cortex.agent.DISCONNECT")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        
        // Instantly shift to background so the overlay is dominant
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        // Trigger overlay restoration
        sendBroadcast(Intent("com.cortex.agent.RESUME"))
        // Instantly slip back to background task stack
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }
}
