package com.example.myandroid

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

object ConfigManager {

    // Default: Allow everything always
    private const val DEFAULT_CONFIG = "{\"features\":{\"all\":{\"collect\":{\"mode\":\"ALWAYS\"},\"upload\":{\"mode\":\"ALWAYS\"}}}}"

    fun getConfig(ctx: Context): JSONObject {
        val prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val raw = prefs.getString("json", DEFAULT_CONFIG)
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject(DEFAULT_CONFIG) }
    }



    fun setFeatureParametric(ctx: Context, feature: String, mode: String, p1: String? = null, p2: String? = null) {
        try {
            val current = getConfig(ctx)
            val features = current.optJSONObject("features") ?: JSONObject()
            val rule = features.optJSONObject(feature) ?: JSONObject()
            val collect = rule.optJSONObject("collect") ?: JSONObject()

            collect.remove("expiry")
            collect.remove("start")
            collect.remove("end")

            when (mode.uppercase()) {
                "ON", "ALWAYS", "TRUE" -> {
                    collect.put("mode", "ALWAYS")
                }
                "OFF", "NEVER", "FALSE" -> {
                    collect.put("mode", "NEVER")
                    val durationMins = p1?.toLongOrNull() ?: 0L
                    if (durationMins > 0) {
                        collect.put("expiry", System.currentTimeMillis() + (durationMins * 60 * 1000))
                    }
                }
                "SCHED", "SCHEDULED" -> {
                    collect.put("mode", "SCHEDULED")
                    collect.put("start", p1 ?: "00:00")
                    collect.put("end", p2 ?: "23:59")
                }
            }

            rule.put("collect", collect)
            features.put(feature, rule)
            current.put("features", features)
            
            ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE)
                .edit().putString("json", current.toString()).apply()
                
            DebugLogger.log("CONFIG", "Parametric Update [$feature]: Mode=$mode P1=$p1 P2=$p2")
        } catch (e: Exception) { 
            DebugLogger.log("CONFIG_ERR", "Parametric set failed: ${e.message}")
        }
    }

    fun canCollect(ctx: Context, feature: String): Boolean {
        // 1. Get Rule
        val config = getConfig(ctx)
        val features = config.optJSONObject("features") ?: return true
        val rule = features.optJSONObject(feature) ?: features.optJSONObject("all") ?: return true
        
        val collect = rule.optJSONObject("collect") ?: return true
        val mode = collect.optString("mode", "ALWAYS")

        // 2. Logic
        if (mode == "NEVER") {
            val expiry = collect.optLong("expiry", 0L)
            if (expiry == 0L) return false // Permanent Off
            if (System.currentTimeMillis() < expiry) return false // Still in timeout
            
            // TTL Expired! Self-heal back to Always
            return true
        }
        if (mode == "ALWAYS") return true
        
        if (mode == "SCHEDULED") {
            val start = collect.optString("start", "00:00")
            val end = collect.optString("end", "23:59")
            return isTimeBetween(start, end)
        }
        return true
    }

    fun canUpload(ctx: Context): Boolean {
        val config = getConfig(ctx)
        val global = config.optJSONObject("features")?.optJSONObject("all")?.optJSONObject("upload") ?: return true
        val mode = global.optString("mode", "ALWAYS")
        
        if (mode == "WIFI_ONLY") {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            return caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        
        if (mode == "SCHEDULED") {
             val start = global.optString("start", "00:00")
             val end = global.optString("end", "23:59")
             return isTimeBetween(start, end)
        }
        
        return true
    }

    private fun isTimeBetween(start: String, end: String): Boolean {
        try {
            val now = Calendar.getInstance()
            val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            
            val (sh, sm) = start.split(":").map { it.toInt() }
            val (eh, em) = end.split(":").map { it.toInt() }
            val sMin = sh * 60 + sm
            val eMin = eh * 60 + em
            
            return current in sMin..eMin
        } catch(e: Exception) {
            return true // Fallback to allow operation if schedule config is corrupted
        }
    }
}