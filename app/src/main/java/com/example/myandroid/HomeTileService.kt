package com.example.myandroid

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import android.os.Handler
import android.os.Looper

class HomeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        val isLauncherActive = LauncherManager.isEnabled(this)

        val tile = qsTile ?: return
        
        // Strict gating logic to prevent conflict with the Sync Quick Tile masquerade rules
        if (!isDashboardActive) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = "Home (Disabled)"
        } else {
            tile.state = if (isLauncherActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (isLauncherActive) "Home: Active" else "Home: Disabled"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        
        if (!isDashboardActive) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Enable Sync Service first to use Home feature.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val isLauncherActive = LauncherManager.isEnabled(this)
        val newState = !isLauncherActive
        
        LauncherManager.setEnabled(this, newState)
        
        val tile = qsTile ?: return
        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (newState) "Home: Active" else "Home: Disabled"
        tile.updateTile()
        
        DebugLogger.log("STEALTH", "Home Launcher Mode Toggled: $newState")
    }
}