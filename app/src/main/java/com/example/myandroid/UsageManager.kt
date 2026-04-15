package com.example.myandroid

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object UsageManager {

    fun getTimeline(ctx: Context): JSONArray {
        val list = JSONArray()
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val startTime = calendar.timeInMillis

            val events = usm.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val entry = JSONObject()
                    entry.put("pkg", event.packageName)
                    entry.put("ts", event.timeStamp)
                    entry.put("time_fmt", SimpleDateFormat("HH:mm:ss", Locale.US).format(event.timeStamp))
                    list.put(entry)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun getSwitchCount(ctx: Context): Int {
        // Returns how many times you opened a new app today
        return getTimeline(ctx).length()
    }
}