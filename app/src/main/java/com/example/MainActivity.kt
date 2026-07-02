package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LiquidGlassBackground()
                    
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        topBar = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .height(64.dp)
                                    .background(Color(0x1F000000)), // subtle frosted top bar
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "FLOATING CAMERA",
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    fontSize = 20.sp,
                                    color = Color.White
                                )
                            }
                        }
                    ) { innerPadding ->
                        DashboardScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Force refresh state variables when returning from overlay settings screen
        OverlayState.notifyChanged(this)
    }
}

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Permission States
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    var isServiceRunning by remember {
        mutableStateOf(OverlayState.isServiceRunning)
    }

    // Dynamic state trackers
    var backgroundMode by remember { mutableStateOf(OverlayState.backgroundMode) }
    var isPortrait by remember { mutableStateOf(OverlayState.isPortrait) }
    var isFrontCamera by remember { mutableStateOf(OverlayState.isFrontCamera) }
    var scaleFactor by remember { mutableStateOf(OverlayState.scaleFactor) }
    var opacity by remember { mutableStateOf(OverlayState.opacity) }
    var selectedColorIndex by remember { mutableStateOf(0) }
    var selectedBgIndex by remember { mutableStateOf(OverlayState.bgImageIndex) }

    // Register listeners
    LaunchedEffect(Unit) {
        OverlayState.onConfigChanged = {
            isServiceRunning = OverlayState.isServiceRunning
            backgroundMode = OverlayState.backgroundMode
            isPortrait = OverlayState.isPortrait
            isFrontCamera = OverlayState.isFrontCamera
            scaleFactor = OverlayState.scaleFactor
            opacity = OverlayState.opacity
            selectedBgIndex = OverlayState.bgImageIndex
        }
    }

    // Setup permission launcher
    val requestCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Camera permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Color list
    val presetColors = listOf(
        0xFF00FF00 to "Chroma Green",
        0xFFFF007F to "Neon Magenta",
        0xFF007FFF to "Electric Azure",
        0xFF1E293B to "Charcoal Slate"
    )

    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // --- SECTION 1: SYSTEM PERMISSION CARD ---
        val allPermissionsGranted = hasCameraPermission && hasOverlayPermission && hasNotificationPermission

        if (!allPermissionsGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x1FEF4444) // semi-transparent error red
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, "Permission Warning", tint = Color(0xFFEF4444))
                            Text(
                                "Permissions Required",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFCA5A5),
                                fontSize = 16.sp
                            )
                        }

                        Text(
                            "The floating camera overlay requires system access to display over other applications.",
                            color = Color(0xFFFECACA),
                            fontSize = 13.sp
                        )

                        // Camera Grant
                        PermissionRow(
                            title = "Camera Access",
                            isGranted = hasCameraPermission,
                            onGrantClick = {
                                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        )

                        // Notification Grant
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionRow(
                                title = "Notifications (For service keep-alive)",
                                isGranted = hasNotificationPermission,
                                onGrantClick = {
                                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            )
                        }

                        // Overlay Grant
                        PermissionRow(
                            title = "Draw Over Other Apps",
                            isGranted = hasOverlayPermission,
                            onGrantClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(context, "Please enable 'Draw Over Other Apps' for Floating Camera", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            }
        }

        // --- SECTION 2: LIVE SERVICE STATUS HERO ---
        item {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x1F1E293B) // Frosted glass dark translucency
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = if (isServiceRunning) {
                                listOf(Color(0xFF30D158), Color(0x1F30D158)) // iOS Green glowing border
                            } else {
                                listOf(Color(0x33FFFFFF), Color(0x05FFFFFF)) // White frosted glowing border
                            }
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status Badge with pulse
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .scale(if (isServiceRunning) pulseScale else 1.0f)
                                .background(
                                    if (isServiceRunning) Color(0xFF30D158) else Color(0x8CFFFFFF),
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (isServiceRunning) "OVERLAY FEED ACTIVE" else "OVERLAY DISCONNECTED",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp,
                            color = if (isServiceRunning) Color(0xFF30D158) else Color(0x8CFFFFFF)
                        )
                    }

                    Text(
                        text = if (isServiceRunning) {
                            "The floating window is running over your screen. You can drag and resize it at any time!"
                        } else {
                            "Ready to start. Tap the button below to project your camera onto a floating draggable bubble!"
                        },
                        color = Color(0xBFEEEEEE),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Button(
                        onClick = {
                            if (!allPermissionsGranted) {
                                Toast.makeText(context, "Please grant all permissions first!", Toast.LENGTH_LONG).show()
                                return@Button
                            }

                            if (isServiceRunning) {
                                // Stop Service
                                context.stopService(Intent(context, CameraOverlayService::class.java))
                                isServiceRunning = false
                                OverlayState.isServiceRunning = false
                            } else {
                                // Start Service in default transparent cutout mode
                                OverlayState.backgroundMode = BackgroundMode.TRANSPARENT
                                backgroundMode = BackgroundMode.TRANSPARENT
                                val intent = Intent(context, CameraOverlayService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                isServiceRunning = true
                                OverlayState.isServiceRunning = true
                            }
                            OverlayState.notifyChanged(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isServiceRunning) Color(0xFFFF453A) else Color(0xFF0A84FF) // iOS Red / iOS Blue
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .testTag("service_toggle_button")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isServiceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isServiceRunning) "Stop Overlay" else "Start Overlay"
                            )
                            Text(
                                text = if (isServiceRunning) "DISCONNECT OVERLAY" else "LAUNCH FLOATING OVERLAY",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // --- SECTION 3: VIDEO SETTINGS CONFIG ---
        item {
            Text(
                "CAMERA HARDWARE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color(0xFF0A84FF), // iOS Premium Blue
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Orientation selection card
                OptionCard(
                    title = "Aspect Ratio",
                    valueText = if (isPortrait) "Portrait (9:16)" else "Landscape (16:9)",
                    icon = if (isPortrait) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
                    isSelected = true,
                    onClick = {
                        OverlayState.isPortrait = !OverlayState.isPortrait
                        isPortrait = OverlayState.isPortrait
                        OverlayState.notifyChanged()
                    },
                    modifier = Modifier.weight(1f)
                )

                // Lens selector
                OptionCard(
                    title = "Lens Position",
                    valueText = if (isFrontCamera) "Front Camera" else "Rear Camera",
                    icon = Icons.Default.FlipCameraAndroid,
                    isSelected = true,
                    onClick = {
                        OverlayState.isFrontCamera = !OverlayState.isFrontCamera
                        isFrontCamera = OverlayState.isFrontCamera
                        OverlayState.notifyChanged()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // --- SECTION 4: BACKGROUND REMOVAL / CHROMA KEY ---
        item {
            Text(
                "SEGMENTATION & BACKGROUND",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color(0xFF0A84FF), // iOS Premium Blue
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        // Horizontal Background Mode Selectors
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x1F1E293B) // Frosted glass dark translucency
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x05FFFFFF))),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Background Removal Mode",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    // Vertical options of modes
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BackgroundModeRow(
                            mode = BackgroundMode.NONE,
                            title = "None (Full Camera Feed)",
                            desc = "No segmentation. Low power consumption.",
                            isSelected = backgroundMode == BackgroundMode.NONE,
                            onClick = {
                                OverlayState.backgroundMode = BackgroundMode.NONE
                                backgroundMode = BackgroundMode.NONE
                                OverlayState.notifyChanged()
                            }
                        )

                        BackgroundModeRow(
                            mode = BackgroundMode.TRANSPARENT,
                            title = "Transparent cutout",
                            desc = "Full transparency backdrop, person cut-out floats over other apps.",
                            isSelected = backgroundMode == BackgroundMode.TRANSPARENT,
                            onClick = {
                                OverlayState.backgroundMode = BackgroundMode.TRANSPARENT
                                backgroundMode = BackgroundMode.TRANSPARENT
                                OverlayState.notifyChanged()
                            }
                        )

                        BackgroundModeRow(
                            mode = BackgroundMode.BLUR,
                            title = "Cinematic Blur",
                            desc = "Bilinear downscaled bokeh effect backdrop.",
                            isSelected = backgroundMode == BackgroundMode.BLUR,
                            onClick = {
                                OverlayState.backgroundMode = BackgroundMode.BLUR
                                backgroundMode = BackgroundMode.BLUR
                                OverlayState.notifyChanged()
                            }
                        )

                        BackgroundModeRow(
                            mode = BackgroundMode.COLOR,
                            title = "Solid Chroma Key Color",
                            desc = "Useful for streaming, recording, or virtual green-screening.",
                            isSelected = backgroundMode == BackgroundMode.COLOR,
                            onClick = {
                                OverlayState.backgroundMode = BackgroundMode.COLOR
                                backgroundMode = BackgroundMode.COLOR
                                OverlayState.notifyChanged()
                            }
                        )

                        BackgroundModeRow(
                            mode = BackgroundMode.IMAGE,
                            title = "Studio Backdrop Designs",
                            desc = "Aesthetic generative programmatic shapes.",
                            isSelected = backgroundMode == BackgroundMode.IMAGE,
                            onClick = {
                                OverlayState.backgroundMode = BackgroundMode.IMAGE
                                backgroundMode = BackgroundMode.IMAGE
                                OverlayState.notifyChanged()
                            }
                        )
                    }

                    // Expandable Details (Color Preset Picker)
                    AnimatedVisibility(visible = backgroundMode == BackgroundMode.COLOR) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Divider(color = Color(0x11FFFFFF))
                            Text(
                                "Chroma presets",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                presetColors.forEachIndexed { idx, pair ->
                                    val colorObj = Color(pair.first)
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(colorObj)
                                            .border(
                                                width = if (selectedColorIndex == idx) 3.dp else 0.dp,
                                                color = Color.White,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                selectedColorIndex = idx
                                                OverlayState.backgroundColor = pair.first.toInt()
                                                OverlayState.notifyChanged()
                                            }
                                    )
                                }
                            }
                        }
                    }

                    // Expandable Details (Backdrop selection)
                    AnimatedVisibility(visible = backgroundMode == BackgroundMode.IMAGE) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Divider(color = Color(0x11FFFFFF))
                            Text(
                                "Abstract backdrops",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf("Sunset Glow", "Space Grid", "Studio Gray").forEachIndexed { idx, text ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (selectedBgIndex == idx) Color(0xFF0EA5E9) else Color(0x11FFFFFF)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (selectedBgIndex == idx) Color.White else Color(0x11FFFFFF),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                selectedBgIndex = idx
                                                OverlayState.bgImageIndex = idx
                                                OverlayState.notifyChanged()
                                            }
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (selectedBgIndex == idx) Color.White else Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- SECTION 6: FLOATING OPACITY CONTROLLER ---
        item {
            Text(
                "TRANSPARENCY & OPACITY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = Color(0xFF0A84FF), // iOS Premium Blue
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x1F1E293B) // Frosted glass dark translucency
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x05FFFFFF))),
                        shape = RoundedCornerShape(24.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Overlay Opacity",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            "${(opacity * 100).toInt()}%",
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF30D158), // iOS System Green
                            fontSize = 14.sp
                        )
                    }

                    Slider(
                        value = opacity,
                        onValueChange = {
                            opacity = it
                            OverlayState.opacity = it
                            OverlayState.notifyChanged()
                        },
                        valueRange = 0.10f..1.0f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF30D158), // iOS System Green
                            activeTrackColor = Color(0xFF30D158),
                            inactiveTrackColor = Color(0x22FFFFFF)
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Translucent (10%)", fontSize = 11.sp, color = Color(0xFF64748B))
                        Text("Opaque (100%)", fontSize = 11.sp, color = Color(0xFF64748B))
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x11FFFFFF))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Text(
                if (isGranted) "Permission Granted" else "Action Required",
                color = if (isGranted) Color(0xFF10B981) else Color(0xFFF87171),
                fontSize = 11.sp
            )
        }

        if (isGranted) {
            Icon(Icons.Default.CheckCircle, "Granted", tint = Color(0xFF10B981))
        } else {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OptionCard(
    title: String,
    valueText: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0x1F0A84FF) else Color(0x0AFFFFFF) // Frosted Blue or Frosted White
        ),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                brush = if (isSelected) {
                    Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0x330A84FF)))
                } else {
                    Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x05FFFFFF)))
                },
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) Color(0xFF0A84FF) else Color(0xFF8E8E93)
            )
            Text(title, fontSize = 11.sp, color = Color(0xFF8E8E93))
            Text(valueText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun BackgroundModeRow(
    mode: BackgroundMode,
    title: String,
    desc: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isSelected) Color(0x120A84FF) else Color(0x05FFFFFF))
            .border(
                width = 1.dp,
                brush = if (isSelected) {
                    Brush.linearGradient(listOf(Color(0xFF0A84FF), Color(0x1A0A84FF)))
                } else {
                    Brush.linearGradient(listOf(Color(0x15FFFFFF), Color(0x03FFFFFF)))
                },
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF0A84FF),
                unselectedColor = Color(0x33FFFFFF)
            )
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = if (isSelected) Color.White else Color(0xFFE2E8F0)
            )
            Text(
                desc,
                fontSize = 11.sp,
                color = if (isSelected) Color(0xBF0A84FF) else Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
fun LiquidGlassBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B19)) // Deepest space black/slate
    ) {
        // Blob 1: iOS Neon Pink/Red glow in top-right
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(450.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x2BFF2D55), Color(0x00FF2D55)),
                        radius = 1000f
                    )
                )
        )
        // Blob 2: iOS Neon Blue/Cyan glow in bottom-left
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x221AD6FF), Color(0x001AD6FF)),
                        radius = 1100f
                    )
                )
        )
        // Blob 3: Purple/Violet ambient center glow
        Box(
            modifier = Modifier
                .size(380.dp)
                .align(Alignment.Center)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x1EA855F7), Color(0x00A855F7)),
                        radius = 800f
                    )
                )
        )
        // Tinted overlay for extra frosted aesthetic depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x0A000000))
        )
    }
}
