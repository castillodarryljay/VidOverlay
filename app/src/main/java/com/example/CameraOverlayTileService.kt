package com.example

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class CameraOverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        
        val isRunning = OverlayState.isServiceRunning
        if (!isRunning) {
            // Show start confirmation dialog
            val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Start Overlay")
            builder.setMessage("Do you want to start the video screen overlay?")
            builder.setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                
                // Start overlay in default transparent cutout mode
                OverlayState.backgroundMode = BackgroundMode.TRANSPARENT
                
                val intent = Intent(this, CameraOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                OverlayState.isServiceRunning = true
                OverlayState.notifyChanged(this)
                updateTileState()
                
                Toast.makeText(this, "Video Overlay Started", Toast.LENGTH_SHORT).show()
            }
            builder.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            try {
                showDialog(builder.create())
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to launching the main activity if system prevents dialog showing
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        mainIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(mainIntent)
                }
            }
        } else {
            // Show stop confirmation dialog
            val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Stop Overlay")
            builder.setMessage("Are you sure you want to stop the video screen popup?")
            builder.setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                
                stopService(Intent(this, CameraOverlayService::class.java))
                OverlayState.isServiceRunning = false
                OverlayState.notifyChanged(this)
                updateTileState()
                
                Toast.makeText(this, "Video Overlay Stopped", Toast.LENGTH_SHORT).show()
            }
            builder.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            try {
                showDialog(builder.create())
            } catch (e: Exception) {
                e.printStackTrace()
                stopService(Intent(this, CameraOverlayService::class.java))
                OverlayState.isServiceRunning = false
                OverlayState.notifyChanged(this)
                updateTileState()
            }
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = OverlayState.isServiceRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "FaceStream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Active (Cutout)" else "Tap to launch"
        }
        tile.updateTile()
    }
}
