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

    private val NOTIFICATION_ID = 8821
    private val CHANNEL_ID = "camera_overlay_service_channel"

    private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val openAppRunnable = Runnable {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(launchIntent)
        Toast.makeText(this, "Opening App", Toast.LENGTH_SHORT).show()
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
            .enableRawSizeMask() // Extremely fast on-device raw size processing
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

        windowParams = WindowManager.LayoutParams(
            (baseWidth * scale).toInt(),
            (baseHeight * scale).toInt(),
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

        overlayView.setOnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    touchMode = 1 // DRAG
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    // Handle protective 3-second Long Press to open the app safely without accidental gesture launches
                    longPressHandler.removeCallbacks(openAppRunnable)
                    longPressHandler.postDelayed(openAppRunnable, 3000L)

                    // Double Tap to toggle landscape/portrait orientation
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTapTime < 300L) {
                        OverlayState.isPortrait = !OverlayState.isPortrait
                        OverlayState.notifyChanged(this@CameraOverlayService)
                        Toast.makeText(this@CameraOverlayService, "Rotated Screen Aspect", Toast.LENGTH_SHORT).show()
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

                        // Cancel accidental launch on multi-touch
                        longPressHandler.removeCallbacks(openAppRunnable)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // If finger moves significantly, cancel long press (they are dragging or pinching, not pressing statically)
                    val moveDist = Math.hypot((event.rawX - initialTouchX).toDouble(), (event.rawY - initialTouchY).toDouble())
                    if (moveDist > 15) {
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

                        // Rotating fingers by more than 60 degrees flips orientation as well!
                        val newRotation = rotation(event)
                        val angleDiff = newRotation - oldRotation
                        if (Math.abs(angleDiff) > 60f) {
                            OverlayState.isPortrait = !OverlayState.isPortrait
                            OverlayState.notifyChanged(this@CameraOverlayService)
                            oldRotation = newRotation
                            touchMode = 1 // transition back to default
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
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

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            if (OverlayState.backgroundMode == BackgroundMode.NONE) {
                // Background removal is off, display native PreviewView
                previewView.post {
                    previewView.visibility = View.VISIBLE
                    segmentedImageView.visibility = View.GONE
                }
                imageProxy.close()
            } else {
                // Background removal active, analyze and draw to Custom ImageView
                previewView.post {
                    previewView.visibility = View.GONE
                    segmentedImageView.visibility = View.VISIBLE
                }
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    segmenter?.process(inputImage)
                        ?.addOnSuccessListener { mask ->
                            renderSegmentedFrame(imageProxy, mask)
                        }
                        ?.addOnFailureListener {
                            imageProxy.close()
                        }
                        ?.addOnCompleteListener {
                            // ImageProxy closed inside success/failure or here
                        }
                } else {
                    imageProxy.close()
                }
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

            // 2. Process ML Mask into white-gray alpha Map Bitmap (aligned with raw image)
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

            // 3. Composite output canvas matching rawBitmap sizes
            val compositedRawBitmap = Bitmap.createBitmap(rawBitmap.width, rawBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(compositedRawBitmap)

            // A: Draw the chosen background state on the raw canvas size
            when (OverlayState.backgroundMode) {
                BackgroundMode.COLOR -> {
                    canvas.drawColor(OverlayState.backgroundColor)
                }
                BackgroundMode.BLUR -> {
                    // Hyper-efficient native box blur on rawBitmap
                    val scaleDownFactor = 0.12f
                    val w = (rawBitmap.width * scaleDownFactor).toInt().coerceAtLeast(1)
                    val h = (rawBitmap.height * scaleDownFactor).toInt().coerceAtLeast(1)
                    val smallBmp = Bitmap.createScaledBitmap(rawBitmap, w, h, true)
                    val blurredBmp = Bitmap.createScaledBitmap(smallBmp, rawBitmap.width, rawBitmap.height, true)
                    canvas.drawBitmap(blurredBmp, 0f, 0f, null)
                    smallBmp.recycle()
                    blurredBmp.recycle()
                }
                BackgroundMode.IMAGE -> {
                    drawStudioBackground(canvas, rawBitmap.width, rawBitmap.height, OverlayState.bgImageIndex)
                }
                else -> {
                    // Fully transparent, do not draw background layer
                }
            }

            // B: Mask and draw foreground portrait (DST_IN layer masking)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val saveCount = canvas.saveLayer(0f, 0f, rawBitmap.width.toFloat(), rawBitmap.height.toFloat(), null)
            canvas.drawBitmap(rawBitmap, 0f, 0f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawBitmap(maskBitmap, null, Rect(0, 0, rawBitmap.width, rawBitmap.height), paint)
            paint.xfermode = null
            canvas.restoreToCount(saveCount)

            // 4. Rotate and Mirror the final composited frame to match standard orientation
            val rotation = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                if (rotation != 0) {
                    postRotate(rotation.toFloat())
                }
                if (OverlayState.isFrontCamera) {
                    postScale(-1f, 1f)
                }
            }

            val finalBitmap = Bitmap.createBitmap(
                compositedRawBitmap, 0, 0, compositedRawBitmap.width, compositedRawBitmap.height, matrix, true
            )

            // 5. Update floating window on the UI Thread
            segmentedImageView.post {
                segmentedImageView.setImageBitmap(finalBitmap)
            }

            // Memory cleanup to avoid OutOfMemoryError
            rawBitmap.recycle()
            maskBitmap.recycle()
            compositedRawBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close() // Close imageProxy safely in all scenarios
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

        windowParams.width = (baseWidth * scale).toInt()
        windowParams.height = (baseHeight * scale).toInt()
        windowParams.alpha = OverlayState.opacity

        try {
            windowManager.updateViewLayout(overlayView, windowParams)
        } catch (e: Exception) {
            e.printStackTrace()
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
