package com.example.myandroid

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DebugLogger {
    @Volatile var isLoggingEnabled = true
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val MAX_LOGS = 500
    private var appContext: Context? = null
    private var writeCounter = 0

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        if (!isLoggingEnabled) return
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "$time [$tag] $msg"
        logs.add(0, entry)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }
        
        // SYNC WRITE: Guarantees logs survive if process is frozen/killed immediately after
        appContext?.let { ctx ->
            try {
                val file = File(ctx.filesDir, "survivor_logs.txt")
                file.appendText("$entry\n")
                
                writeCounter++
                if (writeCounter >= 20) {
                    writeCounter = 0
                    if (file.length() > 512 * 1024) {
                        val lines = file.readLines()
                        if (lines.size > 1000) {
                            val kept = lines.takeLast(1000)
                            file.writeText(kept.joinToString("\n") + "\n")
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        println(entry)
    }

    fun getLogs(): String {
        appContext?.let { ctx ->
            try {
                val file = File(ctx.filesDir, "survivor_logs.txt")
                if (file.exists()) {
                    return file.readLines().asReversed().joinToString("\n")
                }
            } catch (e: Exception) {}
        }
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
