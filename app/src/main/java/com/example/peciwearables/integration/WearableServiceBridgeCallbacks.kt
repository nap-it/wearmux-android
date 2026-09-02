package com.example.peciwearables.integration

import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.wearable.legacy.WearableServiceLegacyBridge
import kotlinx.coroutines.flow.update



internal fun WearableService.onGlassesStateChanged(newState: BleDeviceState) {
    if (!glassesConnectedViaUdp) WearableService._glassesState.value = newState
    if (newState != BleDeviceState.DISCONNECTED && newState != BleDeviceState.ERROR) return
    WearableService._latestGlassesImu.value = null
    glassesQuaternionTracker.reset()
    glassesGyroIntegrators.reset()
    if (!glassesConnectedViaUdp) {
        onGlassesDeviceDisconnected(); updateWristbandTrafficProfile("omi desconectado")
    }
}

internal fun WearableService.onGlassesIpReceived(ip: String) {
    if (WearableService._glassesIp.value != ip) {
        WearableService._glassesIp.value = ip; WearableService.appendLog("IP Omi recebido: $ip")
    }
    currentGlassesClient()?.let {
        WearableService._glassesWifiSupported.value = it.supportsWifiControl
        WearableService._glassesWifiConnectionEnabled.value = it.wifiConnectionEnabled
        WearableService._glassesWifiConnected.value = it.isWifiConnected
        WearableService._glassesWifiSecure.value = it.isWifiSecure
    }
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI && wifiCredentialsSent && !glassesConnectedViaUdp) connectUdp(ip)
}

internal fun WearableService.onWristbandStateChanged(state: BleDeviceState) {
    WearableService._wristbandState.value = state
    when (state) {
        BleDeviceState.READY -> {
            wristbandSensorRateAppliedMs = -1; applyMlProcessingMode(WearableService._mlProcessingLocation.value, "BS READY")
        }
        BleDeviceState.DISCONNECTED, BleDeviceState.ERROR -> {
            wristbandSensorRateAppliedMs = -1
            mlProcessingCoordinator.onWristbandDisconnected()
            WearableService._latestImuSamples.update { emptyList() }
        }
        else -> Unit
    }
}

internal fun WearableService.setupWearableLegacyBridge() {
    wearableBridge?.stop()
    wearableBridge = WearableServiceLegacyBridge(wearableHub, serviceScope, WearableServiceLegacyBridge.Callbacks(
        onGlassesState = ::onGlassesStateChanged,
        onGlassesBattery = { WearableService._glassesBattery.value = it },
        onGlassesFirmware = { WearableService._glassesFirmware.value = it },
        onGlassesAudioCodecId = { codecId ->
            glassesRawAudioCodec = codecId
            glassesMicrophoneManager.updateCodec(codecId); refreshGlassesMicPlaybackSupport()
        },
        onGlassesMicrophoneConfig = { sr, bd, codec ->
            audioRecordSampleRate = sr; glassesRawAudioCodec = codec
            WearableService._glassesMicSampleRateHz.value = sr; WearableService._glassesMicBitDepth.value = bd
            glassesMicrophoneManager.updateConfiguration(sr, bd); refreshGlassesMicPlaybackSupport()
        },
        onGlassesMicrophoneStatus = glassesMicStateMachine::onStatus,
        onGlassesCameraStatus = glassesCameraStateMachine::onStatus,
        onGlassesIp = ::onGlassesIpReceived,
        onWristbandState = ::onWristbandStateChanged,
        onWristbandBattery = { WearableService._wristbandBattery.value = it },
        onWristbandFirmware = { WearableService._wristbandFirmware.value = it },
        onEsp32State = { WearableService._esp32State.value = it },
    )).also { it.start() }
}
