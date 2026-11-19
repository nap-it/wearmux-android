package com.wearmux.android.integration

import com.wearmux.android.integration.adapters.BleDeviceState
import com.wearmux.android.integration.adapters.legacy.WearableServiceLegacyBridge
import kotlinx.coroutines.flow.update



internal fun WearableService.onGlassesStateChanged(newState: BleDeviceState) {
    if (!glassesConnectedViaUdp) WearableService._glassesState.value = newState
    if (newState != BleDeviceState.DISCONNECTED && newState != BleDeviceState.ERROR) return
    WearableService._latestGlassesImu.value = null
    glassesQuaternionTracker.reset()
    glassesGyroIntegrators.reset()
    if (!glassesConnectedViaUdp) {
        onGlassesDeviceDisconnected(); updateWristbandTrafficProfile("omi disconnected")
    }
}

internal fun WearableService.onGlassesIpReceived(ip: String) {
    if (WearableService._glassesIp.value != ip) {
        WearableService._glassesIp.value = ip; WearableService.appendLog("Omi IP received: $ip")
    }
    currentGlassesClient()?.let {
        WearableService._glassesWifiSupported.value = it.supportsWifiControl
        WearableService._glassesWifiConnectionEnabled.value = it.wifiConnectionEnabled
        WearableService._glassesWifiConnected.value = it.isWifiConnected
        WearableService._glassesWifiSecure.value = it.isWifiSecure
    }
    if (WearableService._glassesConnectionMode.value == GlassesConnectionMode.WIFI && wifiCredentialsSent && !glassesConnectedViaUdp) connectUdp(ip)
}

internal fun WearableService.setupWearableLegacyBridge() {
    wearableBridge?.stop()
    wearableBridge = WearableServiceLegacyBridge(deviceHub, serviceScope, WearableServiceLegacyBridge.Callbacks(
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
        // A pulseira publica estado por sessão; os flows globais são o agregado.
        onWristbandState = ::onWristbandStateChanged,
        onWristbandBattery = ::onWristbandBatteryChanged,
        onWristbandFirmware = ::onWristbandFirmwareChanged,
        onWristbandDevice = ::onWristbandDeviceChanged,
        onGlassesDevice = ::onGlassesDeviceChanged,
        onDeviceEvent = { id, event -> onDeviceEvent(id, event) },
        onSessionRemoved = { id -> onWearableSessionRemoved(id) },
        onEsp32State = { WearableService._esp32State.value = it },
    )).also { it.start() }
}
