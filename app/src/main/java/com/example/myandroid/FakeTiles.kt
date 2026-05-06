package com.example.myandroid

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

abstract class BaseFakeTile(private val prefKey: String, private val labelOn: String, private val labelOff: String) : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean(prefKey, true) // Default to true to look established
        updateTileState(isActive)
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val currentState = prefs.getBoolean(prefKey, true)
        val newState = !currentState
        prefs.edit().putBoolean(prefKey, newState).apply()
        updateTileState(newState)
        DebugLogger.log("DECEPTION", "Fake Tile [$prefKey] toggled to: $newState")
    }

    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) labelOn else labelOff
        tile.updateTile()
    }
}

class WifiTileService : BaseFakeTile("fake_tile_wifi", "Wi-Fi", "Wi-Fi")
class LocationTileService : BaseFakeTile("fake_tile_location", "Location", "Location")
class DataTileService : BaseFakeTile("fake_tile_data", "Mobile data", "Mobile data")
class AirplaneTileService : BaseFakeTile("fake_tile_airplane", "Airplane mode", "Airplane mode")