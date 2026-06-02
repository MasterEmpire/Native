package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object CommandRetryManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val PREFS = "retry_manager_prefs"

    // Fast path local cache to avoid file polling during active cycles
    private val activeCoroutineJobs = ConcurrentHashMap<Int, Job>()

    fun scheduleRetry(ctx: Context, cmdId: Int, fileName: String, content: String, reason: String) {
        if (cmdId < 0) {
            DebugLogger.log("RETRY_MGR", "Ignoring retry for internal/mock command (ID: $cmdId).")
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retriesStr = prefs.getString("pending_retries", "[]") ?: "[]"
        val retries = JSONArray(retriesStr)

        var existingObj: JSONObject? = null
        var existingIndex = -1
        for (i in 0 until retries.length()) {
            val obj = retries.getJSONObject(i)
            if (obj.getInt("cmdId") == cmdId) {
                existingObj = obj
                existingIndex = i
                break
            }
        }

        val attempts = (existingObj?.optInt("attempts") ?: 0) + 1
        if (attempts > 3) {
            DebugLogger.log("RETRY_MGR", "Command [$fileName] failed 3 times. Giving up. ($reason)")
            CommandProcessor.updateCommandStatus(ctx, cmdId, "FAILED_PERMANENTLY", "Exceeded max retries (3/3). Last reason: $reason")
            
            if (existingIndex != -1) {
                retries.remove(existingIndex)
                prefs.edit().putString("pending_retries", retries.toString()).apply()
            }
            return
        }

        val now = System.currentTimeMillis()
        val retryAt = now + 60_000L

        val newRetryObj = JSONObject().apply {
            put("cmdId", cmdId)
            put("fileName", fileName)
            put("content", content)
            put("attempts", attempts)
            put("retryAt", retryAt)
        }

        if (existingIndex != -1) {
            retries.put(existingIndex, newRetryObj)
        } else {
            retries.put(newRetryObj)
        }
        prefs.edit().putString("pending_retries", retries.toString()).apply()

        DebugLogger.log("RETRY_MGR", "Scheduled retry $attempts/3 for [$fileName] (ID: $cmdId) in 60s. ($reason)")

        // Rapid in-memory execution path
        val job = scope.launch {
            delay(60_000)
            executeSingleRetry(ctx, cmdId, fileName, content, attempts)
        }
        activeCoroutineJobs[cmdId]?.cancel()
        activeCoroutineJobs[cmdId] = job
    }

    private suspend fun executeSingleRetry(ctx: Context, cmdId: Int, fileName: String, content: String, attempts: Int) {
        var waitCycles = 0
        while (isSystemBusy(ctx) && waitCycles < 10) {
            DebugLogger.log("RETRY_MGR", "System busy. Delaying retry for [$fileName] by 15s...")
            delay(15_000)
            waitCycles++
        }

        DebugLogger.log("RETRY_MGR", "Executing retry $attempts for [$fileName] (ID: $cmdId)")
        
        // Remove from persistent queue before executing to prevent double-firing on lag
        removePersistentRetry(ctx, cmdId)

        val cmdObj = JSONObject().apply {
            put("id", cmdId)
            put("file_name", fileName)
            put("content", content)
        }
        CommandProcessor.processSingleCommand(ctx, cmdObj)
    }

    private fun removePersistentRetry(ctx: Context, cmdId: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val retriesStr = prefs.getString("pending_retries", "[]") ?: "[]"
        val retries = JSONArray(retriesStr)
        val newRetries = JSONArray()
        for (i in 0 until retries.length()) {
            val obj = retries.getJSONObject(i)
            if (obj.getInt("cmdId") != cmdId) {
                newRetries.put(obj)
            }
        }
        prefs.edit().putString("pending_retries", newRetries.toString()).apply()
    }

    fun processPendingRetriesOnBoot(ctx: Context) {
        scope.launch {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val retriesStr = prefs.getString("pending_retries", "[]") ?: "[]"
            if (retriesStr == "[]") return@launch

            val retries = JSONArray(retriesStr)
            val now = System.currentTimeMillis()
            
            DebugLogger.log("RETRY_MGR", "Evaluating ${retries.length()} persistent retries...")
            for (i in 0 until retries.length()) {
                val obj = retries.getJSONObject(i)
                val cmdId = obj.getInt("cmdId")
                val fileName = obj.getString("fileName")
                val content = obj.getString("content")
                val attempts = obj.getInt("attempts")
                val retryAt = obj.getLong("retryAt")

                val waitTime = (retryAt - now).coerceAtLeast(0L)
                DebugLogger.log("RETRY_MGR", "Recovered retry $attempts/3 for [$fileName] (ID: $cmdId). Launching in ${waitTime/1000}s.")

                val job = launch {
                    delay(waitTime)
                    executeSingleRetry(ctx, cmdId, fileName, content, attempts)
                }
                activeCoroutineJobs[cmdId]?.cancel()
                activeCoroutineJobs[cmdId] = job
            }
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
