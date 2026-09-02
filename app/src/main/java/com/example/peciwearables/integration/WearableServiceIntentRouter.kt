package com.example.peciwearables.integration

import android.content.Intent
import com.example.peciwearables.integration.WearableServiceActions.ACTION_APPLY_GLASSES_CAMERA_PROFILE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_APPLY_GLASSES_MICROPHONE_PROFILE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ATCLL_SET_ENDPOINT
import com.example.peciwearables.integration.WearableServiceActions.ACTION_AUDIO_NOTIFY
import com.example.peciwearables.integration.WearableServiceActions.ACTION_AUDIO_TEST_STOP
import com.example.peciwearables.integration.WearableServiceActions.ACTION_AUDIO_TEST_TONE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CAPTURE_PHOTO
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_AUTO
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_CANDIDATE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_ESP32
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_GLASSES
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_UDP
import com.example.peciwearables.integration.WearableServiceActions.ACTION_CONNECT_WRISTBAND
import com.example.peciwearables.integration.WearableServiceActions.ACTION_DEPTH_SET_URL
import com.example.peciwearables.integration.WearableServiceActions.ACTION_DISCONNECT_GLASSES
import com.example.peciwearables.integration.WearableServiceActions.ACTION_DISCONNECT_WRISTBAND
import com.example.peciwearables.integration.WearableServiceActions.ACTION_DISCOVER_CANDIDATES
import com.example.peciwearables.integration.WearableServiceActions.ACTION_PDR_RESET
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_ACTIVATE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_CANCEL
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_DEACTIVATE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_DELETE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_FINISH
import com.example.peciwearables.integration.WearableServiceActions.ACTION_ROUTE_START_RECORDING
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_ADD_ZONE_HERE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_REFRESH_OSM
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_REMOVE_ZONE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC1_2_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC1_4_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC1_4_STRICT_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC1_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC2_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC3_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC3_INCOMING_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC4_3_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SAFETY_SET_UC4_5_ENABLED
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SEND_WIFI
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_GLASSES_CONNECTION_MODE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_GLASSES_INFERENCE_MODE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_GLASSES_INFERENCE_URL
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_IMU_STREAMING
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_ML_PROCESSING_LOCATION
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_ML_SERVER_URL
import com.example.peciwearables.integration.WearableServiceActions.ACTION_SET_NAVISENS_IMU_SOURCE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_START
import com.example.peciwearables.integration.WearableServiceActions.ACTION_START_AUDIO_RECORDING
import com.example.peciwearables.integration.WearableServiceActions.ACTION_START_MICROPHONE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_START_PHONE_SENSORS
import com.example.peciwearables.integration.WearableServiceActions.ACTION_START_STREAM
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP_AUDIO_RECORDING
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP_DISCOVER_CANDIDATES
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP_MICROPHONE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP_PHONE_SENSORS
import com.example.peciwearables.integration.WearableServiceActions.ACTION_STOP_STREAM
import com.example.peciwearables.integration.WearableServiceActions.ACTION_TAKE_PICTURE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_TRIGGER_IMU_ML_BENCHMARK
import com.example.peciwearables.integration.WearableServiceActions.ACTION_UNIFIED_SERVER_SET_URL
import com.example.peciwearables.integration.WearableServiceActions.ACTION_VIBRATE_GO
import com.example.peciwearables.integration.WearableServiceActions.ACTION_VIBRATE_STOP
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WAKE_CAMERA
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_BEEP
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_NOTIFY
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_SET_RATE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_START_IMU
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_STOP_IMU
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WATCH_VIBRATE
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WHISPER_CONNECT
import com.example.peciwearables.integration.WearableServiceActions.ACTION_WHISPER_DISCONNECT
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_ROUTE_ID
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_ROUTE_NAME
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_TONE_DURATION_MS
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_TONE_FREQ_HZ
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_VIBRATE_PATTERN
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WATCH_RATE_HZ
import com.example.peciwearables.integration.WearableServiceActions.EXTRA_WHISPER_HOST
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.ble.WearableKind

/** Routes Intent.action a um handler. Encapsula o body do `onStartCommand`. */
internal fun WearableService.routeIntent(intent: Intent?) {
    when (intent?.action) {
        ACTION_START -> Unit
        ACTION_STOP -> { wifiUdpRecovery.cancelWatchdog(); stopCandidateDiscoveryIfNeeded(); stopSelf() }
        ACTION_CONNECT_GLASSES -> startFilteredConnect(WearableKind.GLASSES, "Omi")
        ACTION_CONNECT_WRISTBAND -> startFilteredConnect(WearableKind.WRIST_OR_SOLE, "Pulseira")
        ACTION_CONNECT_ESP32 -> connectEsp32Cam()
        ACTION_CONNECT_AUTO -> startAutoConnectFlow()
        ACTION_DISCONNECT_GLASSES -> disconnectGlasses()
        ACTION_DISCONNECT_WRISTBAND -> disconnectWristband()
        ACTION_DISCOVER_CANDIDATES -> startCandidateDiscovery()
        ACTION_STOP_DISCOVER_CANDIDATES -> stopCandidateDiscoveryIfNeeded("Descoberta de candidatos parada")
        ACTION_CONNECT_CANDIDATE -> handleConnectCandidate(intent)
        ACTION_SEND_WIFI -> handleSendWifi(intent)
        ACTION_TAKE_PICTURE -> takePictureWithMicPriority("A tirar foto")
        ACTION_WAKE_CAMERA -> handleWakeCamera()
        ACTION_START_MICROPHONE -> handleStartMicrophone()
        ACTION_STOP_MICROPHONE -> handleStopMicrophone()
        ACTION_START_AUDIO_RECORDING -> handleStartAudioRecording()
        ACTION_STOP_AUDIO_RECORDING -> handleStopAudioRecording()
        ACTION_WHISPER_CONNECT -> intent.getStringExtra(EXTRA_WHISPER_HOST)?.trim()?.takeIf { it.isNotBlank() }
            ?.let(::connectKwsInternal) ?: WearableService.appendLog("KWS: host em falta")
        ACTION_WHISPER_DISCONNECT -> { disconnectKwsInternal(); WearableService.appendLog("KWS: desligado") }
        ACTION_TRIGGER_IMU_ML_BENCHMARK -> requestImuMlBenchmarkSample()
        ACTION_SET_ML_PROCESSING_LOCATION -> handleSetMlProcessingLocation(intent)
        ACTION_SET_ML_SERVER_URL -> handleSetMlServerUrl(intent)
        ACTION_APPLY_GLASSES_CAMERA_PROFILE -> handleApplyGlassesCameraProfile(intent)
        ACTION_APPLY_GLASSES_MICROPHONE_PROFILE -> handleApplyGlassesMicrophoneProfile(intent)
        ACTION_CONNECT_UDP -> handleConnectUdp()
        ACTION_SET_GLASSES_CONNECTION_MODE -> handleSetGlassesConnectionMode(intent)
        ACTION_VIBRATE_STOP -> { wristbandBleClient.sendStopAlertVibration(); WearableService.appendLog("🛑 Vibração PARAR via App") }
        ACTION_VIBRATE_GO -> { wristbandBleClient.sendGoAlertVibration(); WearableService.appendLog("▶️ Vibração SIGA via App") }
        ACTION_CAPTURE_PHOTO -> takePictureWithMicPriority("A capturar foto")
        ACTION_START_STREAM -> handleStartStream()
        ACTION_STOP_STREAM -> handleStopStream()
        ACTION_START_PHONE_SENSORS -> startPhoneSensorsInternal()
        ACTION_STOP_PHONE_SENSORS -> stopPhoneSensorsInternal()
        ACTION_PDR_RESET -> {
            pdr.reset(); WearableService._pdrPosition.value = null; WearableService._pdrStepCount.value = 0
            WearableService.appendLog("🔄 PDR reiniciado")
        }
        ACTION_ROUTE_START_RECORDING -> routeStartRecordingInternal()
        ACTION_ROUTE_FINISH -> routeFinishInternal(intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "")
        ACTION_ROUTE_CANCEL -> routeCancelInternal()
        ACTION_ROUTE_ACTIVATE -> intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf { it.isNotBlank() }
            ?.let(::routeActivateInternal) ?: WearableService.appendLog("ROUTE_ACTIVATE sem id")
        ACTION_ROUTE_DEACTIVATE -> routeDeactivateInternal()
        ACTION_ROUTE_DELETE -> intent.getStringExtra(EXTRA_ROUTE_ID)?.takeIf { it.isNotBlank() }?.let(::routeDeleteInternal)
        ACTION_SET_GLASSES_INFERENCE_MODE -> handleSetGlassesInferenceMode(intent)
        ACTION_SET_GLASSES_INFERENCE_URL -> handleSetGlassesInferenceUrl(intent)
        ACTION_SET_IMU_STREAMING -> handleSetImuStreaming(intent)
        ACTION_WATCH_START_IMU -> watchClient.requestStartImu()
        ACTION_WATCH_STOP_IMU -> watchClient.requestStopImu()
        ACTION_WATCH_SET_RATE -> {
            val hz = intent.getIntExtra(EXTRA_WATCH_RATE_HZ, 100)
            watchClient.requestSetSampleRate(hz); WearableService._watchSampleRateHz.value = hz.coerceIn(20, 200)
        }
        ACTION_WATCH_BEEP -> watchClient.requestBeep(intent.getIntExtra(EXTRA_TONE_FREQ_HZ, 880), intent.getIntExtra(EXTRA_TONE_DURATION_MS, 250))
        ACTION_WATCH_VIBRATE -> watchClient.requestVibrate(intent.getIntExtra(EXTRA_VIBRATE_PATTERN, 0))
        ACTION_WATCH_NOTIFY -> handleWatchNotify(intent)
        ACTION_SET_NAVISENS_IMU_SOURCE -> handleSetNavisensSource(intent)
        ACTION_AUDIO_TEST_TONE -> handleAudioTestTone(intent)
        ACTION_AUDIO_TEST_STOP -> AudioTestEngine.stop()
        ACTION_SAFETY_SET_UC1_ENABLED -> handleUcToggle(intent, "🚦 UC1.1", safetyToggles::setUc1)
        ACTION_SAFETY_SET_UC3_ENABLED -> handleUcToggle(intent, "🚦 UC1.3", safetyToggles::setUc3)
        ACTION_SAFETY_SET_UC2_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc2Enabled, "🚦 UC2")
        ACTION_SAFETY_SET_UC3_INCOMING_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc3IncomingEnabled, "🚦 UC3 incoming")
        ACTION_SAFETY_SET_UC4_3_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc4_3Enabled, "🚦 UC4.3")
        ACTION_SAFETY_SET_UC1_4_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc1_4Enabled, "🚲 UC1.4")
        ACTION_SAFETY_SET_UC1_2_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc1_2Enabled, "🚗 UC1.2")
        ACTION_SAFETY_SET_UC1_4_STRICT_ENABLED -> handleUcToggleFlow(intent, WearableService._safetyUc1_4StrictEnabled, "🚲 UC1.4 strict")
        ACTION_SAFETY_SET_UC4_5_ENABLED -> handleUc45Toggle(intent)
        ACTION_SAFETY_ADD_ZONE_HERE -> handleAddZoneHere(intent)
        ACTION_SAFETY_REMOVE_ZONE -> handleRemoveZone(intent)
        ACTION_SAFETY_REFRESH_OSM -> handleRefreshOsm()
        ACTION_ATCLL_SET_ENDPOINT -> handleAtcllEndpoint(intent)
        ACTION_DEPTH_SET_URL -> handleDepthUrl(intent)
        ACTION_UNIFIED_SERVER_SET_URL -> handleUnifiedServerUrl(intent)
        ACTION_AUDIO_NOTIFY -> handleAudioNotify(intent)
    }
}
