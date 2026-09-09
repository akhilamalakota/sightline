package com.sightline.app.ui

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sightline.app.DetectedObject
import com.sightline.app.Direction
import com.sightline.app.DistanceZone
import com.sightline.app.GoalMode
import com.sightline.app.confidencePercent
import com.sightline.app.distanceDisplay
import com.sightline.app.trafficLightSuffix
import java.util.concurrent.Executors

private const val TAG = "SightlineUI"

@Composable
fun SightlineApp(viewModel: SightlineViewModel) {
    val state by viewModel.uiState.collectAsState()
    val isIdle = state.phase == GoalMode.IDLE

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview (full screen)
        CameraPreview(viewModel)

        // Top gradient scrim (for status text)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
        )

        // Status text (top center, minimal)
        Text(
            text = state.statusMessage,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )

        // Goal badge (top left, small pill)
        AnimatedVisibility(
            visible = !isIdle,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 80.dp)
        ) {
            val goalColor = when (state.phase) {
                GoalMode.FIND -> Color(0xFF2196F3)
                GoalMode.GUIDE -> Color(0xFF4CAF50)
                GoalMode.UNDERSTAND -> Color(0xFF9C27B0)
                GoalMode.REMEMBER -> Color(0xFFFF9800)
                GoalMode.READ -> Color(0xFF00BCD4)
                GoalMode.IDLE -> Color.Gray
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = goalColor.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = state.phase.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Object tags (subtle, small)
        ObjectTagsOverlay(state.detectedObjects)

        // Distance guide line from user to tracked target + metric info
        DistanceGuideOverlay(state.targetLock)

        // Bottom gradient scrim
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
        )

        // Response caption (bottom, toast-style)
        AnimatedVisibility(
            visible = state.lastResponse.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
                .padding(horizontal = 24.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.75f)
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = state.lastResponse,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // Mic button (bottom center, camera app style)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            MicButton(
                isListening = state.isListening,
                onClick = {
                    if (!state.isListening) {
                        viewModel.startListening()
                    } else {
                        viewModel.stopListening()
                    }
                },
            )
        }

        // Debug overlay (tap to toggle)
        if (state.showDebug) {
            DebugOverlay(state, modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp))
        }
    }
}

/**
 * Distance guide: dashed line from the user (bottom-center) to the tracked
 * target, with a metric pill (label · distance · confidence).
 */
@Composable
private fun DistanceGuideOverlay(target: DetectedObject?) {
    if (target == null) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val canvasW = with(density) { maxWidth.toPx() }
        val canvasH = with(density) { maxHeight.toPx() }
        val pillHalfW = with(density) { 80.dp.toPx() }
        val pillTopOffset = with(density) { 32.dp.toPx() }
        val originY = with(density) { canvasH - 110.dp.toPx() }

        val cx = canvasW * ((target.bbox[0] + target.bbox[2]) / 2f).coerceIn(0.04f, 0.96f)
        val cy = canvasH * ((target.bbox[1] + target.bbox[3]) / 2f).coerceIn(0.08f, 0.92f)
        val pillWidth = pillHalfW * 2f
        val pillX = (cx - pillHalfW).coerceIn(0f, canvasW - pillWidth)

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF00E5FF),
                start = Offset(canvasW / 2f, originY),
                end = Offset(cx, cy),
                strokeWidth = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())),
            )
            drawCircle(Color(0xFF00E5FF), radius = 7.dp.toPx(), center = Offset(canvasW / 2f, originY))
            drawCircle(Color(0xFFFF4081), radius = 8.dp.toPx(), center = Offset(cx, cy))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.92f)),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.offset {
                IntOffset(
                    x = pillX.toInt(),
                    y = (canvasH * target.bbox[1] - pillTopOffset).coerceAtLeast(0f).toInt(),
                )
            }
        ) {
            Text(
                text = "${target.label} · ${target.distanceDisplay()} · ${target.confidencePercent()}${target.trafficLightSuffix()}",
                color = Color.Black,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Object tags — one small card per detection, pinned near the object's bbox.
 * Placements are resolved greedily so overlapping boxes never cover each other:
 * each tag gets pushed down past already-placed tags, and a connector line +
 * colored dot links every tag to its object so it stays readable.
 */
@Composable
private fun ObjectTagsOverlay(objects: List<DetectedObject>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val canvasW = with(density) { maxWidth.toPx() }
        val canvasH = with(density) { maxHeight.toPx() }
        val textMeasurer = rememberTextMeasurer()
        val stepY = with(density) { 16.dp.toPx() }
        // Fixed extras around the measured text (dot + spacer + card padding).
        val padH = with(density) { 22.dp.toPx() }
        val padV = with(density) { 6.dp.toPx() }

        data class Placed(val x: Float, val y: Float, val w: Float, val h: Float)

        data class TagPlacement(
            val text: String,
            val color: Color,
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
            val anchorX: Float,
            val anchorY: Float,
        )

        val occupied = mutableListOf<Placed>()
        val placements = mutableListOf<TagPlacement>()

        objects.take(5).forEach { obj ->
            val xFraction = (obj.bbox[0] + obj.bbox[2]) / 2f
            val distanceColor = when (obj.distanceZone) {
                DistanceZone.NEAR -> Color(0xFFFF5252)
                DistanceZone.MID -> Color(0xFFFFD740)
                DistanceZone.FAR -> Color(0xFF69F0AE)
            }

            val text = "${obj.label} · ${obj.distanceDisplay()} · ${obj.confidencePercent()}${obj.trafficLightSuffix()}"
            val measured = textMeasurer.measure(
                text = AnnotatedString(text),
                style = TextStyle(fontSize = 10.sp),
            )
            val tagW = measured.size.width + padH
            val tagH = measured.size.height + padV

            // Anchor point: top-center of the object's bbox, in px.
            val anchorX = canvasW * xFraction
            val anchorY = canvasH * obj.bbox[1]

            var x = (anchorX - tagW / 2f).coerceIn(4f, (canvasW - tagW - 4f).coerceAtLeast(4f))
            var y = (anchorY - tagH).coerceAtLeast(8f)

            // Push down until the tag no longer collides with any placed tag.
            var attempts = 0
            while (attempts++ < 24 && occupied.any { r ->
                    x < r.x + r.w && x + tagW > r.x && y < r.y + r.h && y + tagH > r.y
                }
            ) {
                y += stepY
            }
            occupied.add(Placed(x, y, tagW, tagH))
            placements.add(TagPlacement(text, distanceColor, x, y, tagW, tagH, anchorX, anchorY))
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            placements.forEach { p ->
                drawLine(
                    color = Color.White.copy(alpha = 0.55f),
                    start = Offset(p.x + p.w / 2f, p.y + p.h),
                    end = Offset(p.anchorX, p.anchorY),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawCircle(
                    color = p.color,
                    radius = 3.5.dp.toPx(),
                    center = Offset(p.anchorX, p.anchorY),
                )
            }
        }

        placements.forEach { p ->
            Box(
                modifier = Modifier.offset { IntOffset(p.x.toInt(), p.y.toInt()) },
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(p.color)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = p.text,
                            color = Color.White,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(viewModel: SightlineViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraError by remember { mutableStateOf<String?>(null) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER

                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = androidx.camera.core.Preview.Builder().build().also {
                                it.setSurfaceProvider(surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setTargetResolution(android.util.Size(640, 480))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        try {
                                            viewModel.onCameraFrame(imageProxy)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Frame processing error", e)
                                            imageProxy.close()
                                        }
                                    }
                                }

                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis,
                            )
                            Log.i(TAG, "Camera bound OK")

                            // Read lens/sensor geometry for metric distance estimation.
                            try {
                                val cam2 = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
                                val focal = cam2.getCameraCharacteristic(
                                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )?.get(0) ?: 0f
                                val phys = cam2.getCameraCharacteristic(
                                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                                )
                                val sensorW = phys?.width ?: 0f
                                viewModel.setCameraCalibration(focal, sensorW)
                                Log.i(TAG, "Calibration: focal=${focal}mm sensorW=${sensorW}mm")
                            } catch (e: Exception) {
                                Log.w(TAG, "Calibration read failed", e)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera binding failed", e)
                            cameraError = "Camera failed: ${e.message}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                } catch (e: Exception) {
                    Log.e(TAG, "Camera provider init failed", e)
                    cameraError = "Camera init failed: ${e.message}"
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    cameraError?.let { error ->
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = error, color = Color.Red, fontSize = 14.sp)
        }
    }
}

@Composable
private fun MicButton(
    isListening: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isListening) Color(0xFFE53935) else Color.White
    val iconColor = if (isListening) Color.White else Color.Black

    Button(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
            contentDescription = if (isListening) "Stop" else "Speak",
            modifier = Modifier.size(28.dp),
            tint = iconColor,
        )
    }
}

@Composable
private fun DebugOverlay(
    state: SightlineViewModel.UiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(180.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text("DEBUG", color = Color(0xFF00FF00), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Phase: ${state.phase.name}", color = Color(0xFF00FF00), fontSize = 9.sp)
            Text("Objects: ${state.objectCount}", color = Color(0xFF00FF00), fontSize = 9.sp)
            Text("Path: ${state.pathStatus.name}", color = Color(0xFF00FF00), fontSize = 9.sp)
            state.targetLock?.let { target ->
                Text("Lock: ${target.label}", color = Color(0xFF00FF00), fontSize = 9.sp)
            }
        }
    }
}
