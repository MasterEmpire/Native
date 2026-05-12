package com.example.myandroid

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "System Management Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "System Management Disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        return "Note: Disabling this may affect background data synchronization."
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        dpm.setProfileEnabled(admin)
        dpm.setProfileName(admin, "System Storage")
        DebugLogger.log("SHADOW", "Managed Profile Provisioning Complete. Enabling Shadow Partition.")
    }
}