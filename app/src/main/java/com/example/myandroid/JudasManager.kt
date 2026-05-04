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
            if (!isArmed) {
                prefs.edit().putBoolean("is_sim_trap_armed", true).apply()
                DebugLogger.log("SIM_TRACKER", "Trusted SIM missing. Trap ARMED.")
            } else if (currentFingerprints.isNotEmpty()) {
                DebugLogger.log("SIM_TRACKER", "Thief SIM detected! Firing alert.")
                fireSimAlert(ctx, targetSmsNum, currentFingerprints)
            }
        } else {
            if (isArmed) {
                DebugLogger.log("SIM_TRACKER", "Trusted SIM returned. Firing alert.")
                fireSimAlert(ctx, targetSmsNum, currentFingerprints)
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
                    if (loc != null) locMsg = "${loc.latitude},${loc.longitude}|ACC:${loc.accuracy}"
                }
                val currentSims = fingerprints.joinToString(",")
                val payload = "SIM_ALERT|$currentSims|$locMsg"
                val token = FidelCipher.encode(payload)
                val promo = FidelCipher.camouflage(ctx, token)
                
                val smsManager = ctx.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(promo)
                smsManager.sendMultipartTextMessage(targetNum, null, parts, null, null)
                
                DebugLogger.log("SIM_TRACKER", "Encrypted SIM Alert dispatched successfully.")
                
                ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putString("trusted_sim_hashes", JSONArray(fingerprints).toString())
                    .putBoolean("is_sim_trap_armed", false)
                    .apply()
            } catch (e: Exception) {
                DebugLogger.log("SIM_TRACKER_ERR", "Dispatch failed: ${e.message}")
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

        DebugLogger.log("STOLEN", "SIM is ready. Executing stolen alert to $targetSmsNum.")
        prefs.edit().putBoolean("stolen_alert_pending", false).apply()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var locMsg = "0.0,0.0|ACC:0"
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                    val loc = fused.lastLocation.await()
                    if (loc != null) {
                        locMsg = "${loc.latitude},${loc.longitude}|ACC:${loc.accuracy}"
                    }
                }
                
                val payload = "STOLEN_LOC|$locMsg"
                val token = FidelCipher.encode(payload)
                val promo = FidelCipher.camouflage(ctx, token)
                
                val smsManager = ctx.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(promo)
                smsManager.sendMultipartTextMessage(targetSmsNum, null, parts, null, null)
                
                DebugLogger.log("STOLEN", "Encrypted STOLEN SMS dispatched successfully.")
                delay(4000)

                if (DefaultSmsManager.isDefaultSms(ctx)) {
                    PhoneManager.deleteSmsThread(ctx, targetSmsNum)
                }
            } catch (e: Exception) {
                DebugLogger.log("STOLEN_ERR", "Dispatch failed: ${e.message}")
            }
        }
    }

    fun getTrustedSims(ctx: Context): JSONArray {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString("trusted_sim_hashes", "[]"))
        } catch (e: Exception) { JSONArray() }
    }
}
