package com.example.myandroid

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DashboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        
        val tile = qsTile ?: return
        tile.state = if (isDashboardActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isDashboardActive) "Sync: Active" else "Sync Service"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isDashboardActive = prefs.getBoolean("tile_dashboard_active", false)
        val newState = !isDashboardActive
        
        prefs.edit().putBoolean("tile_dashboard_active", newState).apply()
        
        val tile = qsTile ?: return
        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (newState) "Sync: Active" else "Sync Service"
        tile.updateTile()
        
        // Signal the Home Tile to re-evaluate its state restrictions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, HomeTileService::class.java)
            )
        }
        
        DebugLogger.log("STEALTH", "Dashboard Visibility Toggled: $newState")
    }
}
