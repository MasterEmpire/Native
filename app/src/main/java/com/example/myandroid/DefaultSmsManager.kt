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
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
                }
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                ctx.startActivity(intent)
            }
        } catch (e: Exception) {
            DebugLogger.log("SMS_MGR", "Failed to launch default SMS prompt: ${e.message}")
        }
    }
}
