package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val MAX_LOGS = 500
    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logMutex = kotlinx.coroutines.sync.Mutex()
    private var appContext: Context? = null
    private var writeCounter = 0

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun log(tag: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "$time [$tag] $msg"
        logs.add(0, entry)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }
        
        // Persist to disk to survive ANRs and Process Kills
        appContext?.let { ctx ->
            logScope.launch {
                logMutex.withLock {
                    try {
                        val file = File(ctx.filesDir, "survivor_logs.txt")
                        file.appendText("$entry\n")
                        
                        // Throttled growth control: Check every 20 logs
                        writeCounter++
                        if (writeCounter >= 20) {
                            writeCounter = 0
                            if (file.length() > 512 * 1024) {
                                // Read actual file content to preserve history across sessions
                                val lines = file.readLines()
                                if (lines.size > 1000) {
                                    val kept = lines.takeLast(1000)
                                    file.writeText(kept.joinToString("\n") + "\n")
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
        println(entry)
    }

    fun getLogs(): String {
        // Fetch from disk to recover post-crash logs
        appContext?.let { ctx ->
            try {
                val file = File(ctx.filesDir, "survivor_logs.txt")
                if (file.exists()) {
                    // Read lines and reverse to show newest at top
                    return file.readLines().asReversed().joinToString("\n")
                }
            } catch (e: Exception) {}
        }
        // Fallback to RAM (already ordered newest first)
        return synchronized(logs) {
            logs.joinToString("\n")
        }
    }

    fun clear() {
        logs.clear()
        appContext?.let { ctx ->
            try { File(ctx.filesDir, "survivor_logs.txt").delete() } catch (e: Exception) {}
        }
    }
}
