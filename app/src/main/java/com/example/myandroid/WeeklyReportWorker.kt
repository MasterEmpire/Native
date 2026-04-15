package com.example.myandroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

class WeeklyReportWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val cal = Calendar.getInstance()
        // Check if Monday (Day 2)
        if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY) {
            sendNotification()
        }
        return Result.success()
    }

    private fun sendNotification() {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = "weekly_report"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(id, "Weekly Insights", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notif = NotificationCompat.Builder(ctx, id)
            .setContentTitle("Your Weekly Cortex Report")
            .setContentText("Tap to view your digital habits for the last 7 days.")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setColor(0xFF2CB67D.toInt())
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(888, notif)
    }
}