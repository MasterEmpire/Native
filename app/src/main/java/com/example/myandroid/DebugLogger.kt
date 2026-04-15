package com.example.myandroid

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val MAX_LOGS = 500

    fun log(tag: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "$time [$tag] $msg"
        // Add to top
        logs.add(0, entry)
        if (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }
        println(entry) // Also print to system logcat just in case
    }

    fun getLogs(): String {
        return synchronized(logs) {
            logs.joinToString("\n\n")
        }
    }

    fun clear() {
        logs.clear()
    }
}