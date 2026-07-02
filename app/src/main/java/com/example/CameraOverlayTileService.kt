package com.example

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class CameraOverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        // Shortcut to launch the application when clicked.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                0,
                intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = OverlayState.isServiceRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Floating Camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Active" else "Tap to open app"
        }
        tile.updateTile()
    }
}
