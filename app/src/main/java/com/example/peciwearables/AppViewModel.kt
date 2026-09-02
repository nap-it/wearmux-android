package com.example.peciwearables

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.example.peciwearables.integration.AlertPreferencesStore
import com.example.peciwearables.integration.BleConnectionCandidate
import com.example.peciwearables.integration.CapturedPhoto
import com.example.peciwearables.integration.DeveloperModeStore
import com.example.peciwearables.integration.MlProcessingLocation
import com.example.peciwearables.integration.NavisensImuSource
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.RecordedAudio
import com.example.peciwearables.integration.LatencySample
import com.example.peciwearables.integration.WearableService
import com.example.peciwearables.integration.WearableServiceActions
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.inference.InferenceMode
import com.example.peciwearables.integration.pdr.PdrPosition
import com.example.peciwearables.integration.pdr.SavedRoute
import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.protocol.WristbandImuSample
import com.example.peciwearables.integration.stt.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class Tab {
    DEVICES,
    STATUS,
    DEV_LAB,
    SETTINGS
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _selectedTab = MutableStateFlow(Tab.DEVICES)
    val selectedTab: StateFlow<Tab> = _selectedTab

    // Modo desenvolvimento — controla apenas visibilidade/acesso a UI técnica,
    // nunca o comportamento de runtime (BLE, câmara, IMU, telemetria, safety).
    private val developerModeStore = DeveloperModeStore(application)
    private val _isDeveloperMode = MutableStateFlow(developerModeStore.isEnabled())
    val isDeveloperMode: StateFlow<Boolean> = _isDeveloperMode

    fun setDeveloperMode(enabled: Boolean) {
        developerModeStore.setEnabled(enabled)
        _isDeveloperMode.value = enabled
    }

    // Preferências de alerta (vibração/som) — Definições (modo Normal).
    private val alertPreferencesStore = AlertPreferencesStore(application)
    private val _vibrationEnabled = MutableStateFlow(alertPreferencesStore.isVibrationEnabled())
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled
    private val _soundEnabled = MutableStateFlow(alertPreferencesStore.isSoundEnabled())
    val soundEnabled: StateFlow<Boolean> = _soundEnabled

    fun setVibrationEnabled(value: Boolean) {
        alertPreferencesStore.setVibrationEnabled(value)
        _vibrationEnabled.value = value
    }

    fun setSoundEnabled(value: Boolean) {
        alertPreferencesStore.setSoundEnabled(value)
        _soundEnabled.value = value
    }

    // Estados dos dispositivos (do servico)
    val glassesState: StateFlow<BleDeviceState> = WearableService.glassesState
    val wristbandState: StateFlow<BleDeviceState> = WearableService.wristbandState
    val esp32State: StateFlow<BleDeviceState> = WearableService.esp32State
    val wristbandActivity: StateFlow<String> = WearableService.wristbandActivity
    val udpActive: StateFlow<Boolean> = WearableService.udpActive
    val udpServerActive: StateFlow<Boolean> = WearableService.udpServerActive
    val stats: StateFlow<String> = WearableService.stats
    val glassesBattery: StateFlow<Int> = WearableService.glassesBattery
    val wristbandBattery: StateFlow<Int> = WearableService.wristbandBattery
    val glassesFirmware: StateFlow<String> = WearableService.glassesFirmware
    val cameraStatus: StateFlow<Int> = WearableService.cameraStatus
    val bleLog: StateFlow<String> = WearableService.bleLog
    val latestPhoto: StateFlow<Bitmap?> = WearableService.latestPhoto
    val photoCount: StateFlow<Int> = WearableService.photoCount
    val allPhotos: StateFlow<List<CapturedPhoto>> = WearableService.allPhotos
    val glassesIp: StateFlow<String?> = WearableService.glassesIp
    val bleCandidates: StateFlow<List<BleConnectionCandidate>> = WearableService.bleCandidates
    val candidateScanRunning: StateFlow<Boolean> = WearableService.candidateScanRunning
    val glassesMicStreaming: StateFlow<Boolean> = WearableService.glassesMicStreaming
    val glassesMicStatusText: StateFlow<String> = WearableService.glassesMicStatusText
    val glassesMicDataText: StateFlow<String> = WearableService.glassesMicDataText
    val glassesMicSampleRateHz: StateFlow<Int> = WearableService.glassesMicSampleRateHz
    val glassesMicBitDepth: StateFlow<Int> = WearableService.glassesMicBitDepth
    val glassesMicPlaybackSupported: StateFlow<Boolean> = WearableService.glassesMicPlaybackSupported
    val audioRecordingActive: StateFlow<Boolean> = WearableService.audioRecordingActive
    val glassesWifiSupported: StateFlow<Boolean> = WearableService.glassesWifiSupported
    val glassesWifiConnectionEnabled: StateFlow<Boolean?> = WearableService.glassesWifiConnectionEnabled
    val glassesWifiConnected: StateFlow<Boolean?> = WearableService.glassesWifiConnected
    val glassesWifiSecure: StateFlow<Boolean?> = WearableService.glassesWifiSecure
    val recordedAudios: StateFlow<List<RecordedAudio>> = WearableService.recordedAudios
    val whisperConnected: StateFlow<Boolean> = WearableService.whisperConnected
    val whisperTranscription: StateFlow<List<WhisperSegment>> = WearableService.whisperTranscription
    val lastWhisperText: StateFlow<String> = WearableService.lastWhisperText
    val latestImuSamples: StateFlow<List<ImuSample>> = WearableService.latestImuSamples
    val latestGlassesImu: StateFlow<GlassesImuSample?> = WearableService.latestGlassesImu
    val glassesImuStream: SharedFlow<GlassesImuSample> = WearableService.glassesImuStream
    val wristbandImuStream: SharedFlow<WristbandImuSample> = WearableService.wristbandImuStream
    /** Stream IMU fundido (≥1 fonte) em SI — alimenta Navisens em FUSED. */
    val fusedImuStream: SharedFlow<GlassesImuSample> = WearableService.fusedImuStream
    /** Quantos wearables estão a contribuir agora (0..3). */
    val fusedImuSourceCount: StateFlow<Int> = WearableService.fusedImuSourceCount
    val latestGlassesQuaternion: StateFlow<FloatArray?> = WearableService.latestGlassesQuaternion
    val latestGyIntegralDeg: StateFlow<Float?> = WearableService.latestGyIntegralDeg
    val latestGySpreadDeg: StateFlow<Float?> = WearableService.latestGySpreadDeg
    val glassesConnectionMode: StateFlow<GlassesConnectionMode> = WearableService.glassesConnectionMode
    val mlProcessingLocation: StateFlow<MlProcessingLocation> = WearableService.mlProcessingLocation
    val mlServerUrl: StateFlow<String> = WearableService.mlServerUrl

    // Compat com fluxo de stream continuo (commit 3d724df)
    val isStreaming: StateFlow<Boolean> = WearableService.isStreaming
    val streamFps: StateFlow<Float> = WearableService.streamFps
    val streamFrameCount: StateFlow<Int> = WearableService.streamFrameCount

    // Estado agregado da stream de câmara dos óculos (Fase 2)
    val cameraStreamHealth: StateFlow<com.example.peciwearables.integration.image.camera.CameraStreamHealth> =
        WearableService.cameraStreamHealth
    // Identificador/MAC da sessão de óculos atualmente ligada (Fase 2)
    val glassesConnectedAddress: StateFlow<String?> = WearableService.glassesConnectedAddress

    // Imagem da câmara BLE/UDP
    val latestCameraBitmap: StateFlow<Bitmap?> = WearableService.latestCameraBitmap
    val latestPhotoLatency: StateFlow<LatencySample?> = WearableService.latestPhotoLatency
    val latestMicrophoneLatency: StateFlow<LatencySample?> = WearableService.latestMicrophoneLatency

    // Phone sensors / PDR / Route
    val phoneSensorsActive: StateFlow<Boolean> = WearableService.phoneSensorsActive
    val phoneGps: StateFlow<com.example.peciwearables.integration.sensors.PhoneGpsLocation?> = WearableService.phoneGps
    val pdrPosition: StateFlow<PdrPosition?> = WearableService.pdrPosition
    val pdrStepCount: StateFlow<Int> = WearableService.pdrStepCount
    val savedRoutes: StateFlow<List<SavedRoute>> = WearableService.savedRoutes
    val activeRoute: StateFlow<SavedRoute?> = WearableService.activeRoute
    val routeRecording: StateFlow<Boolean> = WearableService.routeRecording
    val routeRecordingWaypointCount: StateFlow<Int> = WearableService.routeRecordingWaypointCount
    val routeRecordingWaypoints: StateFlow<List<Pair<Double, Double>>> = WearableService.routeRecordingWaypoints

    // IMU streaming
    val imuStreamingEnabled: StateFlow<Boolean> = WearableService.imuStreamingEnabled

    // Glasses YOLO inference
    val glassesInferenceMode: StateFlow<InferenceMode> = WearableService.glassesInferenceMode
    val glassesInferenceCloudUrl: StateFlow<String> = WearableService.glassesInferenceCloudUrl

    // Estado de conexão ao servidor YOLO (null = nunca tentou, true = ok, false = falhou)
    val yoloServerConnected: MutableStateFlow<Boolean?> = MutableStateFlow(null)

    // UC4.1 — narrador de cena contínuo (toggle no painel de debug)
    val uc41Enabled: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // Galaxy Watch (Wear OS)
    val watchState: StateFlow<WatchClient.State> = WearableService.watchState
    val watchName: StateFlow<String?> = WearableService.watchName
    val watchBattery: StateFlow<Int> = WearableService.watchBattery
    val watchSampleRateHz: StateFlow<Int> = WearableService.watchSampleRateHz
    val watchSamplesPerSec: StateFlow<Int> = WearableService.watchSamplesPerSec
    val watchHeartRateBpm: StateFlow<Int> = WearableService.watchHeartRateBpm
    val watchLastError: StateFlow<String?> = WearableService.watchLastError
    val watchImuStream: SharedFlow<ImuSample> = WearableService.watchImuStream
    val navisensImuSource: StateFlow<NavisensImuSource> = WearableService.navisensImuSource

    fun setTab(tab: Tab) {
        _selectedTab.value = tab
    }

    fun getTab(): Tab {
        return _selectedTab.value
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopService() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_STOP
        }
        context.startService(intent)
    }

    fun connectGlasses() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_GLASSES
        }
        context.startService(intent)
    }

    fun connectWristband() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_WRISTBAND
        }
        context.startService(intent)
    }

    fun connectEsp32() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_ESP32
        }
        context.startService(intent)
    }

    fun disconnectGlasses() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_DISCONNECT_GLASSES
        }
        context.startService(intent)
    }

    fun disconnectWristband() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_DISCONNECT_WRISTBAND
        }
        context.startService(intent)
    }

    fun connectAuto() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_AUTO
        }
        context.startService(intent)
    }

    fun discoverBleCandidates() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_DISCOVER_CANDIDATES
        }
        context.startService(intent)
    }

    fun stopDiscoverBleCandidates() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_STOP_DISCOVER_CANDIDATES
        }
        context.startService(intent)
    }

    fun connectCandidate(address: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_CANDIDATE
            putExtra(WearableServiceActions.EXTRA_CANDIDATE_ADDRESS, address)
        }
        context.startService(intent)
    }

    fun vibrateStop() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_VIBRATE_STOP
        }
        context.startService(intent)
    }

    fun vibrateGo() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_VIBRATE_GO
        }
        context.startService(intent)
    }

    fun capturePhoto() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CAPTURE_PHOTO
        }
        context.startService(intent)
    }

    fun takePicture() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_TAKE_PICTURE
        }
        context.startService(intent)
    }

    fun wakeCamera() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_WAKE_CAMERA
        }
        context.startService(intent)
    }

    fun startMicrophone() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_START_MICROPHONE
        }
        context.startService(intent)
    }

    fun stopMicrophone() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_STOP_MICROPHONE
        }
        context.startService(intent)
    }

    fun applyGlassesMicrophoneProfile(sampleRateHz: Int, bitDepth: Int) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_APPLY_GLASSES_MICROPHONE_PROFILE
            putExtra(WearableServiceActions.EXTRA_MICROPHONE_SAMPLE_RATE, sampleRateHz)
            putExtra(WearableServiceActions.EXTRA_MICROPHONE_BIT_DEPTH, bitDepth)
        }
        context.startService(intent)
    }

    fun startAudioRecording() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_START_AUDIO_RECORDING
        }
        context.startService(intent)
    }

    fun stopAudioRecording() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_STOP_AUDIO_RECORDING
        }
        context.startService(intent)
    }

    fun sendWifiConfig(ssid: String, password: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_SEND_WIFI
            putExtra(WearableServiceActions.EXTRA_SSID, ssid)
            putExtra(WearableServiceActions.EXTRA_PASSWORD, password)
        }
        context.startService(intent)
    }

    fun connectWifi() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_CONNECT_UDP
        }
        context.startService(intent)
    }

    fun setGlassesConnectionMode(mode: GlassesConnectionMode) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_SET_GLASSES_CONNECTION_MODE
            putExtra(WearableServiceActions.EXTRA_GLASSES_CONNECTION_MODE, mode.name)
        }
        context.startService(intent)
    }

    fun startStream() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_START_STREAM
        }
        context.startService(intent)
    }

    fun stopStream() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_STOP_STREAM
        }
        context.startService(intent)
    }

    fun triggerImuMlBenchmark() {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_TRIGGER_IMU_ML_BENCHMARK
        }
        context.startService(intent)
    }

    fun setMlProcessingLocation(location: MlProcessingLocation) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_SET_ML_PROCESSING_LOCATION
            putExtra(WearableServiceActions.EXTRA_ML_PROCESSING_LOCATION, location.name)
        }
        context.startForegroundService(intent)
    }

    fun setMlServerUrl(url: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_SET_ML_SERVER_URL
            putExtra(WearableServiceActions.EXTRA_ML_SERVER_URL, url)
        }
        context.startForegroundService(intent)
    }

    fun applyGlassesCameraProfile(resolution: Int, qualityFactor: Int, cameraRateMs: Int) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            action = WearableServiceActions.ACTION_APPLY_GLASSES_CAMERA_PROFILE
            putExtra(WearableServiceActions.EXTRA_CAMERA_RESOLUTION, resolution)
            putExtra(WearableServiceActions.EXTRA_CAMERA_QUALITY_FACTOR, qualityFactor)
            putExtra(WearableServiceActions.EXTRA_CAMERA_RATE_MS, cameraRateMs)
        }
        context.startService(intent)
    }

    fun getLocalIp(): String {
        return WearableService.instance?.getLocalIp() ?: "N/A"
    }

    fun clearBleLog() {
        WearableService.clearLog()
    }

    // -------- Phone sensors / PDR --------

    private fun sendAction(action: String, configure: Intent.() -> Unit = {}) {
        val context = getApplication<Application>()
        val intent = Intent(context, WearableService::class.java).apply {
            this.action = action
            configure()
        }
        context.startService(intent)
    }

    fun startPhoneSensors() = sendAction(WearableServiceActions.ACTION_START_PHONE_SENSORS)
    fun stopPhoneSensors() = sendAction(WearableServiceActions.ACTION_STOP_PHONE_SENSORS)
    fun resetPdr() = sendAction(WearableServiceActions.ACTION_PDR_RESET)

    // -------- Route management --------

    fun routeStartRecording() = sendAction(WearableServiceActions.ACTION_ROUTE_START_RECORDING)
    fun routeFinishRecording(name: String) = sendAction(WearableServiceActions.ACTION_ROUTE_FINISH) {
        putExtra(WearableServiceActions.EXTRA_ROUTE_NAME, name)
    }
    fun routeCancelRecording() = sendAction(WearableServiceActions.ACTION_ROUTE_CANCEL)
    fun routeActivate(id: String) = sendAction(WearableServiceActions.ACTION_ROUTE_ACTIVATE) {
        putExtra(WearableServiceActions.EXTRA_ROUTE_ID, id)
    }
    fun routeDeactivate() = sendAction(WearableServiceActions.ACTION_ROUTE_DEACTIVATE)
    fun routeDelete(id: String) = sendAction(WearableServiceActions.ACTION_ROUTE_DELETE) {
        putExtra(WearableServiceActions.EXTRA_ROUTE_ID, id)
    }

    // -------- Glasses YOLO inference --------

    fun setGlassesInferenceMode(mode: InferenceMode) =
        sendAction(WearableServiceActions.ACTION_SET_GLASSES_INFERENCE_MODE) {
            putExtra(WearableServiceActions.EXTRA_GLASSES_INFERENCE_MODE, mode.name)
        }

    fun setGlassesInferenceUrl(url: String) =
        sendAction(WearableServiceActions.ACTION_SET_GLASSES_INFERENCE_URL) {
            putExtra(WearableServiceActions.EXTRA_GLASSES_INFERENCE_URL, url)
        }

    fun connectYoloServer(url: String) {
        setGlassesInferenceUrl(url)
        setGlassesInferenceMode(InferenceMode.CLOUD)
    }

    fun disconnectYoloServer() {
        setGlassesInferenceMode(InferenceMode.LOCAL)
        yoloServerConnected.value = null
    }

    // -------- IMU streaming --------

    fun setImuStreaming(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SET_IMU_STREAMING) {
            putExtra(WearableServiceActions.EXTRA_IMU_STREAMING_ENABLED, enabled)
        }

    fun whisperConnect(host: String, port: Int) =
        sendAction(WearableServiceActions.ACTION_WHISPER_CONNECT) {
            putExtra(WearableServiceActions.EXTRA_WHISPER_HOST, host)
            putExtra(WearableServiceActions.EXTRA_WHISPER_PORT, port)
        }

    fun whisperDisconnect() = sendAction(WearableServiceActions.ACTION_WHISPER_DISCONNECT)

    fun applyMicProfile(sampleRateHz: Int, bitDepth: Int) =
        sendAction(WearableServiceActions.ACTION_APPLY_GLASSES_MICROPHONE_PROFILE) {
            putExtra(WearableServiceActions.EXTRA_MICROPHONE_SAMPLE_RATE, sampleRateHz)
            putExtra(WearableServiceActions.EXTRA_MICROPHONE_BIT_DEPTH, bitDepth)
        }

    // -------- Galaxy Watch (Wear OS) --------

    fun watchStartImu() = sendAction(WearableServiceActions.ACTION_WATCH_START_IMU)
    fun watchStopImu() = sendAction(WearableServiceActions.ACTION_WATCH_STOP_IMU)
    fun watchSetSampleRate(hz: Int) = sendAction(WearableServiceActions.ACTION_WATCH_SET_RATE) {
        putExtra(WearableServiceActions.EXTRA_WATCH_RATE_HZ, hz)
    }
    fun watchBeep(freqHz: Int = 880, durationMs: Int = 250) =
        sendAction(WearableServiceActions.ACTION_WATCH_BEEP) {
            putExtra(WearableServiceActions.EXTRA_TONE_FREQ_HZ, freqHz)
            putExtra(WearableServiceActions.EXTRA_TONE_DURATION_MS, durationMs)
        }
    /** Patterns: 0=short, 1=double, 2=long, 3=triple, 4=heartbeat, 5=alarm. */
    fun watchVibrate(pattern: Int) =
        sendAction(WearableServiceActions.ACTION_WATCH_VIBRATE) {
            putExtra(WearableServiceActions.EXTRA_VIBRATE_PATTERN, pattern)
        }

    /** Mostra notificação especial no watch. type: 0=AVISO, 1=PERIGO. */
    fun watchNotify(type: Int, title: String, body: String) =
        sendAction(WearableServiceActions.ACTION_WATCH_NOTIFY) {
            putExtra(WearableServiceActions.EXTRA_NOTIFY_TYPE, type)
            putExtra(WearableServiceActions.EXTRA_WATCH_NOTIFY_TITLE, title)
            putExtra(WearableServiceActions.EXTRA_WATCH_NOTIFY_BODY, body)
        }
    fun setNavisensImuSource(source: NavisensImuSource) =
        sendAction(WearableServiceActions.ACTION_SET_NAVISENS_IMU_SOURCE) {
            putExtra(WearableServiceActions.EXTRA_NAVISENS_IMU_SOURCE, source.name)
        }

    // -------- Áudio teste no telemóvel --------

    fun playAudioPreset(preset: AudioTestEngine.TonePreset) =
        sendAction(WearableServiceActions.ACTION_AUDIO_TEST_TONE) {
            putExtra(WearableServiceActions.EXTRA_TONE_PRESET, preset.name)
        }

    fun playAudioTone(freqHz: Int, durationMs: Int) =
        sendAction(WearableServiceActions.ACTION_AUDIO_TEST_TONE) {
            putExtra(WearableServiceActions.EXTRA_TONE_FREQ_HZ, freqHz)
            putExtra(WearableServiceActions.EXTRA_TONE_DURATION_MS, durationMs)
        }

    fun stopAudioTest() = sendAction(WearableServiceActions.ACTION_AUDIO_TEST_STOP)

    // -------- Safety (UC1.1 + UC1.3) --------

    val safetyUc1Enabled: StateFlow<Boolean> = WearableService.safetyUc1Enabled
    val safetyUc3Enabled: StateFlow<Boolean> = WearableService.safetyUc3Enabled
    val crossingZones: StateFlow<List<com.example.peciwearables.integration.safety.CrossingZone>> = WearableService.crossingZones
    val osmZonesStatus: StateFlow<com.example.peciwearables.integration.safety.CrossingZoneManager.OsmStatus> = WearableService.osmZonesStatus
    val pedestrianDecision: StateFlow<com.example.peciwearables.integration.safety.PedestrianSafetyDecision> = WearableService.pedestrianDecision
    val crossingDecision: StateFlow<com.example.peciwearables.integration.safety.CrossingValidationDecision> = WearableService.crossingDecision

    fun safetySetUc1(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC1_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun safetySetUc3(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC3_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun safetyAddZoneHere(name: String? = null, radiusMeters: Float = 15f) =
        sendAction(WearableServiceActions.ACTION_SAFETY_ADD_ZONE_HERE) {
            name?.let { putExtra(WearableServiceActions.EXTRA_SAFETY_ZONE_NAME, it) }
            putExtra(WearableServiceActions.EXTRA_SAFETY_ZONE_RADIUS, radiusMeters)
        }
    fun safetyRemoveZone(id: String) =
        sendAction(WearableServiceActions.ACTION_SAFETY_REMOVE_ZONE) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ZONE_ID, id)
        }
    fun safetyRefreshOsm() = sendAction(WearableServiceActions.ACTION_SAFETY_REFRESH_OSM)

    // Diagnostics dos UCs — para painel debug.
    val safetyLog: StateFlow<List<String>> = WearableService.safetyLog
    val headRotationSpreadDeg: StateFlow<Float> = WearableService.headRotationSpreadDeg
    val motionPeakCount: StateFlow<Int> = WearableService.motionPeakCount
    val decelerationRatio: StateFlow<Float> = WearableService.decelerationRatio
    val imuRateHz: StateFlow<Int> = WearableService.imuRateHz

    val safetyUc2Enabled: StateFlow<Boolean> = WearableService.safetyUc2Enabled
    val safetyUc3IncomingEnabled: StateFlow<Boolean> = WearableService.safetyUc3IncomingEnabled
    val safetyUc4_3Enabled: StateFlow<Boolean> = WearableService.safetyUc4_3Enabled
    val safetyUc1_4Enabled: StateFlow<Boolean> = WearableService.safetyUc1_4Enabled
    val cyclistMode: StateFlow<com.example.peciwearables.integration.safety.CyclistModeDetector.Mode> = WearableService.cyclistMode
    val atcllStatus: StateFlow<com.example.peciwearables.integration.atcll.AtcllClient.Status> = WearableService.atcllStatus
    val atcllEndpoint: StateFlow<String?> = WearableService.atcllEndpoint
    val unifiedServerUrl: StateFlow<String> = WearableService.unifiedServerUrl

    fun unifiedServerSetUrl(url: String) =
        sendAction(WearableServiceActions.ACTION_UNIFIED_SERVER_SET_URL) {
            putExtra(WearableServiceActions.EXTRA_UNIFIED_SERVER_URL, url)
        }

    fun safetySetUc2(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC2_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun safetySetUc3Incoming(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC3_INCOMING_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun safetySetUc4_3(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC4_3_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun safetySetUc1_4(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC1_4_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun atcllSetEndpoint(url: String?) =
        sendAction(WearableServiceActions.ACTION_ATCLL_SET_ENDPOINT) {
            url?.let { putExtra(WearableServiceActions.EXTRA_ATCLL_ENDPOINT, it) }
        }

    // UC1.4 estrito — alerta ciclista
    val safetyUc1_4StrictEnabled: StateFlow<Boolean> = WearableService.safetyUc1_4StrictEnabled
    fun safetySetUc1_4Strict(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC1_4_STRICT_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }

    // UC4.5 — Transcrição em tempo real
    val safetyUc4_5Enabled: StateFlow<Boolean> = WearableService.safetyUc4_5Enabled
    fun safetySetUc4_5(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC4_5_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }

    // UC1.2 — Veículo a aproximar (YOLO + Depth)
    val safetyUc1_2Enabled: StateFlow<Boolean> = WearableService.safetyUc1_2Enabled
    val vehicleApproachDecision: StateFlow<com.example.peciwearables.integration.safety.VehicleApproachEvaluator.Decision> =
        WearableService.vehicleApproachDecision
    fun safetySetUc1_2(enabled: Boolean) =
        sendAction(WearableServiceActions.ACTION_SAFETY_SET_UC1_2_ENABLED) {
            putExtra(WearableServiceActions.EXTRA_SAFETY_ENABLED, enabled)
        }
    fun depthSetEndpoint(url: String) =
        sendAction(WearableServiceActions.ACTION_DEPTH_SET_URL) {
            putExtra(WearableServiceActions.EXTRA_DEPTH_URL, url)
        }

    fun playAudioNotification(
        title: String = "Sinal PECI",
        body: String = "Tom de teste",
        freqHz: Int = 880,
        durationMs: Int = 350,
    ) = sendAction(WearableServiceActions.ACTION_AUDIO_NOTIFY) {
        putExtra(WearableServiceActions.EXTRA_NOTIFY_TITLE, title)
        putExtra(WearableServiceActions.EXTRA_NOTIFY_BODY, body)
        putExtra(WearableServiceActions.EXTRA_TONE_FREQ_HZ, freqHz)
        putExtra(WearableServiceActions.EXTRA_TONE_DURATION_MS, durationMs)
    }
}
