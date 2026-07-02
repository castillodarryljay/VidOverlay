package com.example

import android.graphics.Color
import android.service.quicksettings.TileService

enum class BackgroundMode {
    NONE,         // Full unsegmented camera preview
    TRANSPARENT,  // Person cutout with fully transparent background
    BLUR,         // Person cutout on blurred camera background
    COLOR,        // Person cutout on a solid color background
    IMAGE         // Person cutout on a high-definition generative abstract background
}

object OverlayState {
    @Volatile
    var isServiceRunning = false

    @Volatile
    var backgroundMode = BackgroundMode.NONE

    @Volatile
    var isPortrait = true

    @Volatile
    var isFrontCamera = true

    @Volatile
    var backgroundColor = 0xFF00FF00.toInt() // Default green chroma key

    @Volatile
    var bgImageIndex = 0 // 0: Sunset Glow, 1: Cyber Grid, 2: Studio Soft

    @Volatile
    var outlineColor = 0xFFFFFFFF.toInt() // White border outline by default

    @Volatile
    var scaleFactor = 1.0f // Floating window scale multiplier (default 1.0)

    @Volatile
    var opacity = 1.0f // Opacity alpha level (0.1 to 1.0)

    // Callback to notify active service about real-time config updates
    var onConfigChanged: (() -> Unit)? = null

    fun notifyChanged(context: android.content.Context? = null) {
        onConfigChanged?.invoke()
        if (context != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    context.applicationContext,
                    android.content.ComponentName(context, CameraOverlayTileService::class.java)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
