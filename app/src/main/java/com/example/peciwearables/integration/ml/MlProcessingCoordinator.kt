package com.example.peciwearables.integration.ml

import com.example.peciwearables.integration.MlProcessingLocation
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClient
import com.example.peciwearables.integration.ble.devices.brilliantsole.TFLITE_TASK_CLASSIFICATION
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
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
            appendLog("🧠  Modo ML: ${mode.name.lowercase()} (aguarda ligação da pulseira)")
            return
        }
        when (mode) {
            MlProcessingLocation.WRISTBAND -> applyWristband(client, reason)
            MlProcessingLocation.APP -> {
                client.disableTfliteInferencing()
                onTrafficProfileUpdate("ml app ($reason)")
                appendLog("🧠  Modo ML = app (modelo local Android @20Hz)")
            }
            MlProcessingLocation.SERVER -> {
                client.disableTfliteInferencing()
                serverClassifier()?.reset()
                onTrafficProfileUpdate("ml server ($reason)")
                appendLog("🧠  Modo ML = server (node-simd @20Hz)")
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
                    .getOrElse { appendLog("❌ Upload trained.tflite falhou: ${it.message}"); false }
                modelUploaded = uploaded
                if (uploaded) appendLog("✅ trained.tflite pronto na wristband")
            }
            if (!modelUploaded) {
                appendLog("❌ Modo wristband cancelado: modelo não foi instalado")
                return@launch
            }
            client.setSensorsConfig(
                rateMs = 10, includeMagnetometer = false,
                includePressure = false, includeLinearAcceleration = true,
            )
            client.setTfliteSampleRate(100)
            client.setTfliteTask(TFLITE_TASK_CLASSIFICATION)
            client.setTfliteCaptureDelay(1000)
            client.setTfliteSensorTypes(byteArrayOf(3, 4))
            client.setTfliteThreshold(0f)
            client.enableTfliteInferencing()
            onWristbandRateApplied(10)
            appendLog("🧠  Modo ML = wristband (100Hz, inferência no dispositivo) [$reason]")
        }
    }
}
