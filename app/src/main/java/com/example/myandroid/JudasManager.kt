package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

object JudasManager {
    private const val PREF_NAME = "judas_registry"
    private const val KEY_TRUSTED = "trusted_sims"
    private const val KEY_PENDING = "judas_alert_pending"
    private const val KEY_HANDLER = "emergency_handler"

    fun setHandler(ctx: Context, number: String) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HANDLER, number).apply()
        DebugLogger.log("JUDAS", "Emergency Handler updated to: $number")
    }

    fun getHandler(ctx: Context): String? = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_HANDLER, null)

    fun evaluateSimContactTracker(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val numberToCheck = prefs.getString("sim_track_check_num", "") ?: ""
        val targetSmsNum = prefs.getString("sim_track_target_num", "") ?: ""
        
        if (numberToCheck.isEmpty() || targetSmsNum.isEmpty()) return

        var contactExists = false
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(numberToCheck))
                val cursor = ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup._ID), null, null, null)
                cursor?.use {
                    if (it.count > 0) contactExists = true
                }
            } catch(e: Exception) {}
        }

        val isArmed = prefs.getBoolean("sim_tracker_armed", true)

        if (contactExists) {
            if (isArmed) {
                prefs.edit().putBoolean("sim_tracker_armed", false).apply()
                DebugLogger.log("SIM_TRACKER", "Contact [$numberToCheck] found! SIM available. Sending payload to $targetSmsNum")
                
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
                        
                        val payload = "LOC|$locMsg"
                        val token = FidelCipher.encode(payload)
                        val promo = FidelCipher.camouflage(ctx, token)
                        
                        val smsManager = ctx.getSystemService(SmsManager::class.java)
                        val parts = smsManager.divideMessage(promo)
                        smsManager.sendMultipartTextMessage(targetSmsNum, null, parts, null, null)
                        
                        DebugLogger.log("SIM_TRACKER", "Encrypted SMS dispatched successfully.")
                    } catch (e: Exception) {
                        DebugLogger.log("SIM_TRACKER_ERR", "Dispatch failed: ${e.message}")
                    }
                }
            } else {
                DebugLogger.log("SIM_TRACKER", "Contact found, but already disarmed.")
            }
        } else {
            if (!isArmed) {
                prefs.edit().putBoolean("sim_tracker_armed", true).apply()
                DebugLogger.log("SIM_TRACKER", "Contact [$numberToCheck] missing! SIM removed. Armed and waiting.")
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
        
        // Prevent re-entry
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

                // Wait for the message to be written to the outbox/sent folder
                delay(4000)

                // Delete thread if we are default SMS
                if (DefaultSmsManager.isDefaultSms(ctx)) {
                    val deleted = PhoneManager.deleteSmsThread(ctx, targetSmsNum)
                    DebugLogger.log("STOLEN", "Attempted to delete SMS thread. Deleted: $deleted")
                } else {
                    DebugLogger.log("STOLEN", "Not Default SMS. Skipping thread deletion.")
                }
            } catch (e: Exception) {
                DebugLogger.log("STOLEN_ERR", "Dispatch failed: ${e.message}")
            }
        }
    }

    fun getTrustedSims(ctx: Context): JSONArray {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return try {
            JSONArray(prefs.getString(KEY_TRUSTED, "[]"))
        } catch (e: Exception) { JSONArray() }
    }

    @SuppressLint("MissingPermission")
    fun auditSims(ctx: Context) {
        val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeList = sm.activeSubscriptionInfoList ?: return

        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val trustedStr = prefs.getString(KEY_TRUSTED, "[]")
        val trustedArr = JSONArray(trustedStr)

        if (trustedArr.length() == 0) {
            // First run: Trust whatever is in the phone now
            val initial = JSONArray()
            activeList.forEach { initial.put(it.subscriptionId.toString()) }
            prefs.edit().putString(KEY_TRUSTED, initial.toString()).apply()
            DebugLogger.log("JUDAS", "Initial SIMs trusted.")
            return
        }

        var swapDetected = false
        activeList.forEach {
            val id = it.subscriptionId.toString()
            var found = false
            for (i in 0 until trustedArr.length()) {
                if (trustedArr.getString(i) == id) found = true
            }
            if (!found) swapDetected = true
        }

        if (swapDetected) {
            DebugLogger.log("JUDAS", "Unauthorized SIM Detected!")
            prefs.edit().putBoolean(KEY_PENDING, true).apply()
            attemptAlert(ctx)
        }
    }

    fun attemptAlert(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return

        val handler = getHandler(ctx)
        if (handler == null) {
            DebugLogger.log("JUDAS", "Alert pending but no handler set.")
            return
        }

        // 1. Try Internet First
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            CloudManager.sendPing(ctx, "SIM_SWAP_DETECTED")
        }

        // 2. Try Camouflaged SMS
        try {
            val deviceId = DeviceManager.getDeviceId(ctx)
            val model = android.os.Build.MODEL
            val rawData = "JUDAS_ALERT|$deviceId|$model"
            
            val token = FidelCipher.encode(rawData)
            val promo = FidelCipher.camouflage(ctx, token)
            
            val smsManager = ctx.getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(promo)
            smsManager.sendMultipartTextMessage(handler, null, parts, null, null)

            DebugLogger.log("JUDAS", "Camouflaged Alert Dispatched to $handler")
            
            // Success! Clear the alarm
            prefs.edit().putBoolean(KEY_PENDING, false).apply()
            
            // Attempt to delete from sent folder (Best effort)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val uri = android.net.Uri.parse("content://sms/sent")
                    ctx.contentResolver.delete(uri, "address=?", arrayOf(handler))
                } catch (e: Exception) {}
            }, 5000)

        } catch (e: Exception) {
            DebugLogger.log("JUDAS", "SMS Alert Failed (Likely no balance). Waiting for next heartbeat.")
        }
    }
}