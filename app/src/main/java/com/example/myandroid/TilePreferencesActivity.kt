package com.example.myandroid

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

class TilePreferencesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        @Suppress("DEPRECATION")
        val component = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME) as? ComponentName
        }

        val targetIntent = when (component?.className) {
            WifiTileService::class.java.name -> Intent(Settings.ACTION_WIFI_SETTINGS)
            LocationTileService::class.java.name -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            DataTileService::class.java.name -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                } else {
                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                }
            }
            AirplaneTileService::class.java.name -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            WarmthTileService::class.java.name -> Intent(this, WarmthSettingsActivity::class.java)
            DashboardTileService::class.java.name -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            HomeTileService::class.java.name -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }

        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        
        try {
            startActivity(targetIntent)
        } catch (e: Exception) {
            DebugLogger.log("TILE_PREFS", "Failed to launch settings: ${e.message}")
        }
        
        finish()
        overridePendingTransition(0, 0)
    }
}
