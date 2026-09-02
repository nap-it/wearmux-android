package com.example.peciwearables

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.integration.WearableService
import com.example.peciwearables.integration.audio.TextToSpeechEngine
import com.example.peciwearables.integration.depth.DepthManager
import com.example.peciwearables.integration.depth.DepthRenderer
import com.example.peciwearables.integration.depth.DepthResult
import com.example.peciwearables.integration.depth.DepthVisualizationMode
import com.example.peciwearables.integration.inference.InferenceManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.example.peciwearables.integration.CameraSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.example.peciwearables.integration.ble.BleDeviceState

private enum class CameraSource { PHONE, GLASSES }
private enum class DepthUIMode { OFF, RELATIVE, ABSOLUTE }

/**
 * Tab Câmara — YOLO + Depth Anything em **pipelines paralelos**.
 *
 * Cada toggle (YOLO / DEPTH) controla um pipeline independente:
 *  - Ambos podem estar ON ao mesmo tempo — partilham os mesmos frames mas
 *    fazem inferência em executores próprios (AtomicBoolean impede racing).
 *  - URL do servidor cloud configurado em `Settings → Processamento → Detecção`.
 *  - Suporta câmara do **telemóvel** (CameraX) ou **óculos** (stream BLE/UDP).
 *
 * Bounding boxes YOLO + colormap Depth são desenhados em layers sobre a
 * preview da câmara.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val detections = remember { mutableStateOf<List<Detection>>(emptyList()) }
    val depthResult = remember { mutableStateOf<DepthResult?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val fallbackInferenceManager = remember { InferenceManager(context) }
    val inferenceManager = WearableService.instance?.glassesInferenceManager ?: fallbackInferenceManager
    // DepthManager singleton do serviço (partilhado com UC1.2 a correr no service).
    val depthManager = remember {
        WearableService.instance?.depthManager ?: DepthManager()
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val scope = rememberCoroutineScope()
    val phoneInferenceInFlight = remember { AtomicBoolean(false) }
    val phoneDepthInFlight = remember { AtomicBoolean(false) }

    val depthVisualizationMode by depthManager.visualizationMode.collectAsState()
    val metersPerUnit by depthManager.metersPerUnit.collectAsState()
    val latestCameraBitmap by viewModel.latestCameraBitmap.collectAsStateWithLifecycle()
    val whisperConnected by viewModel.whisperConnected.collectAsStateWithLifecycle()
    val lastWhisperText by viewModel.lastWhisperText.collectAsStateWithLifecycle()
    val esp32State by viewModel.esp32State.collectAsStateWithLifecycle()
    val glassesStateForStream by viewModel.glassesState.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()

    val uc41Tts = remember(context) { TextToSpeechEngine(context, Locale.ENGLISH) }
    val uc41Enabled by viewModel.uc41Enabled.collectAsStateWithLifecycle()

    var cameraSource by remember { mutableStateOf(CameraSource.PHONE) }
    var yoloEnabled by remember { mutableStateOf(false) }
    var depthUIMode by remember { mutableStateOf(DepthUIMode.OFF) }
    // Keep as state-backed delegate so the imageAnalysis closure always reads the current value.
    var depthEnabled by remember { mutableStateOf(false) }
    var colormapEnabled by remember { mutableStateOf(true) }

    val isActive = remember { mutableStateOf(true) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val displayMetrics = context.resources.displayMetrics
    val screenW = displayMetrics.widthPixels.toFloat()
    val screenH = displayMetrics.heightPixels.toFloat()
    val density = LocalDensity.current

    // UC4.1 — snapshot narration: objects must be stable for 2 s; narrate every 10 s max.
    LaunchedEffect(uc41Enabled) {
        if (!uc41Enabled) return@LaunchedEffect
        val firstSeen = mutableMapOf<String, Long>()
        var lastDesc = ""
        var lastNarrationMs = 0L
        while (true) {
            delay(500L)
            val now = System.currentTimeMillis()
            val current = detections.value
            val currentLabels = current.map { it.label }.toSet()
            firstSeen.keys.retainAll(currentLabels)
            currentLabels.forEach { label -> if (label !in firstSeen) firstSeen[label] = now }

            if (now - lastNarrationMs >= 5_000L) {
                val stable = current.filter { det ->
                    (now - (firstSeen[det.label] ?: now)) >= 2_000L
                }
                if (stable.isNotEmpty()) {
                    val desc = buildSceneDescription(stable, depthResult.value, metersPerUnit)
                    if (desc.isNotEmpty() && desc != lastDesc) {
                        uc41Tts.speak(desc, flush = true)
                        lastDesc = desc
                        lastNarrationMs = now
                    }
                }
            }
        }
    }

    // Sync depth UI mode → depthEnabled state + manager viz mode.
    LaunchedEffect(depthUIMode) {
        depthEnabled = depthUIMode != DepthUIMode.OFF
        when (depthUIMode) {
            DepthUIMode.RELATIVE -> depthManager.setVisualizationMode(DepthVisualizationMode.RELATIVE)
            DepthUIMode.ABSOLUTE -> depthManager.setVisualizationMode(DepthVisualizationMode.ABSOLUTE)
            DepthUIMode.OFF -> depthResult.value = null
        }
    }

    // Colormap bitmap — only rendered when colormap overlay is enabled.
    val depthBitmap = remember(depthResult.value, colormapEnabled) {
        if (colormapEnabled) depthResult.value?.let { DepthRenderer.toColorBitmap(it) } else null
    }

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
        if (!micPermission.status.isGranted) micPermission.launchPermissionRequest()
    }

    LaunchedEffect(cameraSource) {
        WearableService.updateCameraSource(cameraSource)
        if (cameraSource == CameraSource.GLASSES) {
            cameraProvider?.unbindAll()
            if (!yoloEnabled || latestCameraBitmap == null) detections.value = emptyList()
            if (!depthEnabled || latestCameraBitmap == null) depthResult.value = null
        }
    }

    // YOLO pipeline para frames dos óculos.
    LaunchedEffect(cameraSource, yoloEnabled) {
        if (cameraSource != CameraSource.GLASSES || !yoloEnabled) {
            detections.value = emptyList()
            return@LaunchedEffect
        }
        viewModel.latestCameraBitmap.collectLatest { frame ->
            if (frame == null) { detections.value = emptyList(); return@collectLatest }
            val result = runCatching { inferenceManager.detect(frame) }
                .onFailure { Log.e("CameraScreen", "Glasses YOLO error: ${it.message}") }
                .getOrDefault(emptyList())
            if (isActive.value && cameraSource == CameraSource.GLASSES) detections.value = result
        }
    }

    // Depth pipeline para frames dos óculos.
    LaunchedEffect(cameraSource, depthEnabled) {
        if (cameraSource != CameraSource.GLASSES || !depthEnabled) {
            depthResult.value = null
            return@LaunchedEffect
        }
        viewModel.latestCameraBitmap.collectLatest { frame ->
            if (frame == null) { depthResult.value = null; return@collectLatest }
            val result = runCatching { depthManager.estimateDepth(frame) }
                .onFailure { Log.e("CameraScreen", "Glasses depth error: ${it.message}") }
                .getOrNull()
            if (isActive.value && cameraSource == CameraSource.GLASSES) depthResult.value = result
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isActive.value = false
            cameraProvider?.unbindAll()
            executor.shutdown()
            executor.awaitTermination(500, TimeUnit.MILLISECONDS)
            if (inferenceManager === fallbackInferenceManager) inferenceManager.close()
            uc41Tts.shutdown()
        }
    }

    if (cameraSource == CameraSource.PHONE && !cameraPermission.status.isGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Permissão de câmara necessária", color = Color.White)
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (cameraSource) {
            CameraSource.PHONE -> {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val future = ProcessCameraProvider.getInstance(ctx)

                        future.addListener({
                            val provider = future.get()
                            cameraProvider = provider

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setTargetResolution(android.util.Size(640, 640))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()

                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                try {
                                    if (!isActive.value) return@setAnalyzer
                                    val needBitmap = yoloEnabled || depthEnabled
                                    val bitmap = if (needBitmap) imageProxy.toBitmap() else null
                                    if (bitmap != null) {
                                        com.example.peciwearables.integration.WearableService.updatePhoneCameraBitmap(bitmap)
                                    }

                                    if (yoloEnabled && bitmap != null &&
                                        phoneInferenceInFlight.compareAndSet(false, true)
                                    ) {
                                        scope.launch(Dispatchers.Default) {
                                            try {
                                                val result = inferenceManager.detect(bitmap)
                                                if (isActive.value) mainHandler.post {
                                                    if (isActive.value) detections.value = result
                                                }
                                            } finally { phoneInferenceInFlight.set(false) }
                                        }
                                    } else if (!yoloEnabled) {
                                        detections.value = emptyList()
                                    }

                                    if (depthEnabled && bitmap != null &&
                                        phoneDepthInFlight.compareAndSet(false, true)
                                    ) {
                                        scope.launch(Dispatchers.Default) {
                                            try {
                                                val result = depthManager.estimateDepth(bitmap)
                                                if (isActive.value) mainHandler.post {
                                                    if (isActive.value) depthResult.value = result
                                                }
                                            } finally { phoneDepthInFlight.set(false) }
                                        }
                                    } else if (!depthEnabled) {
                                        depthResult.value = null
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Frame analysis error: ${e.message}")
                                    phoneInferenceInFlight.set(false)
                                    phoneDepthInFlight.set(false)
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                            )
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (depthEnabled) {
                    depthBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                    depthResult.value?.let { dr ->
                        DepthLabels(dr, depthVisualizationMode, metersPerUnit, screenW, screenH, density)
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (yoloEnabled) detections.value.forEach { det ->
                        val rect = det.toPixelRect(size.width, size.height)
                        drawRect(
                            color = if (det.label == "person") Color.Green else Color.Red,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.width, rect.height),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
                if (yoloEnabled) detections.value.forEach { det ->
                    val xPx = det.left * screenW
                    val yPx = det.top * screenH
                    val depthSuffix = if (depthEnabled) depthResult.value?.let { dr ->
                        val d = dr.sampleAt((det.left + det.right) / 2f, (det.top + det.bottom) / 2f)
                        when (depthVisualizationMode) {
                            DepthVisualizationMode.ABSOLUTE -> " ${"%.1f".format(d * metersPerUnit)}m"
                            DepthVisualizationMode.RELATIVE -> " ${"%.2f".format(d)}"
                        }
                    } ?: "" else ""
                    with(density) {
                        Box(modifier = Modifier.offset(x = xPx.toDp(), y = yPx.toDp()).padding(2.dp)) {
                            Text(
                                text = "${det.label} ${"%.0f".format(det.confidence * 100)}%$depthSuffix",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            CameraSource.GLASSES -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF111111)),
                    contentAlignment = Alignment.Center
                ) {
                    if (latestCameraBitmap != null) {
                        Image(
                            bitmap = latestCameraBitmap!!.asImageBitmap(),
                            contentDescription = "Preview câmara dos óculos",
                            modifier = Modifier
                                .fillMaxSize()
                                .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.FillBounds
                        )
                        if (depthEnabled) {
                            depthBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                            }
                            depthResult.value?.let { dr ->
                                DepthLabels(dr, depthVisualizationMode, metersPerUnit, screenW, screenH, density)
                            }
                        }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            if (yoloEnabled) detections.value.forEach { det ->
                                val rect = det.toPixelRect(size.width, size.height)
                                drawRect(
                                    color = if (det.label == "person") Color.Green else Color.Red,
                                    topLeft = Offset(rect.left, rect.top),
                                    size = Size(rect.width, rect.height),
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }
                        }
                        if (isStreaming) {
                            Button(
                                onClick = { viewModel.stopStream() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                Text("Parar Stream", color = Color.White)
                            }
                        }
                    } else {
                        val glassesReadyForStream = glassesStateForStream == BleDeviceState.READY ||
                            glassesStateForStream == BleDeviceState.CONNECTED
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Sem imagem dos óculos", color = Color.White, fontSize = 16.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Liga o stream dos óculos para ver aqui", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))

                            when {
                                // Óculos (Omi) ligados via BLE/Wi-Fi — caso mais comum.
                                // O stream é o mesmo `startStream()` já usado noutros
                                // ecrãs de vídeo dos óculos; aqui faltava esta opção: o
                                // bloco antigo só considerava esp32State, nunca glassesState.
                                glassesReadyForStream -> {
                                    Button(
                                        onClick = { viewModel.startStream() },
                                        enabled = !isStreaming,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))
                                    ) {
                                        Text(
                                            if (isStreaming) "A iniciar stream…" else "Iniciar Stream (Óculos)",
                                            color = Color.White,
                                        )
                                    }
                                }
                                // ESP32-CAM ligado (dispositivo manual, sem os óculos).
                                esp32State == BleDeviceState.READY || esp32State == BleDeviceState.CONNECTED -> {
                                    Button(
                                        onClick = { viewModel.startStream() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))
                                    ) {
                                        Text("Iniciar Stream Firmware", color = Color.White)
                                    }
                                }
                                // Nenhum dos dois ligado.
                                else -> {
                                    Button(
                                        onClick = { viewModel.connectEsp32() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2))
                                    ) {
                                        Text("Ligar ESP32-CAM", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Toggles (top-end) — fonte de câmara + YOLO ON/OFF + DEPTH ON/OFF.
        // Configuração detalhada (URL, modo, calibração) fica no
        // Settings → Processamento → Detecção.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (cameraSource == CameraSource.PHONE)
                    Color(0xFF8BC34A).copy(alpha = 0.88f) else Color(0xFF7E57C2).copy(alpha = 0.88f),
                onClick = {
                    cameraSource =
                        if (cameraSource == CameraSource.PHONE) CameraSource.GLASSES else CameraSource.PHONE
                }
            ) {
                Text(
                    text = if (cameraSource == CameraSource.PHONE) "Telemóvel" else "Óculos",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (yoloEnabled) Color(0xFF26A69A).copy(alpha = 0.9f) else Color(0xFF616161).copy(alpha = 0.9f),
                onClick = { yoloEnabled = !yoloEnabled; if (!yoloEnabled) detections.value = emptyList() }
            ) {
                Text(
                    text = if (yoloEnabled) "YOLO ON" else "YOLO OFF",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when (depthUIMode) {
                    DepthUIMode.OFF -> Color(0xFF616161).copy(alpha = 0.9f)
                    DepthUIMode.RELATIVE -> Color(0xFFFF7043).copy(alpha = 0.9f)
                    DepthUIMode.ABSOLUTE -> Color(0xFFFFA726).copy(alpha = 0.9f)
                },
                onClick = {
                    depthUIMode = when (depthUIMode) {
                        DepthUIMode.OFF -> DepthUIMode.RELATIVE
                        DepthUIMode.RELATIVE -> DepthUIMode.ABSOLUTE
                        DepthUIMode.ABSOLUTE -> DepthUIMode.OFF
                    }
                }
            ) {
                Text(
                    text = when (depthUIMode) {
                        DepthUIMode.OFF -> "DEPTH OFF"
                        DepthUIMode.RELATIVE -> "DEPTH REL"
                        DepthUIMode.ABSOLUTE -> "DEPTH ABS"
                    },
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (depthEnabled) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (colormapEnabled) Color(0xFF5C6BC0).copy(alpha = 0.9f) else Color(0xFF616161).copy(alpha = 0.9f),
                    onClick = { colormapEnabled = !colormapEnabled }
                ) {
                    Text(
                        text = if (colormapEnabled) "Map ON" else "Map OFF",
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (whisperConnected && lastWhisperText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
            ) {
                Text(
                    text = "🎙 $lastWhisperText",
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun buildSceneDescription(
    detections: List<Detection>,
    depth: DepthResult?,
    metersPerUnit: Float,
): String {
    if (detections.isEmpty()) return ""

    data class Key(val label: String, val position: String, val distance: String)

    val grouped = detections.groupBy { det ->
        val cx = (det.left + det.right) / 2f
        val cy = (det.top + det.bottom) / 2f
        val position = when {
            cx < 0.35f -> "on your left"
            cx > 0.65f -> "on your right"
            else -> "ahead"
        }
        val distance = if (depth != null) {
            val meters = depth.sampleAt(cx, cy) * metersPerUnit
            when {
                meters < 5f -> "close"
                meters < 15f -> "at medium distance"
                else -> "far"
            }
        } else {
            val h = det.bottom - det.top
            when {
                h > 0.5f -> "close"
                h > 0.2f -> "at medium distance"
                else -> "far"
            }
        }
        Key(det.label, position, distance)
    }

    return grouped.entries.joinToString(", ") { (key, dets) ->
        val count = dets.size
        val countWord = if (count == 1) "one" else count.toString()
        val noun = if (count == 1) key.label else pluralizeLabel(key.label)
        "$countWord $noun ${key.distance} ${key.position}"
    }
}

private fun pluralizeLabel(label: String) = when (label) {
    "person" -> "people"
    "bus" -> "buses"
    else -> "${label}s"
}

@Composable
private fun DepthLabels(
    result: DepthResult,
    mode: DepthVisualizationMode,
    metersPerUnit: Float,
    screenW: Float,
    screenH: Float,
    density: androidx.compose.ui.unit.Density,
) {
    val samples = remember(result) { result.sampleGrid(cols = 3, rows = 3) }
    samples.forEach { (nx, ny, d) ->
        val label = when (mode) {
            DepthVisualizationMode.RELATIVE -> "%.2f".format(d)
            DepthVisualizationMode.ABSOLUTE -> "${"%.1f".format(d * metersPerUnit)}m"
        }
        with(density) {
            Box(modifier = Modifier.offset(x = (nx * screenW).toDp(), y = (ny * screenH).toDp())) {
                Text(
                    text = label,
                    color = Color.White, fontSize = 11.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}
