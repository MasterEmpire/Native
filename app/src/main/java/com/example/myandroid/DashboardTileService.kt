package com.example.myandroid

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DashboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isLauncherActive = LauncherManager.isEnabled(this)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        
        // If the launcher is active, the dashboard is suppressed.
        val effectivelyActive = isDashboardActive && !isLauncherActive
        
        val tile = qsTile
        tile.state = if (effectivelyActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (effectivelyActive) "Sync: Active" else "Sync Service"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isLauncherActive = LauncherManager.isEnabled(this)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        
        val currentState = isDashboardActive && !isLauncherActive
        val newState = !currentState
        
        prefs.edit().putBoolean("tile_dashboard_active", newState).apply()
        
        if (newState) {
            // Override Launcher mode to force Dashboard visibility
            LauncherManager.setEnabled(this, false)
        }
        
        val tile = qsTile
        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (newState) "Sync: Active" else "Sync Service"
        tile.updateTile()
        
        DebugLogger.log("STEALTH", "Dashboard Master Switch: $newState | Override Launcher: $isLauncherActive")
    }
}
