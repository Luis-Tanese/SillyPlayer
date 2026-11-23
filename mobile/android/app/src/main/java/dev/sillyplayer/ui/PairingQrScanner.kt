package dev.sillyplayer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sillyplayer.sync.PairingQrDecoder
import dev.sillyplayer.sync.validPairingQr
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PairingQrScanner(onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(allowed()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
            asked = true
        }
    LaunchedEffect(Unit) { if (!permission && !asked) request.launch(Manifest.permission.CAMERA) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = allowed()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Scan desktop QR",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconAction("x", "Close scanner", onClick = onDismiss)
                }
                if (permission) LivePairingCamera(Modifier.weight(1f), onScanned)
                else
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Allow camera access to scan the desktop pairing QR.")
                        TextButton({ request.launch(Manifest.permission.CAMERA) }) {
                            Text("Allow camera")
                        }
                        TextButton({
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }) {
                            Text("Open app permissions")
                        }
                    }
                Text(
                    "Point at the QR in desktop Settings → Phone sync. Pinch or tap 2× to zoom; tap anywhere to refocus.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onDismiss, Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Paste link instead")
                }
            }
        }
    }
}

@Composable
private fun LivePairingCamera(modifier: Modifier, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val onResult by rememberUpdatedState(onScanned)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var message by remember { mutableStateOf("Starting camera…") }
    var retry by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    // periodically lock center AF/AE onto the viewfinder so bright desktop monitors expose cleanly
    LaunchedEffect(camera) {
        val active = camera ?: return@LaunchedEffect
        while (isActive) {
            if (previewView.width > 0 && previewView.height > 0) {
                val center =
                    previewView.meteringPointFactory.createPoint(
                        previewView.width / 2f,
                        previewView.height / 2f,
                    )
                val action =
                    FocusMeteringAction.Builder(
                            center,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                runCatching { active.cameraControl.startFocusAndMetering(action) }
            }
            delay(2500)
        }
    }

    DisposableEffect(owner, previewView, retry) {
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val disposed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val decoder = PairingQrDecoder()
        val preview =
            Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
        val analysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!disposed.get() && !delivered.get()) {
                    val crop = image.cropRect
                    val plane = image.planes[0]
                    val text =
                        decoder.decode(
                            plane.buffer,
                            crop.width(),
                            crop.height(),
                            plane.rowStride,
                            plane.pixelStride,
                            crop.left,
                            crop.top,
                        )
                    if (text != null) {
                        val link = validPairingQr(text)
                        if (link != null && delivered.compareAndSet(false, true)) {
                            main.execute {
                                if (!disposed.get()) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onResult(link)
                                }
                            }
                        } else if (link == null) {
                            main.execute {
                                if (!disposed.get())
                                    message =
                                        "Use the pairing QR from SillyPlayer's desktop settings."
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                main.execute {
                    if (!disposed.get())
                        message = "Could not read this frame. Hold the QR steady and tap to focus."
                }
            } finally {
                image.close()
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        val observer =
            Observer<CameraState> { state ->
                if (!disposed.get())
                    message =
                        when {
                            state.error != null ->
                                "Camera unavailable. Close other camera apps or try again."
                            state.type == CameraState.Type.OPEN -> "Looking for a SillyPlayer QR…"
                            else -> "Starting camera…"
                        }
            }
        future.addListener(
            {
                if (!disposed.get())
                    try {
                        val p = future.get()
                        provider = p
                        val selector =
                            if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                                CameraSelector.DEFAULT_BACK_CAMERA
                            else CameraSelector.DEFAULT_FRONT_CAMERA
                        val bound = p.bindToLifecycle(owner, selector, preview, analysis)
                        camera = bound
                        val zoomState = bound.cameraInfo.zoomState.value
                        maxZoom = zoomState?.maxZoomRatio ?: 1f
                        zoomRatio = zoomState?.zoomRatio ?: 1f
                        val stateLiveData = bound.cameraInfo.cameraState
                        cameraState = stateLiveData
                        stateLiveData.observe(owner, observer)
                    } catch (_: Exception) {
                        message =
                            "Camera could not start. Check camera permissions or paste the pairing link."
                    }
            },
            main,
        )
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            cameraState?.removeObserver(observer)
            provider?.unbind(preview, analysis)
            camera = null
            executor.shutdown()
        }
    }
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(camera) {
                    detectTapGestures { point ->
                        camera?.let {
                            val focus =
                                previewView.meteringPointFactory.createPoint(point.x, point.y)
                            it.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(
                                        focus,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                                    )
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                            )
                        }
                    }
                }
                .pointerInput(camera, maxZoom) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val active = camera ?: return@detectTransformGestures
                        if (maxZoom <= 1f || zoom == 1f) return@detectTransformGestures
                        val next = (zoomRatio * zoom).coerceIn(1f, minOf(maxZoom, 4f))
                        zoomRatio = next
                        active.cameraControl.setZoomRatio(next)
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val accent = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val side = size.minDimension * .76f
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f
                drawRoundRect(
                    Color.White.copy(alpha = .45f),
                    Offset(left, top),
                    Size(side, side),
                    CornerRadius(14.dp.toPx()),
                    style = Stroke(1.5.dp.toPx()),
                )
                val corner = 26.dp.toPx()
                val stroke = 3.5.dp.toPx()
                // highlight viewfinder corners for quick alignment
                drawLine(accent, Offset(left, top + corner), Offset(left, top), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left, top), Offset(left + corner, top), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left + side - corner, top), Offset(left + side, top), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left + side, top), Offset(left + side, top + corner), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left, top + side - corner), Offset(left, top + side), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left, top + side), Offset(left + corner, top + side), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left + side - corner, top + side), Offset(left + side, top + side), stroke, StrokeCap.Round)
                drawLine(accent, Offset(left + side, top + side - corner), Offset(left + side, top + side), stroke, StrokeCap.Round)
            }
        }
        Text(
            message,
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({
                retry++
                torch = false
                zoomRatio = 1f
            }) {
                Text("Retry camera")
            }
            if (maxZoom >= 1.5f)
                TextButton({
                    val target = if (zoomRatio < 1.5f) minOf(2f, maxZoom) else 1f
                    zoomRatio = target
                    camera?.cameraControl?.setZoomRatio(target)
                }) {
                    Text(if (zoomRatio < 1.5f) "Zoom 2×" else "Zoom 1×")
                }
            if (camera?.cameraInfo?.hasFlashUnit() == true)
                TextButton({
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }) {
                    Text(if (torch) "Light off" else "Light on")
                }
        }
    }
}
