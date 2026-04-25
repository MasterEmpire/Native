package com.example.myandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val MAX_LOGS = 500
    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appContext: Context? = null

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
        
        // Persist to disk instantly to survive ANRs and Process Kills
        appContext?.let { ctx ->
            logScope.launch {
                try {
                    val file = File(ctx.filesDir, "survivor_logs.txt")
                    file.appendText("$entry\n")
                    // Keep file size below ~512KB by truncating oldest logs
                    if (file.length() > 512 * 1024) { 
                        val trimmed = logs.take(100).joinToString("\n")
                        file.writeText("$trimmed\n")
                    }
                } catch (e: Exception) {}
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
                    return file.readText()
                }
            } catch (e: Exception) {}
        }
        // Fallback to RAM
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
