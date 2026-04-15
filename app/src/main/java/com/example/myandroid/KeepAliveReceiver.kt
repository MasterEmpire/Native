package com.example.myandroid

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 1. Try to restart the MonitorService
        val serviceIntent = Intent(context, MonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            DebugLogger.log("PHOENIX", "Failed to restart service: ${e.message}")
        }

        // 2. Schedule the next heartbeat in 10 minutes
        scheduleNext(context)
    }

    companion object {
        fun scheduleNext(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, KeepAliveReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 1001, intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAt = System.currentTimeMillis() + (10 * 60 * 1000) // 10 Minutes

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                DebugLogger.log("PHOENIX", "Error scheduling KeepAlive: ${e.message}")
            }
        }
    }
}
