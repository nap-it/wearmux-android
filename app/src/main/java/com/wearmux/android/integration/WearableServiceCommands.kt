package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.adapters.WearableCommand
import com.wearmux.android.integration.hub.WearableKind
import com.wearmux.android.integration.modules.microphone.GlassesMicrophoneProfile
import com.wearmux.android.integration.modules.sensors.SensorRatePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun BleDeviceState.isOnlineBle() = this == BleDeviceState.READY || this == BleDeviceState.CONNECTED

internal fun WearableService.setMlProcessingLocation(mode: MlProcessingLocation) {
    val previous = WearableService._mlProcessingLocation.value
    if (previous == mode) { WearableService.appendLog("🧠  ML mode already active: ${mode.name.lowercase()}"); return }
    WearableService.appendLog("🧠  ML mode: ${previous.name.lowercase()} -> ${mode.name.lowercase()}")
    WearableService._mlProcessingLocation.value = mode
    mlProcessingCoordinator.apply(mode, "mode changed")
}

internal fun WearableService.applyMlProcessingMode(mode: MlProcessingLocation, reason: String) =
    mlProcessingCoordinator.apply(mode, reason)

internal fun WearableService.desiredWristbandSensorRateMs(): Int = SensorRatePolicy.wristbandRateMs(
    location = WearableService._mlProcessingLocation.value,
    contended = WearableService._glassesMicStreaming.value ||
        WearableService._audioRecordingActive.value ||
        photoCaptureInFlight,
)

/** FPS-alvo do stream: baixa quando há áudio activo (concorrência por canal). */
internal fun WearableService.streamTargetFps(
    audioActive: Boolean = WearableService._audioRecordingActive.value || WearableService._glassesMicStreaming.value,
): Int = when (WearableService._glassesConnectionMode.value) {
    GlassesConnectionMode.WIFI -> if (audioActive) 10 else 12
    GlassesConnectionMode.BLE -> if (audioActive) 4 else 6
}.coerceAtMost(10)

internal fun WearableService.updateWristbandTrafficProfile(reason: String) {
    val state = wristbandBleClient.state.value
    if (state != BleDeviceState.READY && state != BleDeviceState.CONNECTED) return
    val targetRate = desiredWristbandSensorRateMs()
    if (wristbandSensorRateAppliedMs == targetRate) return
    val includeMagnetometer = SensorRatePolicy.includeMagnetometer(WearableService._mlProcessingLocation.value)
    wristbandBleClient.setSensorsConfig(targetRate, includeMagnetometer, includePressure = false)
    wristbandSensorRateAppliedMs = targetRate
    WearableService.appendLog("BS sensors: imu=${targetRate}ms, mag=$includeMagnetometer ($reason)")
}

internal fun WearableService.startCandidateDiscovery() = scanAndConnect.startDiscovery()
internal fun WearableService.stopCandidateDiscoveryIfNeeded(reason: String? = null) = scanAndConnect.stopDiscovery(reason)
internal fun WearableService.connectSelectedCandidate(address: String) = scanAndConnect.connectByAddress(address)
internal fun WearableService.connectEsp32Cam() = scanAndConnect.connectEsp32Cam { WearableService._esp32State.value = it }
internal fun WearableService.startFilteredConnect(kind: WearableKind, label: String) {
    wifiUdpRecovery.cancelWatchdog(); scanAndConnect.startFilteredConnect(kind, label)
}
internal fun WearableService.startAutoConnectFlow() = scanAndConnect.startAutoConnect()
internal fun WearableService.startWifiTransitionWatchdog() = wifiUdpRecovery.startWatchdog()

/** Desliga os óculos a pedido do utilizador (BLE e, se ativo, a sessão Wi-Fi/UDP). */
internal fun WearableService.disconnectGlasses() {
    WearableService.appendLog("🔌 Glasses: manual disconnect requested")
    // Impede que o watchdog/recuperação Wi-Fi volte a religar sozinho.
    wifiUdpRecovery.cancelWatchdog()
    scanAndConnect.cancelAutoConnect()
    serviceScope.launch {
        // Mata a sessão UDP sem disparar a recuperação automática e larga a rota BLE.
        wifiUdpRecovery.disconnectUdpSession()
        wifiUdpRecovery.clearRouting()
        val id = currentGlassesSessionId()
        if (id != null) {
            deviceHub.disconnect(id)            // remove a sessão + client.disconnect()
            glassesWearableAdapter.releaseClient(id)
        } else {
            glassesBleClient.disconnect()
        }
        WearableService._glassesState.value = BleDeviceState.DISCONNECTED
        WearableService._glassesIp.value = null
        WearableService.appendLog("✅ Glasses disconnected")
    }
}

/** Desliga a pulseira/sole a pedido do utilizador. */
internal fun WearableService.disconnectWristband() {
    WearableService.appendLog("🔌 Wristband: manual disconnect requested")
    scanAndConnect.cancelAutoConnect()
    serviceScope.launch {
        val id = currentWristbandSessionId()
        if (id != null) {
            deviceHub.disconnect(id)
            wristbandWearableAdapter.releaseClient(id)
        } else {
            wristbandBleClient.disconnect()
        }
        WearableService._wristbandState.value = BleDeviceState.DISCONNECTED
        WearableService.appendLog("✅ Wristband disconnected")
    }
}

internal fun WearableService.takePictureWithMicPriority(actionLabel: String) {
    if (!WearableService._glassesState.value.isOnlineBle()) {
        WearableService.appendLog("⚠  Photo: Omi is not connected (state: ${WearableService._glassesState.value.name})"); return
    }
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI && !WearableService._udpActive.value) {
        val ip = WearableService._glassesIp.value
        if (ip.isNullOrBlank()) WearableService.appendLog("⚠  Wi-Fi photo: no known Omi IP")
        else { WearableService.appendLog("ℹ Wi-Fi photo: UDP session not active, trying to connect..."); connectUdp(ip) }
        return
    }
    if (WearableService._audioRecordingActive.value) {
        WearableService.appendLog("⚠  Photo: stop the audio recording before taking a photo"); return
    }
    val sendCapture: () -> Unit = {
        photoCaptureInFlight = true
        startPhotoLatencyMeasurement()
        updateWristbandTrafficProfile("photo capture")
        photoCaptureTimeoutJob?.cancel()
        photoCaptureTimeoutJob = serviceScope.launch {
            delay(6000L)
            if (photoCaptureInFlight) { photoCaptureInFlight = false; updateWristbandTrafficProfile("capture timeout") }
        }
        WearableService.appendLog("📷 $actionLabel via ${if (WearableService._udpActive.value) "Wi-Fi/UDP" else "BLE"}...")
        sendGlassesCommand(WearableCommand.TakePicture, "take photo")
    }
    if (WearableService._glassesMicStreaming.value) {
        sendGlassesCommand(WearableCommand.StopAudioStream, "pause microphone for photo")
        WearableService._glassesMicStreaming.value = false; WearableService._glassesMicStatusText.value = "IDLE"
        WearableService.appendLog("🎤 Microphone paused to prioritize photo capture")
        serviceScope.launch { delay(180L); sendCapture() }
    } else sendCapture()
}

internal fun WearableService.clearGlassesUdpRouting() = wifiUdpRecovery.clearRouting()
internal fun WearableService.connectUdp(ip: String) = wifiUdpRecovery.connect(ip)

internal fun WearableService.startPhoneSensorsInternal() {
    if (WearableService._phoneSensorsActive.value) return
    phoneSensors.startAll(); pdr.start()
    WearableService._phoneSensorsActive.value = true
}

internal fun WearableService.stopPhoneSensorsInternal() {
    if (!WearableService._phoneSensorsActive.value) return
    phoneSensors.stopAll(); pdr.stop()
    WearableService._phoneSensorsActive.value = false
    WearableService._phoneAccel.value = null; WearableService._phoneGps.value = null
}

internal fun WearableService.applyGlassesMicrophoneProfileInternal(profile: GlassesMicrophoneProfile) {
    val decision = decideGlassesMicrophoneProfileApply(WearableService._glassesState.value, WearableService._glassesMicStreaming.value)
    if (!decision.canApply) { WearableService.appendLog("Microphone: ${decision.reason}"); return }
    val client = currentGlassesClient() ?: glassesBleClient
    if (decision.shouldRestartStream) {
        WearableService.appendLog("Microphone: applying profile ${profile.profileId} with stream restart")
        WearableService._glassesMicStatusText.value = "RESTARTING"
        client.stopMicrophone(); client.applyMicrophoneProfile(profile); client.startMicrophone()
    } else {
        WearableService.appendLog("Microphone: applying profile ${profile.profileId}")
        client.applyMicrophoneProfile(profile)
    }
}

internal fun WearableService.connectKwsInternal(host: String = hostFromUrl(WearableService._unifiedServerUrl.value)) = kwsCoordinator.connect(host)
internal fun WearableService.hostFromUrl(url: String): String =
    runCatching { java.net.URI(url).host }.getOrDefault(CloudConfig.DEFAULT_HOST)
internal fun WearableService.disconnectKwsInternal() = kwsCoordinator.disconnect()

internal fun WearableService.routeStartRecordingInternal() = routeRecording.start()
internal fun WearableService.routeFinishInternal(name: String) = routeRecording.finish(name)
internal fun WearableService.routeCancelInternal() = routeRecording.cancel()
internal fun WearableService.routeActivateInternal(id: String) = routeRecording.activate(id)
internal fun WearableService.routeDeactivateInternal() = routeRecording.deactivate()
internal fun WearableService.routeDeleteInternal(id: String) = routeRecording.delete(id)

internal fun WearableService.fusedToImuSample(s: com.wearmux.android.integration.protocol.GlassesImuSample) =
    com.wearmux.android.integration.modules.sensors.FusedImuConverter.toImuSample(s)
