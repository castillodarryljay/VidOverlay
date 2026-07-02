package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Size
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

class CameraOverlayService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var windowParams: WindowManager.LayoutParams

    private lateinit var previewView: PreviewView
    private lateinit var segmentedImageView: ImageView

    private var cameraProvider: ProcessCameraProvider? = null
    private var segmenter: Segmenter? = null

    private var lastWidth = 0
    private var lastHeight = 0

    private val analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val isProcessingFrame = java.util.concurrent.atomic.AtomicBoolean(false)

    private val NOTIFICATION_ID = 8821
    private val CHANNEL_ID = "camera_overlay_service_channel"

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var minimizeDialog: android.app.AlertDialog? = null

    private val minimizePopupRunnable = Runnable {
        showMinimizePopup()
    }

    private val openAppRunnable = Runnable {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(launchIntent)
        Toast.makeText(this, "Opening App", Toast.LENGTH_SHORT).show()
    }

    private fun showMinimizePopup() {
        minimizeDialog?.dismiss()

        val context = this
        val builder = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

        val density = context.resources.displayMetrics.density
        val dpToPx = { dp: Int -> (dp * density).toInt() }

        // ScrollView container to handle potential height overflow gracefully on small screens
        val scrollView = android.widget.ScrollView(context).apply {
            isFillViewport = true
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            
            // Premium dark frosted glass background with subtle stroke
            val gd = GradientDrawable().apply {
                setColor(0xEE0B0F19.toInt()) // Deepest dark frosted glass slate
                cornerRadius = 32f * density // Beautiful iOS rounded corners
                setStroke((1.5f * density).toInt(), 0x33FFFFFF) // Refined glowing outline
            }
            background = gd
        }
        scrollView.addView(layout)

        // Title text
        val titleView = TextView(context).apply {
            text = "OVERLAY SETTINGS"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(titleView)

        // Helper for Section Labels
        fun addSectionLabel(textStr: String) {
            val label = TextView(context).apply {
                text = textStr
                textSize = 12f
                setTextColor(0xFF38BDF8.toInt()) // Vibrant neon blue
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                setPadding(0, dpToPx(12), 0, dpToPx(6))
            }
            layout.addView(label)
        }

        // 1. Background Mode Section
        addSectionLabel("CUTOUT MODE / BACKGROUND")

        val modeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val horizontalScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeContainer)
        }
        layout.addView(horizontalScroll)

        val modes = listOf(
            BackgroundMode.NONE to "Original",
            BackgroundMode.TRANSPARENT to "Cutout",
            BackgroundMode.BLUR to "Blur",
            BackgroundMode.COLOR to "Solid",
            BackgroundMode.IMAGE to "Image"
        )

        val modeButtons = mutableListOf<Button>()

        fun updateModeButtonStyles() {
            for (i in modes.indices) {
                val (mode, _) = modes[i]
                val btn = modeButtons[i]
                val isActive = OverlayState.backgroundMode == mode
                
                val btnBg = GradientDrawable().apply {
                    if (isActive) {
                        setColor(0x440A84FF.toInt()) // Frosted Blue
                        cornerRadius = 16f * density
                        setStroke((1.5f * density).toInt(), 0xFF0A84FF.toInt())
                    } else {
                        setColor(0x11FFFFFF.toInt()) // Frosted White
                        cornerRadius = 16f * density
                        setStroke((1f * density).toInt(), 0x33FFFFFF)
                    }
                }
                btn.background = btnBg
                btn.setTextColor(if (isActive) Color.WHITE else 0xFF94A3B8.toInt())
            }
        }

        for (pair in modes) {
            val (mode, labelText) = pair
            val btn = Button(context).apply {
                text = labelText
                textSize = 12f
                isAllCaps = false
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                
                setOnClickListener {
                    OverlayState.backgroundMode = mode
                    OverlayState.notifyChanged(context)
                    updateModeButtonStyles()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(38)
            ).apply {
                setMargins(0, 0, dpToPx(8), 0)
            }
            modeContainer.addView(btn, params)
            modeButtons.add(btn)
        }
        updateModeButtonStyles()

        // 2. Opacity Section
        addSectionLabel("OPACITY")
        val opacityLabel = TextView(context).apply {
            text = "Level: ${(OverlayState.opacity * 100).toInt()}%"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dpToPx(4))
        }
        layout.addView(opacityLabel)

        val opacitySeekBar = android.widget.SeekBar(context).apply {
            max = 90 // 10% to 100%
            progress = ((OverlayState.opacity - 0.1f) * 100).toInt()
            
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val newOpacity = 0.1f + (progress / 100f)
                    OverlayState.opacity = newOpacity
                    opacityLabel.text = "Level: ${(newOpacity * 100).toInt()}%"
                    OverlayState.notifyChanged(context)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        layout.addView(opacitySeekBar)

        // 3. Size / Scale Section
        addSectionLabel("OVERLAY SIZE")
        val sizeLabel = TextView(context).apply {
            text = "Scale: ${(OverlayState.scaleFactor * 100).toInt()}%"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dpToPx(4))
        }
        layout.addView(sizeLabel)

        val sizeSeekBar = android.widget.SeekBar(context).apply {
            max = 130 // 20% to 150%
            progress = ((OverlayState.scaleFactor - 0.2f) * 100).toInt()
            
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val newScale = 0.2f + (progress / 100f)
                    OverlayState.scaleFactor = newScale
                    sizeLabel.text = "Scale: ${(newScale * 100).toInt()}%"
                    OverlayState.notifyChanged(context)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        layout.addView(sizeSeekBar)

        // 4. Camera Selection Section
        addSectionLabel("CAMERA")
        val cameraToggleBtn = Button(context).apply {
            text = if (OverlayState.isFrontCamera) "Switch to Back Camera" else "Switch to Front Camera"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            
            val btnBg = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), 0x44FFFFFF)
            }
            background = btnBg
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            
            setOnClickListener {
                OverlayState.isFrontCamera = !OverlayState.isFrontCamera
                text = if (OverlayState.isFrontCamera) "Switch to Back Camera" else "Switch to Front Camera"
                
                // Re-bind camera immediately
                try {
                    bindCamera()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                OverlayState.notifyChanged(context)
            }
        }
        val cameraBtnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dpToPx(16))
        }
        layout.addView(cameraToggleBtn, cameraBtnParams)

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(0x22FFFFFF)
        }
        val divParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1f * density).toInt()
        ).apply {
            setMargins(0, dpToPx(12), 0, dpToPx(16))
        }
        layout.addView(divider, divParams)

        // Bottom Actions layout
        val actionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val actionBtnParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(0, 0, dpToPx(8), 0)
        }

        // Stop Service button
        val stopBtn = Button(context).apply {
            text = "Stop Overlay"
            textSize = 13f
            setTextColor(0xFFEF4444.toInt()) // Neon Premium Red
            isAllCaps = false
            
            val btnBg = GradientDrawable().apply {
                setColor(0x1AEF4444.toInt())
                cornerRadius = 14f * density
                setStroke((1f * density).toInt(), 0x33EF4444.toInt())
            }
            background = btnBg
            
            setOnClickListener {
                stopSelf()
                OverlayState.isServiceRunning = false
                OverlayState.notifyChanged(context)
                minimizeDialog?.dismiss()
            }
        }
        actionsContainer.addView(stopBtn, actionBtnParams)

        // Done button
        val doneBtn = Button(context).apply {
            text = "Done"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            
            val btnBg = GradientDrawable().apply {
                setColor(0xFF0A84FF.toInt()) // iOS Premium Blue
                cornerRadius = 14f * density
            }
            background = btnBg
            
            setOnClickListener {
                minimizeDialog?.dismiss()
            }
        }
        val doneBtnParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            setMargins(dpToPx(8), 0, 0, 0)
        }
        actionsContainer.addView(doneBtn, doneBtnParams)

        layout.addView(actionsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        builder.setView(scrollView)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
        }
        
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        
        minimizeDialog = dialog
        dialog.show()
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize ML Kit Selfie Segmenter
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
        segmenter = Segmentation.getClient(options)

        // Setup Foreground Notification
        createNotificationChannel()
        showForegroundNotification()

        // Build floating UI window overlay
        setupFloatingWindow()

        // Start Camera Use Cases
        setupCamera()

        // Register State Update callback
        OverlayState.isServiceRunning = true
        OverlayState.onConfigChanged = {
            if (::overlayView.isInitialized) {
                overlayView.post {
                    updateFloatingLayout()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Overlay Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Overlay Running")
            .setContentText("Tap to open control center.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupFloatingWindow() {
        // Decide WindowManager flag depending on Android version
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Determine starting sizes based on Portrait vs Landscape
        val baseWidth = if (OverlayState.isPortrait) 360 else 640
        val baseHeight = if (OverlayState.isPortrait) 640 else 360
        val scale = OverlayState.scaleFactor

        val startWidth = (baseWidth * scale).toInt()
        val startHeight = (baseHeight * scale).toInt()
        lastWidth = startWidth
        lastHeight = startHeight

        windowParams = WindowManager.LayoutParams(
            startWidth,
            startHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 150
            y = 250
            alpha = OverlayState.opacity
        }

        // Create the root container view
        overlayView = FrameLayout(this).apply {
            // Apply clipping for rounded corners
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 48f) // 48px round
                }
            }
        }

        // Camera PreviewView (used when background removal is OFF)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView.addView(previewView)

        // ImageView for Segmented Frames (used when background removal is ON)
        segmentedImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        overlayView.addView(segmentedImageView)

        // Custom touch variables for drag, pinch-to-zoom, orientation rotation, and long press
        var touchMode = 0 // 0: NONE, 1: DRAG, 2: ZOOM_ROTATE
        var oldDist = 1f
        var oldRotation = 0f
        var initialWidth = 0
        var initialHeight = 0
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTapTime = 0L
        var initialRotation = 0f

        overlayView.setOnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    touchMode = 1 // DRAG
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    // Handle 3-second long press for Minimize Options, and 5-second long press to Open App
                    longPressHandler.removeCallbacks(minimizePopupRunnable)
                    longPressHandler.removeCallbacks(openAppRunnable)
                    longPressHandler.postDelayed(minimizePopupRunnable, 3000L)
                    longPressHandler.postDelayed(openAppRunnable, 5000L)

                    // Double Tap to toggle landscape/portrait orientation and reset physical rotation
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTapTime < 300L) {
                        OverlayState.isPortrait = !OverlayState.isPortrait
                        overlayView.rotation = 0f
                        OverlayState.notifyChanged(this@CameraOverlayService)
                        Toast.makeText(this@CameraOverlayService, "Reset Rotation & Swapped Aspect", Toast.LENGTH_SHORT).show()
                    }
                    lastTapTime = currentTime
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val count = event.pointerCount
                    if (count >= 2) {
                        touchMode = 2 // ZOOM_ROTATE
                        oldDist = spacing(event)
                        oldRotation = rotation(event)
                        initialWidth = windowParams.width
                        initialHeight = windowParams.height
                        initialRotation = overlayView.rotation

                        // Cancel accidental launches on multi-touch
                        longPressHandler.removeCallbacks(minimizePopupRunnable)
                        longPressHandler.removeCallbacks(openAppRunnable)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // If finger moves significantly, cancel long press
                    val moveDist = Math.hypot((event.rawX - initialTouchX).toDouble(), (event.rawY - initialTouchY).toDouble())
                    if (moveDist > 15) {
                        longPressHandler.removeCallbacks(minimizePopupRunnable)
                        longPressHandler.removeCallbacks(openAppRunnable)
                    }

                    if (touchMode == 1 && event.pointerCount == 1) {
                        // Drag layout around
                        windowParams.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        windowParams.y = (initialY + (event.rawY - initialTouchY)).toInt()
                        windowManager.updateViewLayout(overlayView, windowParams)
                    } else if (touchMode == 2 && event.pointerCount >= 2) {
                        // Pinch to Zoom (making it small or big dynamically)
                        val newDist = spacing(event)
                        if (newDist > 10f) {
                            val scale = newDist / oldDist
                            val newWidth = (initialWidth * scale).toInt().coerceIn(150, 1200)
                            val newHeight = if (OverlayState.isPortrait) {
                                (newWidth * 16 / 9).coerceIn(266, 2133)
                            } else {
                                (newWidth * 9 / 16).coerceIn(84, 675)
                            }
                            windowParams.width = newWidth
                            windowParams.height = newHeight

                            // Update scaleFactor dynamically to keep layout updates persistent
                            val baseWidth = if (OverlayState.isPortrait) 360f else 640f
                            OverlayState.scaleFactor = newWidth.toFloat() / baseWidth

                            windowManager.updateViewLayout(overlayView, windowParams)
                        }

                        // Continuous physical/literal rotation around center (only allowed for segmented/background-removed states)
                        if (OverlayState.backgroundMode != BackgroundMode.NONE) {
                            val newRotation = rotation(event)
                            var angleDiff = newRotation - oldRotation
                            // Normalize to prevent jumps at -180/180 degrees
                            if (angleDiff > 180f) {
                                angleDiff -= 360f
                            } else if (angleDiff < -180f) {
                                angleDiff += 360f
                            }
                            overlayView.rotation = (initialRotation + angleDiff)
                        } else {
                            overlayView.rotation = 0f
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(minimizePopupRunnable)
                    longPressHandler.removeCallbacks(openAppRunnable)
                    touchMode = 0
                }
            }
            true
        }

        // Inject overlay root view into the system window manager
        windowManager.addView(overlayView, windowParams)
    }

    private fun spacing(event: MotionEvent): Float {
        return try {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            Math.sqrt((x * x + y * y).toDouble()).toFloat()
        } catch (e: Exception) {
            1f
        }
    }

    private fun rotation(event: MotionEvent): Float {
        return try {
            val deltaX = (event.getX(0) - event.getX(1)).toDouble()
            val deltaY = (event.getY(0) - event.getY(1)).toDouble()
            val radians = Math.atan2(deltaY, deltaX)
            Math.toDegrees(radians).toFloat()
        } catch (e: Exception) {
            0f
        }
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCamera() {
        val provider = cameraProvider ?: return

        // Flip front/back selector
        val cameraSelector = if (OverlayState.isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Limit camera processing frame rate / size to preserve battery on overlays
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            ).build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            if (OverlayState.backgroundMode == BackgroundMode.NONE) {
                // Background removal is off, display native PreviewView
                previewView.post {
                    previewView.visibility = View.VISIBLE
                    segmentedImageView.visibility = View.GONE
                }
                imageProxy.close()
                return@setAnalyzer
            }

            // Frame skipping logic: if we are already processing a frame, skip this one
            if (!isProcessingFrame.compareAndSet(false, true)) {
                imageProxy.close()
                return@setAnalyzer
            }

            // Background removal active, analyze and draw to Custom ImageView
            previewView.post {
                previewView.visibility = View.GONE
                segmentedImageView.visibility = View.VISIBLE
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                segmenter?.process(inputImage)
                    ?.addOnSuccessListener(analysisExecutor) { mask ->
                        renderSegmentedFrame(imageProxy, mask)
                    }
                    ?.addOnFailureListener(analysisExecutor) {
                        imageProxy.close()
                        isProcessingFrame.set(false)
                    }
            } else {
                imageProxy.close()
                isProcessingFrame.set(false)
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderSegmentedFrame(
        imageProxy: androidx.camera.core.ImageProxy,
        mask: com.google.mlkit.vision.segmentation.SegmentationMask
    ) {
        try {
            // 1. Get raw Bitmap from frame (which is in YUV_420_888, converted cleanly by CameraX)
            val rawBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees

            // Rotate the raw bitmap first to match the actual display/upright orientation
            val rotatedRawBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }

            val RW = rotatedRawBitmap.width
            val RH = rotatedRawBitmap.height

            // 2. Process ML Mask into white-gray alpha Map Bitmap (aligned with rotated raw image)
            val maskWidth = mask.width
            val maskHeight = mask.height
            val maskBuffer = mask.buffer
            maskBuffer.rewind()
            maskBuffer.order(java.nio.ByteOrder.nativeOrder())

            val maskPixels = IntArray(maskWidth * maskHeight)
            for (i in 0 until maskWidth * maskHeight) {
                val confidence = maskBuffer.float
                val alpha = (confidence * 255).toInt().coerceIn(0, 255)
                maskPixels[i] = (alpha shl 24) or 0x00FFFFFF // alpha-weighted white pixels
            }

            val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
            maskBitmap.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

            // 3. Composite output canvas matching the final rotated/upright sizes
            val compositedBitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(compositedBitmap)

            // A: Draw the chosen background state on the rotated canvas size
            when (OverlayState.backgroundMode) {
                BackgroundMode.COLOR -> {
                    canvas.drawColor(OverlayState.backgroundColor)
                }
                BackgroundMode.BLUR -> {
                    // Hyper-efficient native box blur on rotatedRawBitmap
                    val scaleDownFactor = 0.12f
                    val w = (rotatedRawBitmap.width * scaleDownFactor).toInt().coerceAtLeast(1)
                    val h = (rotatedRawBitmap.height * scaleDownFactor).toInt().coerceAtLeast(1)
                    val smallBmp = Bitmap.createScaledBitmap(rotatedRawBitmap, w, h, true)
                    val blurredBmp = Bitmap.createScaledBitmap(smallBmp, rotatedRawBitmap.width, rotatedRawBitmap.height, true)
                    canvas.drawBitmap(blurredBmp, 0f, 0f, null)
                    smallBmp.recycle()
                    blurredBmp.recycle()
                }
                BackgroundMode.IMAGE -> {
                    drawStudioBackground(canvas, RW, RH, OverlayState.bgImageIndex)
                }
                else -> {
                    // Fully transparent, do not draw background layer
                }
            }

            // B: Mask and draw foreground portrait (DST_IN layer masking directly in rotated upright space)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val saveCount = canvas.saveLayer(0f, 0f, RW.toFloat(), RH.toFloat(), null)
            canvas.drawBitmap(rotatedRawBitmap, 0f, 0f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(maskBitmap, null, Rect(0, 0, RW, RH), paint)
            paint.xfermode = null
            canvas.restoreToCount(saveCount)

            // 4. Mirror the final composited frame if front camera is active to match standard orientation
            val finalBitmap = if (OverlayState.isFrontCamera) {
                val matrix = Matrix().apply {
                    postScale(-1f, 1f)
                }
                val mirrored = Bitmap.createBitmap(
                    compositedBitmap, 0, 0, compositedBitmap.width, compositedBitmap.height, matrix, true
                )
                if (mirrored !== compositedBitmap) {
                    compositedBitmap.recycle()
                }
                mirrored
            } else {
                compositedBitmap
            }

            // 5. Update floating window on the UI Thread and recycle previous frame to avoid GC lag
            segmentedImageView.post {
                val oldBitmap = (segmentedImageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                segmentedImageView.setImageBitmap(finalBitmap)
                oldBitmap?.recycle()
            }

            // Memory cleanup to avoid OutOfMemoryError
            rotatedRawBitmap.recycle()
            maskBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close() // Close imageProxy safely in all scenarios
            isProcessingFrame.set(false)
        }
    }

    private fun drawStudioBackground(canvas: Canvas, width: Int, height: Int, index: Int) {
        when (index) {
            0 -> { // Sunset Glow
                val colors = intArrayOf(0xFF7F00FF.toInt(), 0xFFFF007F.toInt(), 0xFFFFFF00.toInt())
                val gradient = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors, null, Shader.TileMode.CLAMP)
                val paint = Paint().apply { shader = gradient }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            1 -> { // Cyber Grid
                canvas.drawColor(0xFF020617.toInt()) // Deep space slate
                val paint = Paint().apply {
                    color = 0xFF0EA5E9.toInt() // sky blue lines
                    strokeWidth = 3f
                    alpha = 50
                }
                val gridSize = width / 12f
                var x = 0f
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), paint)
                    x += gridSize
                }
                var y = 0f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, paint)
                    y += gridSize
                }
            }
            2 -> { // Studio Soft Radial
                val colors = intArrayOf(0xFF334155.toInt(), 0xFF0F172A.toInt())
                val gradient = RadialGradient(width / 2f, height / 2f, Math.max(width, height) / 1.1f, colors, null, Shader.TileMode.CLAMP)
                val paint = Paint().apply { shader = gradient }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private fun updateFloatingLayout() {
        // Adjust dimensions based on portrait or landscape selection
        val isPortrait = OverlayState.isPortrait
        val baseWidth = if (isPortrait) 360 else 640
        val baseHeight = if (isPortrait) 640 else 360
        val scale = OverlayState.scaleFactor

        val targetWidth = (baseWidth * scale).toInt()
        val targetHeight = (baseHeight * scale).toInt()

        // Apply hardware-accelerated alpha directly to the view for ultra-smooth real-time rendering
        overlayView.alpha = OverlayState.opacity

        if (OverlayState.backgroundMode == BackgroundMode.NONE) {
            overlayView.rotation = 0f
        }

        // Only invoke heavy WindowManager updates if the actual dimensions changed
        if (targetWidth != lastWidth || targetHeight != lastHeight) {
            lastWidth = targetWidth
            lastHeight = targetHeight
            windowParams.width = targetWidth
            windowParams.height = targetHeight
            try {
                windowManager.updateViewLayout(overlayView, windowParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        OverlayState.isServiceRunning = false
        OverlayState.notifyChanged(this)
        OverlayState.onConfigChanged = null

        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        segmenter?.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}

// Highly stylized programmatic Icon button for floating toolbar
class CustomIconButton(
    context: Context,
    private val iconType: IconType,
    private val onClick: () -> Unit
) : View(context) {

    enum class IconType {
        CLOSE, ORIENTATION, BACKGROUND, CAMERA, COLLAPSE, EXPAND
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF.toInt() // semi transparent circular button backplate
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    init {
        layoutParams = ViewGroup.LayoutParams(64, 64)
        isClickable = true
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                onClick()
                performClick()
            }
            true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f

        // Draw modern circular container
        canvas.drawCircle(r, r, r - 4, bgPaint)

        when (iconType) {
            IconType.CLOSE -> {
                // Draw bold letter X
                val margin = w * 0.32f
                canvas.drawLine(margin, margin, w - margin, h - margin, strokePaint)
                canvas.drawLine(w - margin, margin, margin, h - margin, strokePaint)
            }
            IconType.ORIENTATION -> {
                // Draw a portrait and landscape overlapping shapes
                val margin = w * 0.28f
                // Outline of phone screens
                canvas.drawRect(margin, margin + 4, w - margin, h - margin - 4, strokePaint)
                canvas.drawLine(margin, r, w - margin, r, strokePaint)
            }
            IconType.BACKGROUND -> {
                // Draw custom sparkles representing filter / background change
                canvas.drawCircle(r, r, 6f, fillPaint)
                canvas.drawCircle(r - 10, r - 6, 4f, fillPaint)
                canvas.drawCircle(r + 8, r + 8, 3f, fillPaint)
            }
            IconType.CAMERA -> {
                // Draw custom minimal camera body
                val cy = r + 2f
                canvas.drawRoundRect(w * 0.22f, cy - 10f, w * 0.78f, cy + 14f, 6f, 6f, strokePaint)
                canvas.drawCircle(r, cy, 7f, strokePaint)
                canvas.drawLine(r - 6f, cy - 10f, r + 6f, cy - 10f, strokePaint) // Flash wedge
            }
            IconType.COLLAPSE -> {
                // Draw chevron wedge pointing left or up
                val margin = w * 0.35f
                val path = Path().apply {
                    moveTo(margin, r + 4f)
                    lineTo(r, r - 4f)
                    lineTo(w - margin, r + 4f)
                }
                canvas.drawPath(path, strokePaint)
            }
            IconType.EXPAND -> {
                // Draw chevron wedge pointing down
                val margin = w * 0.35f
                val path = Path().apply {
                    moveTo(margin, r - 4f)
                    lineTo(r, r + 4f)
                    lineTo(w - margin, r - 4f)
                }
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
