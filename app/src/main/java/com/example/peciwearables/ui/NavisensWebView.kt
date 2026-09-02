package com.example.peciwearables.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.peciwearables.R
import com.example.peciwearables.integration.protocol.GlassesImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NavisensWebView(
    modifier: Modifier = Modifier,
    glassesImuFlow: SharedFlow<GlassesImuSample>? = null,
    onMotion: (lat: Double?, lon: Double?, heading: Double, motionType: String, stepCount: Int) -> Unit = { _, _, _, _, _ -> },
    onError: (String) -> Unit = {},
    onStarted: () -> Unit = {},
    onStopped: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    var isRunning by remember { mutableStateOf(false) }
    var pageStatus by remember { mutableStateOf("loading") }
    var pageError by remember { mutableStateOf<String?>(null) }
    var sdkRecvCount by remember { mutableStateOf(0) }
    val pathPoints = remember { mutableStateListOf<Offset>() }
    var drawPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var lastHeadingRad by remember { mutableStateOf(0f) }
    var headingOffsetRad by remember { mutableStateOf(0f) }
    var lastMotion by remember { mutableStateOf("—") }
    val navisensImuOut by NavisensTelemetry.latestImuOut.collectAsState()
    val developerKey = remember(context) { context.getString(R.string.navisens_developer_key) }
    val maxPoints = 3000

    
    val webView = remember(context) {
        buildNavisensWebView(
            ctx = context,
            developerKey = developerKey,
            onMotion = { x, y, heading, motionType, stepCount ->
                sdkRecvCount += 1
                if (x != null && y != null) {
                    val pt = Offset(x.toFloat(), y.toFloat())
                    pathPoints.add(pt)
                    if (pathPoints.size > maxPoints) {
                        pathPoints.removeAt(0)
                    }
                    drawPoints = pathPoints.toList()
                }
                if (heading.isFinite()) {
                    lastHeadingRad = heading.toFloat()
                }
                lastMotion = motionType.ifBlank { lastMotion }
                onMotion(x, y, heading, motionType, stepCount)
            },
            onError = onError,
            onStarted = { isRunning = true; onStarted() },
            onStopped = { isRunning = false; onStopped() },
            onPageStatus = { status, message ->
                pageStatus = status
                pageError = message
            },
        )
    }

    // Wire lifecycle → WebView pause/resume so rendering doesn't get stuck
    // in the "detached, still showing white" state after a tab change.
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    webView.resumeTimers()
                    webView.invalidate()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Glasses IMU stream → WebView bridge. Re-subscribes if either changes.
    LaunchedEffect(glassesImuFlow, webView) {
        if (glassesImuFlow == null) return@LaunchedEffect
        launch {
            
            val accelGravityCorrection = 9.81 / 24.8
            glassesImuFlow.collect { s ->
                val nowNs = SystemClock.elapsedRealtimeNanos()
                NavisensImuBridge.markGlassesSample(nowNs)
                val tMs = System.currentTimeMillis()
                val gx = s.gx.toDouble()
                val gy = -s.gz.toDouble()
                val gz = s.gy.toDouble()
                val ax = -s.ax.toDouble() * accelGravityCorrection
                val ay = s.az.toDouble() * accelGravityCorrection
                val az = -s.ay.toDouble() * accelGravityCorrection
                val mx = s.mx.toDouble() * 10.0
                val my = s.my.toDouble() * 10.0
                val mz = s.mz.toDouble() * 10.0
                NavisensTelemetry.update(
                    NavisensImuOut(
                        source = "glasses",
                        deviceMode = "headmounted",
                        timestampMs = tMs,
                        gx = gx,
                        gy = gy,
                        gz = gz,
                        ax = ax,
                        ay = ay,
                        az = az,
                        mx = mx,
                        my = my,
                        mz = mz,
                    )
                )
                val js = "window.__peciSetDeviceMode && window.__peciSetDeviceMode('headmounted');" +
                    "window.__peciImuSample && window.__peciImuSample(" +
                    "$tMs,$gx,$gy,$gz,$ax,$ay,$az,$mx,$my,$mz);"
                webView.post { webView.evaluateJavascript(js, null) }
            }
        }
    }


    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    webView.post {
                        val js = if (isRunning) {
                            "window.__peciStopTrajectory && window.__peciStopTrajectory();"
                        } else {
                            "window.__peciStartTrajectory && window.__peciStartTrajectory();"
                        }
                        webView.evaluateJavascript(js, null)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isRunning) "Parar gravacao" else "Gravar trajeto")
            }
            OutlinedButton(
                onClick = {
                    // Limpar pontos no lado Compose (canvas nativo)
                    pathPoints.clear()
                    drawPoints = emptyList()
                    // Limpar também os pontos dentro do HTML/SDK
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__peciResetTrajectory && window.__peciResetTrajectory();",
                            null,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset trajeto")
            }
        }



        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0D0D11)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
                update = { /* nothing — webView is self-managed */ },
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val points = drawPoints
                if (points.isEmpty()) return@Canvas

                val minX = points.minOf { it.x }
                val maxX = points.maxOf { it.x }
                val minY = points.minOf { it.y }
                val maxY = points.maxOf { it.y }

                val spanX = max(maxX - minX, 4f)
                val spanY = max(maxY - minY, 4f)
                val pad = 30f
                val scale = min((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f

                fun project(p: Offset): Offset {
                    val x = size.width / 2f + (p.x - cx) * scale
                    val y = size.height / 2f - (p.y - cy) * scale
                    return Offset(x, y)
                }

                val path = Path()
                val first = project(points.first())
                path.moveTo(first.x, first.y)
                points.drop(1).forEach { p ->
                    val pp = project(p)
                    path.lineTo(pp.x, pp.y)
                }

                drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(width = 10f))
                drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(width = 4f))
                drawPath(path, Color.White, style = Stroke(width = 2f))

                val last = project(points.last())
                drawCircle(Color.White, radius = 6f, center = last)

                val arrowLen = 28f
                val heading = lastHeadingRad + headingOffsetRad
                val dx = kotlin.math.cos(heading.toDouble()).toFloat() * arrowLen
                val dy = -kotlin.math.sin(heading.toDouble()).toFloat() * arrowLen
                drawLine(Color.White, start = last, end = Offset(last.x + dx, last.y + dy), strokeWidth = 3f)
            }
            Text(
                text = when (pageStatus) {
                    "loaded" -> "WebView: OK"
                    "error" -> "WebView erro"
                    else -> "WebView a carregar..."
                },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
            if (pageStatus == "error") {
                Text(
                    text = pageError ?: "Erro a carregar o trajeto",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            } else if (pathPoints.isEmpty()) {
                Text(
                    text = "Sem dados IMU",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                )
            }
            Text(
                text = "pts=${pathPoints.size}  motion=$lastMotion",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
            Text(
                text = "sdk_recv=$sdkRecvCount",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
            navisensImuOut?.let { out ->
                val acc = "a=${"%.2f".format(out.ax)} ${"%.2f".format(out.ay)} ${"%.2f".format(out.az)}"
                val gyr = "g=${"%.2f".format(out.gx)} ${"%.2f".format(out.gy)} ${"%.2f".format(out.gz)}"
                val mag = "m=${"%.1f".format(out.mx)} ${"%.1f".format(out.my)} ${"%.1f".format(out.mz)}"
                Text(
                    text = "$acc  $gyr  $mag",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildNavisensWebView(
    ctx: Context,
    developerKey: String,
    onMotion: (lat: Double?, lon: Double?, heading: Double, motionType: String, stepCount: Int) -> Unit,
    onError: (String) -> Unit,
    onStarted: () -> Unit,
    onStopped: () -> Unit,
    onPageStatus: (status: String, message: String?) -> Unit,
): WebView {
    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    var latestAx = 0f
    var latestAy = 0f
    var latestAz = 9.81f
    var latestMx = 0f
    var latestMy = 0f
    var latestMz = 0f
    var lastPostNs = 0L
    val minIntervalNs = 2_000_000L // 500Hz cap
    val glassesMuteWindowNs = 2_000_000_000L

    lateinit var webViewRef: WebView

    val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAx = event.values[0]
                    latestAy = event.values[1]
                    latestAz = event.values[2]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    latestMx = event.values[0]
                    latestMy = event.values[1]
                    latestMz = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val lastGlasses = NavisensImuBridge.lastGlassesSampleNs()
                    val nowRealNs = SystemClock.elapsedRealtimeNanos()
                    if (nowRealNs - lastGlasses < glassesMuteWindowNs) return

                    val now = event.timestamp
                    if (now - lastPostNs < minIntervalNs) return
                    lastPostNs = now

                    val tMs = System.currentTimeMillis()
                    // Navisens inputMotion expects gyro in deg/s (matches the
                    // DeviceMotionEvent.rotationRate convention the SDK's
                    // hello-world-web demo uses unchanged). Android's
                    // TYPE_GYROSCOPE is rad/s natively, so convert to deg/s.
                    val radToDeg = 180.0 / Math.PI
                    val gx = event.values[0].toDouble() * radToDeg
                    val gy = event.values[1].toDouble() * radToDeg
                    val gz = event.values[2].toDouble() * radToDeg
                    val ax = latestAx.toDouble()
                    val ay = latestAy.toDouble()
                    val az = latestAz.toDouble()
                    val mx = latestMx.toDouble() * 10.0
                    val my = latestMy.toDouble() * 10.0
                    val mz = latestMz.toDouble() * 10.0

                    NavisensTelemetry.update(
                        NavisensImuOut(
                            source = "phone",
                            deviceMode = "phone",
                            timestampMs = tMs,
                            gx = gx,
                            gy = gy,
                            gz = gz,
                            ax = ax,
                            ay = ay,
                            az = az,
                            mx = mx,
                            my = my,
                            mz = mz,
                        )
                    )

                    val js = "window.__peciSetDeviceMode && window.__peciSetDeviceMode('phone');" +
                        "window.__peciImuSample && window.__peciImuSample(" +
                        "$tMs,$gx,$gy,$gz,$ax,$ay,$az,$mx,$my,$mz);"
                    webViewRef.post { webViewRef.evaluateJavascript(js, null) }
                }
            }
        }
    }

    val webView = object : WebView(ctx) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            accel?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
            gyro?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
            mag?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
            Log.d(
                "NavisensWebView",
                "sensors registered — accel=${accel != null} gyro=${gyro != null} mag=${mag != null}"
            )
        }
        override fun onDetachedFromWindow() {
            sm.unregisterListener(sensorListener)
            super.onDetachedFromWindow()
        }
    }
    webViewRef = webView

    webView.apply {
        // Ensure the WebView draws a solid dark background so a blank/white
        // frame never appears during attach/detach transitions.
        setBackgroundColor(AndroidColor.parseColor("#FF0D0D11"))
        // Software layer avoids some devices rendering the WebView as black.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = false
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onPageStatus("loading", null)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val trimmed = developerKey.trim()
                if (trimmed.isNotEmpty() && trimmed != "<developer-key>" && trimmed != "REPLACE_ME") {
                    val js = "window.__peciSetDeveloperKey && window.__peciSetDeveloperKey(" +
                        JSONObject.quote(trimmed) + ");"
                    view?.evaluateJavascript(js, null)
                }
                onPageStatus("loaded", null)
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                onPageStatus("error", description ?: "Erro WebView")
            }

            
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                val crashed = detail?.didCrash() == true
                Log.e(
                    "NavisensWebView",
                    "renderer gone (crashed=$crashed, priority=${detail?.rendererPriorityAtExit()}); reloading",
                )
                onPageStatus("error", "Renderer caiu — a recarregar")
                view?.reload()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val level = msg.messageLevel()
                val line = "[${msg.sourceId()?.substringAfterLast('/') ?: "?"}:${msg.lineNumber()}] ${msg.message()}"
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e("NavisensWebView", line)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w("NavisensWebView", line)
                    ConsoleMessage.MessageLevel.LOG,
                    ConsoleMessage.MessageLevel.DEBUG,
                    ConsoleMessage.MessageLevel.TIP -> Log.d("NavisensWebView", line)
                    else -> Log.i("NavisensWebView", line)
                }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d("NavisensWebView", "permission request: ${request.resources.joinToString()}")
                request.grant(request.resources)
            }
        }
        addJavascriptInterface(object {
            @JavascriptInterface
            fun onNavisensEvent(event: String, payload: String) {
                Log.d("NavisensWebView", "event=$event payload=$payload")
                try {
                    when (event) {
                        "motion" -> {
                            val json = org.json.JSONObject(payload)
                            val x = if (json.isNull("x")) null else json.optDouble("x", Double.NaN).takeUnless { it.isNaN() }
                            val y = if (json.isNull("y")) null else json.optDouble("y", Double.NaN).takeUnless { it.isNaN() }
                            val hdg = json.optDouble("heading", 0.0)
                            val mt = json.optString("motionType", "")
                            val steps = json.optInt("stepCount", 0)
                            onMotion(x, y, hdg, mt, steps)
                        }
                        "error" -> {
                            val json = org.json.JSONObject(payload)
                            onError(json.optString("message", "unknown"))
                        }
                        "started" -> onStarted()
                        "stopped" -> onStopped()
                    }
                } catch (e: Exception) {
                    Log.w("NavisensWebView", "event parse failed: ${e.message}")
                }
            }
        }, "Android")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        loadUrl("file:///android_asset/navisens_trajectory.html")
    }
    return webView
}


private object NavisensImuBridge {
    @Volatile private var lastGlassesNs: Long = 0L
    fun markGlassesSample(nowNs: Long) { lastGlassesNs = nowNs }
    fun lastGlassesSampleNs(): Long = lastGlassesNs
}

data class NavisensImuOut(
    val source: String,
    val deviceMode: String,
    val timestampMs: Long,
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val mx: Double,
    val my: Double,
    val mz: Double,
)

object NavisensTelemetry {
    val latestImuOut = MutableStateFlow<NavisensImuOut?>(null)
    fun update(sample: NavisensImuOut) {
        latestImuOut.value = sample
    }
}

