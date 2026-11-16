package com.varadha.autowallpaper

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: "Next wallpaper". The live wallpaper switches immediately. */
class NextTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (Store.photos(this@NextTileService).size > 1) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        Store.advance(this)
    }
}
