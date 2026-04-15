package com.example.myandroid

import android.content.Context
import java.util.Calendar

object TimeManager {

    fun getStartOfDay(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun checkDailyReset(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val lastReset = prefs.getLong("last_daily_reset", 0L)
        val startToday = getStartOfDay()

        if (startToday > lastReset) {
            // IT IS A NEW DAY. RESET COUNTERS.
            prefs.edit()
                .putInt("sms_count", 0)
                .putInt("notif_count", 0)
                .putInt("interaction_count", 0)
                .putInt("net_session_count", 0)
                .putLong("net_total_time", 0L)
                .putString("typing_history", "[]") // Optional: Clear logs or keep them is up to you
                .putLong("last_daily_reset", startToday)
                .apply()
            
            // Clear in-memory trackers if needed
            // (NetworkTracker resets automatically on new session)
        }
    }
}