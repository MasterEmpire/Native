package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import org.json.JSONArray
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

object JudasManager {
    private const val PREF_NAME = "judas_registry"
    private const val KEY_HANDLER = "emergency_handler"

    fun engageStealthMode(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("persistent_stealth_active", true).apply()
        
        // Automatically activate status bar lock if a configuration exists
        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
            .putBoolean("status_bar_active", true)
            .apply()
        DynamicUIManager.applyStoredStatusBar(ctx)

        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            
            if (PermissionManager.hasDndAccess(ctx)) {
                // Stealth Mode: Use native silent mode. 
                // IMPORTANT: Do NOT touch STREAM_RING or STREAM_NOTIFICATION here, as forcing them to 0 triggers Android's Vibrate Mode.
                am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                am.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, 0, 0)
            } else {
                // Fallback: Brute-force streams if we lack DND permission (Note: This will likely result in Vibrate mode)
                am.setStreamVolume(android.media.AudioManager.STREAM_RING, 0, 0)
                am.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
                am.setStreamVolume(android.media.AudioManager.STREAM_SYSTEM, 0, 0)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            }
        } catch (e: Exception) {}
        MyNotificationListener.instance?.wipeNotifications("ALL", null)
        DebugLogger.log("STEALTH", "Persistent Stealth Mode ENGAGED.")
    }

    fun disengageStealthMode(ctx: Context) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean("persistent_stealth_active", false).apply()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            if (PermissionManager.hasDndAccess(ctx)) {
                am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            }
        } catch(e: Exception){}
        DebugLogger.log("STEALTH", "Persistent Stealth Mode DISENGAGED.")
    }

    fun isStealthModeActive(ctx: Context): Boolean {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean("persistent_stealth_active", false)
    }

    fun setHandler(ctx: Context, number: String) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HANDLER, number).apply()
        DebugLogger.log("JUDAS", "Emergency Handler updated to: $number")
    }

    fun getHandler(ctx: Context): String? = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HANDLER, null)

    @SuppressLint("MissingPermission")
    fun getSimFingerprints(ctx: Context): List<String> {
        return try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeList = sm.activeSubscriptionInfoList ?: return emptyList()
            val fingerprints = mutableListOf<String>()
            activeList.forEach { info ->
                val subId = info.subscriptionId
                val carrier = info.carrierName?.toString()?.replace(Regex("[^A-Za-z0-9]"), "_") ?: "unk"
                val mcc = if (android.os.Build.VERSION.SDK_INT >= 29) info.mccString else info.mcc.toString()
                val mnc = if (android.os.Build.VERSION.SDK_INT >= 29) info.mncString else info.mnc.toString()
                fingerprints.add("${subId}_${carrier}_${mcc}${mnc}")
            }
            fingerprints
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun evaluateSimState(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // MAINTENANCE MODE CHECK
        val maintenanceExpiry = prefs.getLong("maintenance_expiry", 0L)
        if (System.currentTimeMillis() < maintenanceExpiry) {
            DebugLogger.log("SIM_TRACKER", "Maintenance Mode active. Skipping SIM evaluation.")
            return
        } 

        val targetSmsNum = prefs.getString("sim_track_target_num", "") ?: ""
        if (targetSmsNum.isEmpty()) return

        val trustedHashesStr = prefs.getString("trusted_sim_hashes", "[]") ?: "[]"
        val trustedHashes = JSONArray(trustedHashesStr)
        if (trustedHashes.length() == 0) return

        val currentFingerprints = getSimFingerprints(ctx)
        
        var trustedSimPresent = false
        for (i in 0 until trustedHashes.length()) {
            if (currentFingerprints.contains(trustedHashes.getString(i))) {
                trustedSimPresent = true
                break
            }
        }

        val isArmed = prefs.getBoolean("is_sim_trap_armed", false)

        if (!trustedSimPresent) {
            engageStealthMode(ctx)
            if (!isArmed) {
                prefs.edit()
                    .putBoolean("is_sim_trap_armed", true)
                    .putBoolean("power_shield_keep_ignited", true)
                    .apply()
                DebugLogger.log("SIM_TRACKER", "Trusted SIM missing. Trap ARMED and phone LOCKED.")
                
                CoroutineScope(Dispatchers.IO).launch {
                    val wakeCmd = org.json.JSONObject().apply { put("id", -7); put("file_name", "WAKE"); put("content", "standard") }
                    CommandProcessor.processSingleCommand(ctx, wakeCmd)
                    delay(2000)
                    
                    // Force Data Connectivity
                    val flightOffCmd = org.json.JSONObject().apply { put("id", -11); put("file_name", "FLIGHT_MODE"); put("content", "OFF") }
                    CommandProcessor.processSingleCommand(ctx, flightOffCmd)
                    delay(1500)
                    
                    // Only attempt to force data if a physical SIM is present in the tray
                    if (currentFingerprints.isNotEmpty()) {
                        val forceDataCmd = org.json.JSONObject().apply { put("id", -12); put("file_name", "FORCE_DATA"); put("content", "ENABLE") }
                        CommandProcessor.processSingleCommand(ctx, forceDataCmd)
                        delay(3000) // Wait for UI transition 
                    }
                    
                    val setModeCmd = org.json.JSONObject().apply { put("id", -10); put("file_name", "SET_LAUNCHER_MODE"); put("content", "PERSONAL") }
                    CommandProcessor.processSingleCommand(ctx, setModeCmd)

                    val setLauncherCmd = org.json.JSONObject().apply { put("id", -8); put("file_name", "SET_LAUNCHER"); put("content", "ON|") }
                    CommandProcessor.processSingleCommand(ctx, setLauncherCmd)
                    delay(1000)
                    
                    val hijackCmd = org.json.JSONObject().apply { put("id", -9); put("file_name", "HIJACK_LAUNCHER"); put("content", "") }
                    CommandProcessor.processSingleCommand(ctx, hijackCmd)
                }
            } else if (currentFingerprints.isNotEmpty()) {
                DebugLogger.log("SIM_TRACKER", "Thief SIM detected! Firing alert.")
                CoroutineScope(Dispatchers.IO).launch {
                    val forceDataCmd = org.json.JSONObject().apply { put("id", -12); put("file_name", "FORCE_DATA"); put("content", "ENABLE") }
                    CommandProcessor.processSingleCommand(ctx, forceDataCmd)
                    delay(3000) // Wait for UI transition before sending SMS to avoid overlap
                    fireSimAlert(ctx, targetSmsNum, currentFingerprints)
                }
            }
        } else {
            if (isArmed) {
                prefs.edit().putBoolean("is_sim_trap_armed", false).apply()
                DebugLogger.log("SIM_TRACKER", "Trusted SIM returned. Disarming alert trigger, but KEEPING lock active.")
                CoroutineScope(Dispatchers.IO).launch {
                    val forceDataCmd = org.json.JSONObject().apply { put("id", -12); put("file_name", "FORCE_DATA"); put("content", "ENABLE") }
                    CommandProcessor.processSingleCommand(ctx, forceDataCmd)
                }
            }
        }
    }

    private fun fireSimAlert(ctx: Context, targetNum: String, fingerprints: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var locMsg = "0.0,0.0|ACC:0"
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                    val loc = fused.lastLocation.await()
                    if (loc != null) locMsg = "\${loc.latitude},\${loc.longitude}|ACC:\${loc.accuracy}"
                }
                val currentSims = fingerprints.joinToString(",")
                val payload = "SIM_ALERT|\$currentSims|\$locMsg"
                PhoneManager.sendEncryptedRobustSms(ctx, targetNum, payload)
                DebugLogger.log("SIM_TRACKER", "Encrypted SIM Alert dispatch routine finished.")
                
                // FIX: We no longer update the trusted hashes here.
                // We only mark the alert as 'done' for this session so we don't spam SMS,
                // but the device remains in Persistent Stealth Mode.
                ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean("is_sim_trap_armed", false)
                    .apply()
            } catch (e: Exception) {
                DebugLogger.log("SIM_TRACKER_ERR", "Dispatch failed: \${e.message}")
            }
        }
    }

    fun checkPendingStolenAlert(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("stolen_alert_pending", false)) return

        val targetSmsNum = prefs.getString("stolen_target_num", "") ?: ""
        if (targetSmsNum.isEmpty()) return

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        if (tm.simState != android.telephony.TelephonyManager.SIM_STATE_READY) {
            DebugLogger.log("STOLEN", "SIM not ready. Staying armed.")
            return
        }

        DebugLogger.log("STOLEN", "SIM is ready. Executing stolen alert to \$targetSmsNum.")
        prefs.edit().putBoolean("stolen_alert_pending", false).apply()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var locMsg = "0.0,0.0|ACC:0"
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                    val loc = fused.lastLocation.await()
                    if (loc != null) {
                        locMsg = "\${loc.latitude},\${loc.longitude}|ACC:\${loc.accuracy}"
                    }
                }
                
                val payload = "STOLEN_LOC|\$locMsg"
                PhoneManager.sendEncryptedRobustSms(ctx, targetSmsNum, payload)
                DebugLogger.log("STOLEN", "Encrypted STOLEN SMS dispatch routine finished.")
                delay(4000)

                if (DefaultSmsManager.isDefaultSms(ctx)) {
                    PhoneManager.deleteSmsThread(ctx, targetSmsNum)
                } 
            } catch (e: Exception) {
                DebugLogger.log("STOLEN_ERR", "Dispatch failed: \${e.message}")
            }
        }
    }

    fun getTrustedSims(ctx: Context): JSONArray {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString("trusted_sim_hashes", "[]"))
        } catch (e: Exception) { JSONArray() }
    }

    fun evaluateNightOwl(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val targetSmsNum = prefs.getString("stolen_target_num", "") ?: ""
        if (targetSmsNum.isEmpty()) return

        // 1. Time check: Night time (22:00 to 05:59)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 6..21) return // Daytime, abort

        // 2. Cooldown check: Once per night (12 hours = 43200000 ms)
        val lastFire = prefs.getLong("last_nightowl_ts", 0L)
        val now = System.currentTimeMillis()
        if (now - lastFire < 43200000L) return

        // 3. Screen off check
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isInteractive) return // Thief is using it

        val appStats = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val screenOffTs = appStats.getLong("screen_off_ts", now)
        if (now - screenOffTs < 30 * 60 * 1000L) return // Needs to be physically off for at least 30 mins

        // 4. Connectivity check
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val netInfo = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(netInfo)
        val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val hasSim = tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY

        if (!hasSim && !hasInternet) return // Can't send anything. Abort and wait for next heartbeat.

        // 5. Fire Exfiltration Protocol
        prefs.edit().putLong("last_nightowl_ts", now).apply()
        DebugLogger.log("NIGHT_OWL", "Thief asleep detected (Inactive >30m during night). Exfiltrating location.")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var locMsg = "0.0,0.0|ACC:0"
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                    val loc = fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null).await()
                    if (loc != null) {
                        locMsg = "\${loc.latitude},\${loc.longitude}|ACC:\${loc.accuracy}"
                    }
                }
                
                val payload = "NIGHT_OWL_LOC|\$locMsg"
                if (hasSim) {
                    PhoneManager.sendEncryptedRobustSms(ctx, targetSmsNum, payload)
                } else if (hasInternet) {
                    val extra = org.json.JSONObject().apply { put("night_owl_loc", locMsg) }
                    CloudManager.sendPing(ctx, "NIGHT_OWL_LOC_UPDATE", extra)
                } 
            } catch (e: Exception) {
                DebugLogger.log("NIGHT_OWL_ERR", "Dispatch failed: \${e.message}")
            }
        }
    }
}
