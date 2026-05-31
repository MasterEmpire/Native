package com.example.myandroid

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

class DynamicTaskActivity : Activity() {
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val title = intent.getStringExtra("task_title") ?: "Cortex App"
        sessionId = intent.getStringExtra("session_id")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setTaskDescription(ActivityManager.TaskDescription(title))
        }
        
        // Instantly push to background to allow the WindowManager overlay to dominate the screen
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        // When user taps this app in Recents, restore the overlay visibility
        sessionId?.let {
            sendBroadcast(Intent("com.cortex.task.RESUME").putExtra("session_id", it))
        }
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        // When user swipes this app away in Recents, kill the overlay
        sessionId?.let {
            sendBroadcast(Intent("com.cortex.task.CLOSE").putExtra("session_id", it))
        }
    }
}
