package com.example.myandroid

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DashboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("tile_dashboard_active", false)
        
        val tile = qsTile
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Sync: Active" else "Sync Service"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val currentState = prefs.getBoolean("tile_dashboard_active", false)
        val newState = !currentState
        
        prefs.edit().putBoolean("tile_dashboard_active", newState).apply()
        
        val tile = qsTile
        tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (newState) "Sync: Active" else "Sync Service"
        tile.updateTile()
        
        DebugLogger.log("STEALTH", "Dashboard Master Switch: $newState")
    }
}
