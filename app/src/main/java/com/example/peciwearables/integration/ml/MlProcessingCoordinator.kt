package com.example.peciwearables.integration.ml

import com.example.peciwearables.integration.MlProcessingLocation
import com.example.peciwearables.integration.adapters.BleDeviceState
import com.example.peciwearables.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandBleClient
import com.example.peciwearables.integration.adapters.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
import com.example.peciwearables.integration.adapters.devices.brilliantsole.TFLITE_TASK_CLASSIFICATION
import com.example.peciwearables.integration.api.PeciServerClassifier
import com.example.peciwearables.integration.modules.sensors.SensorRatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Aplica o modo ML (WRISTBAND / APP / SERVER) à pulseira BLE. */
class MlProcessingCoordinator(
    private val scope: CoroutineScope,
    private val wristbandClient: () -> BrilliantSoleWristbandBleClientApi,
    private val serverClassifier: () -> PeciServerClassifier?,
    private val onWristbandRateApplied: (Int) -> Unit,
    private val onTrafficProfileUpdate: (String) -> Unit,
    private val appendLog: (String) -> Unit,
) {
    private var modelUploadJob: Job? = null
    private var modelUploaded = false

    fun apply(mode: MlProcessingLocation, reason: String) {
        val client = wristbandClient()
        val state = client.state.value
        if (state != BleDeviceState.READY && state != BleDeviceState.CONNECTED) {
            appendLog("🧠  ML mode: ${mode.name.lowercase()} (waiting for wristband connection)")
            return
        }
        when (mode) {
            MlProcessingLocation.WRISTBAND -> applyWristband(client, reason)
            MlProcessingLocation.APP -> {
                client.disableTfliteInferencing()
                onTrafficProfileUpdate("ml app ($reason)")
                appendLog("🧠  ML mode = app (local Android model @20Hz)")
            }
            MlProcessingLocation.SERVER -> {
                client.disableTfliteInferencing()
                serverClassifier()?.reset()
                onTrafficProfileUpdate("ml server ($reason)")
                appendLog("🧠  ML mode = server (node-simd @20Hz)")
            }
        }
    }

    fun onWristbandDisconnected() {
        modelUploadJob?.cancel(); modelUploadJob = null; modelUploaded = false
    }

    private fun applyWristband(client: BrilliantSoleWristbandBleClientApi, reason: String) {
        modelUploadJob?.cancel()
        modelUploadJob = scope.launch {
            if (!modelUploaded) {
                val uploaded = runCatching { client.uploadTfliteAsset("trained.tflite") }
                    .getOrElse { appendLog("❌ Upload of trained.tflite failed: ${it.message}"); false }
                modelUploaded = uploaded
                if (uploaded) appendLog("✅ trained.tflite ready on the wristband")
            }
            if (!modelUploaded) {
                appendLog("❌ Wristband mode cancelled: model was not installed")
                return@launch
            }
            val rateMs = SensorRatePolicy.wristbandRateMs(MlProcessingLocation.WRISTBAND, contended = false)
            client.setSensorsConfig(
                rateMs = rateMs,
                includeMagnetometer = SensorRatePolicy.includeMagnetometer(MlProcessingLocation.WRISTBAND),
                includePressure = false, includeLinearAcceleration = true,
            )
            client.setTfliteSampleRate(100)
            client.setTfliteTask(TFLITE_TASK_CLASSIFICATION)
            client.setTfliteCaptureDelay(1000)
            client.setTfliteSensorTypes(byteArrayOf(3, 4))
            client.setTfliteThreshold(0f)
            client.enableTfliteInferencing()
            onWristbandRateApplied(rateMs)
            appendLog("🧠  ML mode = wristband (100Hz, on-device inference) [$reason]")
        }
    }
}
