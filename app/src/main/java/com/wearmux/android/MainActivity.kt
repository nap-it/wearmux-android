package com.wearmux.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wearmux.android.integration.GlassesConnectionMode
import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.ui.theme.WearMuxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

        // Request permissions and start the background service.
        requestPermissionsAndStart()

        setContent {
            WearMuxTheme {
                AppNavigation(viewModel)
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
