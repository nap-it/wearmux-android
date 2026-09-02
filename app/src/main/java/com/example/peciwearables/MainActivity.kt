package com.example.peciwearables

import android.Manifest
import android.media.MediaPlayer
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.ui.theme.PeciWearablesTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.peciwearables.integration.BleConnectionCandidate
import com.example.peciwearables.integration.MlProcessingLocation
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.GlassesNetworkStatusResolver
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.WearableKind
import com.example.peciwearables.integration.pdr.SavedRoute
import com.example.peciwearables.integration.wearable.devices.omi.quaternionToYawDeg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.example.peciwearables.integration.safety.haversineMeters
import com.example.peciwearables.ui.SettingsScreen

data class NavItem(val label: String, val icon: ImageVector, val tab: Tab)

val navItems = listOf(
    NavItem("Dispositivos", Icons.Filled.Link, Tab.DEVICES),
    NavItem("Estado", Icons.Filled.MonitorHeart, Tab.STATUS),
    NavItem("Dev", Icons.Filled.Code, Tab.DEV_LAB),
    NavItem("Definições", Icons.Filled.Settings, Tab.SETTINGS),
)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_BENCHMARK_SCENARIO = "benchmark_scenario"
        const val BENCHMARK_PHOTO_BLE = "photo_ble"
        const val BENCHMARK_PHOTO_WIFI = "photo_wifi"
        const val BENCHMARK_MIC_BLE = "mic_ble"
        const val BENCHMARK_MIC_WIFI = "mic_wifi"
        const val BENCHMARK_IMU_ML = "imu_ml"
    }

    private val viewModel: AppViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            viewModel.startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pedir permissoes e iniciar servico
        requestPermissionsAndStart()

        setContent {
            PeciWearablesTheme {
                MainScreen(viewModel)
            }
        }

        maybeRunBenchmarkScenario(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRunBenchmarkScenario(intent)
    }

    private fun requestPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            viewModel.startService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun maybeRunBenchmarkScenario(intent: android.content.Intent?) {
        val scenario = intent?.getStringExtra(EXTRA_BENCHMARK_SCENARIO) ?: return
        lifecycleScope.launch {
            when (scenario) {
                BENCHMARK_PHOTO_BLE -> runPhotoBenchmark(GlassesConnectionMode.BLE)
                BENCHMARK_PHOTO_WIFI -> runPhotoBenchmark(GlassesConnectionMode.WIFI)
                BENCHMARK_MIC_BLE -> runMicrophoneBenchmark(GlassesConnectionMode.BLE)
                BENCHMARK_MIC_WIFI -> runMicrophoneBenchmark(GlassesConnectionMode.WIFI)
                BENCHMARK_IMU_ML -> runImuMlBenchmark()
            }
        }
    }

    private suspend fun ensureSoleReady(): Boolean {
        if (viewModel.wristbandState.value == BleDeviceState.DISCONNECTED || viewModel.wristbandState.value == BleDeviceState.ERROR) {
            viewModel.connectWristband()
        }

        return withTimeoutOrNull(30_000L) {
            viewModel.wristbandState.filter { it == BleDeviceState.READY || it == BleDeviceState.CONNECTED }.first()
        } != null
    }

    private suspend fun ensureGlassesReady(mode: GlassesConnectionMode): Boolean {
        viewModel.setGlassesConnectionMode(mode)
        delay(500)
        if (viewModel.glassesState.value == BleDeviceState.DISCONNECTED || viewModel.glassesState.value == BleDeviceState.ERROR) {
            viewModel.connectGlasses()
        }
        val bleReady = withTimeoutOrNull(30_000L) {
            viewModel.glassesState.filter { it == BleDeviceState.READY || it == BleDeviceState.CONNECTED }.first()
        } != null
        if (!bleReady) return false

        if (mode == GlassesConnectionMode.WIFI) {
            val ipReady = withTimeoutOrNull(15_000L) {
                viewModel.glassesIp.filter { !it.isNullOrBlank() }.first()
            } != null
            if (!ipReady) return false
            if (!viewModel.udpActive.value) {
                viewModel.connectWifi()
            }
            val udpReady = withTimeoutOrNull(20_000L) {
                viewModel.udpActive.filter { it }.first()
            } != null
            if (!udpReady) return false
            delay(2500)
        }

        return true
    }

    private suspend fun runPhotoBenchmark(mode: GlassesConnectionMode) {
        if (!ensureGlassesReady(mode)) return
        repeat(3) { attempt ->
            val before = viewModel.photoCount.value
            viewModel.wakeCamera()
            delay(220)
            viewModel.takePicture()
            val ok = withTimeoutOrNull(16_000L) {
                viewModel.photoCount.filter { it > before }.first()
            } != null
            if (ok) return
            if (attempt < 2) delay(700)
        }
    }

    private suspend fun runMicrophoneBenchmark(mode: GlassesConnectionMode) {
        if (!ensureGlassesReady(mode)) return
        if (viewModel.glassesMicStreaming.value) {
            viewModel.stopMicrophone()
            delay(800)
        }
        viewModel.startMicrophone()
        withTimeoutOrNull(10_000L) {
            viewModel.glassesMicStreaming.filter { it }.first()
        }
        delay(1500)
        viewModel.stopMicrophone()
    }

    private suspend fun runImuMlBenchmark() {
        if (!ensureSoleReady()) return
        viewModel.triggerImuMlBenchmark()
    }
}

@Composable
fun MainScreen(viewModel: AppViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    // Dev Lab é um ecrã técnico (preview, YOLO/Depth, pipelines manuais) — só
    // aparece na navegação quando o Modo desenvolvimento está ativo.
    val tabs = Tab.entries.filter { isDeveloperMode || it != Tab.DEV_LAB }
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    // Se o Modo desenvolvimento for desligado enquanto o utilizador está na
    // tab Dev Lab, volta para Dispositivos em vez de deixar o ecrã técnico exposto.
    LaunchedEffect(isDeveloperMode) {
        if (!isDeveloperMode && selectedTab == Tab.DEV_LAB) {
            viewModel.setTab(Tab.DEVICES)
        }
    }

    // Sync tab -> pager. Usa scrollToPage (sem animação) para acompanhar
    // mudanças de tamanho de `tabs` (ex: ligar/desligar Modo dev) sem correr
    // à frente do valor ainda não assentado do pager.
    LaunchedEffect(selectedTab, tabs) {
        val index = tabs.indexOf(selectedTab)
        if (index >= 0 && pagerState.currentPage != index) {
            pagerState.scrollToPage(index)
        }
    }

    // Sync pager -> tab. Só reage a gestos reais do utilizador (swipe) — não
    // dispara quando `tabs` muda de tamanho, porque nesse caso é o efeito
    // acima que deve mandar, lendo `selectedTab` como fonte de verdade e não
    // o índice desatualizado do pager.
    LaunchedEffect(pagerState.settledPage) {
        val settledTab = tabs.getOrNull(pagerState.settledPage)
        if (settledTab != null && settledTab != selectedTab) {
            viewModel.setTab(settledTab)
        }
    }

    // Device states
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize().background(Constants.backgroundColor),
        containerColor = Constants.backgroundColor,
        bottomBar = {
            CustomBottomBar(
                selectedTab = selectedTab,
                onItemSelected = { viewModel.setTab(it) },
                items = navItems.filter { isDeveloperMode || it.tab != Tab.DEV_LAB },
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .background(Constants.backgroundColor)
                .padding(innerPadding)
        ) { page ->
            when (tabs[page]) {
                Tab.DEVICES -> com.example.peciwearables.ui.CleanInfoScreen(viewModel)
                Tab.STATUS -> com.example.peciwearables.ui.EstadoScreen(viewModel)
                Tab.DEV_LAB -> com.example.peciwearables.ui.DevLabScreen(viewModel)
                Tab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}
