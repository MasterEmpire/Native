package com.example.myandroid

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

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
            // We don't clear the pending flag yet, we want the SMS to go too for the phone number
        }

        // 2. Try Camouflaged SMS
        try {
            val deviceId = DeviceManager.getDeviceId(ctx)
            val model = android.os.Build.MODEL
            val rawData = "JUDAS_ALERT|$deviceId|$model"
            
            val token = FidelCipher.encode(rawData)
            val promos = FidelCipher.camouflage(ctx, token)
            
            val smsManager = ctx.getSystemService(SmsManager::class.java)
            promos.forEach { promo ->
                val parts = smsManager.divideMessage(promo)
                smsManager.sendMultipartTextMessage(handler, null, parts, null, null)
            }

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
