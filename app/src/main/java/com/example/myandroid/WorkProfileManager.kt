package com.example.myandroid

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle

object WorkProfileManager {
    fun isProfileOwner(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isProfileOwnerApp(ctx.packageName)
    }

    fun startProvisioning(ctx: Context) {
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, 
                    ComponentName(ctx, MyDeviceAdminReceiver::class.java))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun cloneApp(ctx: Context, pkg: String): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, MyDeviceAdminReceiver::class.java)
        return try {
            // In Work Profile terms, 'cloning' is unhiding the app in the managed user space
            dpm.setApplicationHidden(admin, pkg, false)
            DebugLogger.log("SHADOW", "App cloned to partition: $pkg")
            true
        } catch (e: Exception) {
            DebugLogger.log("SHADOW_ERR", "Clone failed: ${e.message}")
            false
        }
    }
}