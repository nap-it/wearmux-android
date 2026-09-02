package com.example.peciwearables.integration

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import com.example.peciwearables.R
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.audio.AudioPipeline
import com.example.peciwearables.integration.pdr.PedestrianDeadReckoning
import com.example.peciwearables.integration.pdr.PdrPosition
import com.example.peciwearables.integration.pdr.RouteManager
import com.example.peciwearables.integration.pdr.SavedRoute
import com.example.peciwearables.integration.sensors.PhoneAccelSample
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import com.example.peciwearables.integration.sensors.PhoneSensorManager
import com.example.peciwearables.integration.inference.InferenceManager
import com.example.peciwearables.integration.inference.InferenceMode
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClient
import com.example.peciwearables.integration.ble.devices.brilliantsole.BrilliantSoleWristbandBleClientApi
import com.example.peciwearables.integration.ble.devices.omi.OmiGlassesBleClientApi
import com.example.peciwearables.integration.ble.CapabilityDiscoveryScanner
import com.example.peciwearables.integration.image.BleCameraPipeline
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.ble.WearableKind
import com.example.peciwearables.integration.ble.GlassesMicrophoneProfile
import com.example.peciwearables.integration.image.ImagePipeline
import com.example.peciwearables.integration.stt.whisper.WhisperSegment
import com.example.peciwearables.integration.wearable.DefaultWearableHub
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableAdapter
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableAdapter
import com.example.peciwearables.integration.wearable.legacy.WearableServiceLegacyBridge
import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.latency.CloudLatencyReporter
import com.example.peciwearables.integration.ml.PeciOnDeviceClassifier
import com.example.peciwearables.integration.ml.PeciServerClassifier
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.managers.GlassesCameraManager
import com.example.peciwearables.integration.managers.GlassesMicrophoneManager
import com.example.peciwearables.integration.sync.TimeSyncManager
import com.example.peciwearables.integration.udp.UdpServer
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.safety.AtcllSafetyAlerts
import com.example.peciwearables.integration.safety.CrossingIntentionEvaluator
import com.example.peciwearables.integration.safety.CrossingZone
import com.example.peciwearables.integration.safety.CrossingZoneManager
import com.example.peciwearables.integration.safety.CrossingZoneStore
import com.example.peciwearables.integration.safety.CrossingValidationDecision
import com.example.peciwearables.integration.safety.PedestrianSafetyDecision
import com.example.peciwearables.integration.safety.CyclistModeDetector
import com.example.peciwearables.integration.safety.DefaultSafetyDecisionFeed
import com.example.peciwearables.integration.safety.SafetyDiagnostics
import com.example.peciwearables.integration.safety.SafetyEventBus
import com.example.peciwearables.integration.safety.SafetyOrchestrator
import com.example.peciwearables.integration.safety.SafetyToggles
import com.example.peciwearables.integration.atcll.AtcllClient
import com.example.peciwearables.integration.audio.AmbientSoundClassifier
import com.example.peciwearables.integration.depth.DepthManager
import com.example.peciwearables.integration.safety.VehicleApproachEvaluator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class CapturedPhoto(val bitmap: android.graphics.Bitmap, val timestamp: String, val index: Int)

data class RecordedAudio(
    val filePath: String,
    val timestamp: String,
    val index: Int,
    val durationSec: Double,
    val sampleRateHz: Int,
)


data class BleConnectionCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val kind: WearableKind,
    val sdkDeviceType: String?,
    val ipAddress: String?,
    val isWifiSecure: Boolean?,
)

data class LatencySample(
    val route: String,
    val totalMs: Long,
    val phoneProcessingMs: Long,
    val transportEstimateMs: Long,
    val glassesProcessingEstimateMs: Long,
    val timestamp: String,
)

internal data class GlassesMicrophoneProfileApplyDecision(
    val canApply: Boolean,
    val shouldRestartStream: Boolean,
    val reason: String? = null,
)

internal fun decideGlassesMicrophoneProfileApply(
    glassesState: BleDeviceState,
    micStreaming: Boolean,
): GlassesMicrophoneProfileApplyDecision {
    val isConnected = glassesState == BleDeviceState.READY || glassesState == BleDeviceState.CONNECTED
    if (!isConnected) {
        return GlassesMicrophoneProfileApplyDecision(
            canApply = false,
            shouldRestartStream = false,
            reason = "Omi nao conectado (estado=${glassesState.name})",
        )
    }
    return GlassesMicrophoneProfileApplyDecision(
        canApply = true,
        shouldRestartStream = micStreaming,
    )
}

/** Foreground service: BLE/UDP streaming + safety dispatch + NSD/mDNS + telemetria cloud. */
class WearableService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "WearableService"
        private const val NOTIFICATION_ID = 1001
        private const val NSD_SERVICE_TYPE = "_omi._udp."
        private const val NSD_SERVICE_NAME = "PeciWearables"
        const val UDP_PORT = 3000

        internal const val ATCLL_PREFS = "peci_cloud_prefs"
        internal const val ATCLL_PREF_URL = "atcll_endpoint"
        internal const val DEPTH_PREF_URL = "depth_endpoint"
        private const val TELEMETRY_PREF_URL = "telemetry_endpoint"
        private const val TELEMETRY_DEFAULT_URL = CloudConfig.DEFAULT_BASE_URL
        internal const val UNIFIED_SERVER_PREF_URL = "unified_server_url"
        internal const val UNIFIED_SERVER_DEFAULT_URL = CloudConfig.DEFAULT_BASE_URL
        internal const val IMU_PREF_URL = "imu_endpoint"

        internal const val GLASSES_MIC_TRANSITION_TIMEOUT_MS = 3_000L
        enum class GlassesMicStreamState { IDLE, STARTING, STREAMING, STOPPING, ERROR }
        enum class GlassesCameraStreamState { IDLE, STARTING, STREAMING, STOPPING, ERROR }

        internal val _glassesState = MutableStateFlow(BleDeviceState.DISCONNECTED); val glassesState: StateFlow<BleDeviceState> = _glassesState
        internal val _wristbandState = MutableStateFlow(BleDeviceState.DISCONNECTED); val wristbandState: StateFlow<BleDeviceState> = _wristbandState
        internal val _esp32State = MutableStateFlow(BleDeviceState.DISCONNECTED); val esp32State: StateFlow<BleDeviceState> = _esp32State
        val wristbandActivity = MutableStateFlow("A aguardar inferência...")
        internal val _mlProcessingLocation = MutableStateFlow(MlProcessingLocation.APP); val mlProcessingLocation: StateFlow<MlProcessingLocation> = _mlProcessingLocation
        internal val _mlServerUrl = MutableStateFlow(""); val mlServerUrl: StateFlow<String> = _mlServerUrl
        internal val _udpActive = MutableStateFlow(false); val udpActive: StateFlow<Boolean> = _udpActive
        internal val _udpServerActive = MutableStateFlow(false); val udpServerActive: StateFlow<Boolean> = _udpServerActive
        internal val _latestImuSamples = MutableStateFlow<List<ImuSample>>(emptyList()); val latestImuSamples: StateFlow<List<ImuSample>> = _latestImuSamples
        internal val _latestGlassesImu = MutableStateFlow<GlassesImuSample?>(null); val latestGlassesImu: StateFlow<GlassesImuSample?> = _latestGlassesImu
        internal val _glassesImuStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val glassesImuStream: SharedFlow<GlassesImuSample> = _glassesImuStream
        internal val _wristbandImuStream = MutableSharedFlow<com.example.peciwearables.integration.protocol.WristbandImuSample>(replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val wristbandImuStream: SharedFlow<com.example.peciwearables.integration.protocol.WristbandImuSample> = _wristbandImuStream
        internal val _latestGlassesQuaternion = MutableStateFlow<FloatArray?>(null); val latestGlassesQuaternion: StateFlow<FloatArray?> = _latestGlassesQuaternion
        internal val _latestGyIntegralDeg = MutableStateFlow<Float?>(null); val latestGyIntegralDeg: StateFlow<Float?> = _latestGyIntegralDeg
        internal val _latestGySpreadDeg = MutableStateFlow<Float?>(null); val latestGySpreadDeg: StateFlow<Float?> = _latestGySpreadDeg
        internal val _latestGxIntegralDeg = MutableStateFlow<Float?>(null)
        internal val _latestGzIntegralDeg = MutableStateFlow<Float?>(null)
        internal val _imuStreamingEnabled = MutableStateFlow(false); val imuStreamingEnabled: StateFlow<Boolean> = _imuStreamingEnabled
        internal val _stats = MutableStateFlow(""); val stats: StateFlow<String> = _stats
        internal val _glassesBattery = MutableStateFlow(-1); val glassesBattery: StateFlow<Int> = _glassesBattery
        internal val _glassesFirmware = MutableStateFlow(""); val glassesFirmware: StateFlow<String> = _glassesFirmware
        internal val _wristbandBattery = MutableStateFlow(-1); val wristbandBattery: StateFlow<Int> = _wristbandBattery
        internal val _wristbandFirmware = MutableStateFlow(""); val wristbandFirmware: StateFlow<String> = _wristbandFirmware
        internal val _cameraStatus = MutableStateFlow(-1); val cameraStatus: StateFlow<Int> = _cameraStatus
        internal val _latestCameraBitmap = MutableStateFlow<android.graphics.Bitmap?>(null); val latestCameraBitmap: StateFlow<android.graphics.Bitmap?> = _latestCameraBitmap
        internal val _phoneCameraBitmap = MutableStateFlow<android.graphics.Bitmap?>(null); val phoneCameraBitmap: StateFlow<android.graphics.Bitmap?> = _phoneCameraBitmap
        internal val _cameraSource = MutableStateFlow(CameraSource.PHONE); val cameraSource: StateFlow<CameraSource> = _cameraSource

        fun updatePhoneCameraBitmap(bitmap: android.graphics.Bitmap) { _phoneCameraBitmap.value = bitmap }
        fun updateCameraSource(source: CameraSource) {
            if (_cameraSource.value == source) return
            _cameraSource.value = source
            if (source == CameraSource.GLASSES) _phoneCameraBitmap.value = null
        }

        internal val _latestBitmap = MutableStateFlow<android.graphics.Bitmap?>(null); val latestBitmap: StateFlow<android.graphics.Bitmap?> = _latestBitmap
        internal val _bleLog = MutableStateFlow(""); val bleLog: StateFlow<String> = _bleLog
        internal val _latestPhoto = MutableStateFlow<Bitmap?>(null); val latestPhoto: StateFlow<Bitmap?> = _latestPhoto
        internal val _photoCount = MutableStateFlow(0); val photoCount: StateFlow<Int> = _photoCount
        internal val _isStreaming = MutableStateFlow(false); val isStreaming: StateFlow<Boolean> = _isStreaming
        internal val _streamFps = MutableStateFlow(0f); val streamFps: StateFlow<Float> = _streamFps
        internal val _streamFrameCount = MutableStateFlow(0); val streamFrameCount: StateFlow<Int> = _streamFrameCount
        internal val _lastFrameTimestampMs = MutableStateFlow<Long?>(null); val lastFrameTimestampMs: StateFlow<Long?> = _lastFrameTimestampMs
        internal val _cameraStreamHealth = MutableStateFlow(com.example.peciwearables.integration.image.camera.CameraStreamHealth.EMPTY)
        val cameraStreamHealth: StateFlow<com.example.peciwearables.integration.image.camera.CameraStreamHealth> = _cameraStreamHealth
        internal val _glassesConnectedAddress = MutableStateFlow<String?>(null); val glassesConnectedAddress: StateFlow<String?> = _glassesConnectedAddress
        internal val _allPhotos = MutableStateFlow<List<CapturedPhoto>>(emptyList()); val allPhotos: StateFlow<List<CapturedPhoto>> = _allPhotos
        internal val _glassesIp = MutableStateFlow<String?>(null); val glassesIp: StateFlow<String?> = _glassesIp
        internal val _bleCandidates = MutableStateFlow<List<BleConnectionCandidate>>(emptyList()); val bleCandidates: StateFlow<List<BleConnectionCandidate>> = _bleCandidates
        internal val _candidateScanRunning = MutableStateFlow(false); val candidateScanRunning: StateFlow<Boolean> = _candidateScanRunning
        internal val _glassesMicStreaming = MutableStateFlow(false); val glassesMicStreaming: StateFlow<Boolean> = _glassesMicStreaming
        internal val _glassesMicState = MutableStateFlow(GlassesMicStreamState.IDLE); val glassesMicStreamState: StateFlow<GlassesMicStreamState> = _glassesMicState
        internal val _glassesCameraState = MutableStateFlow(GlassesCameraStreamState.IDLE); val glassesCameraStreamState: StateFlow<GlassesCameraStreamState> = _glassesCameraState
        internal val _glassesConnectionMode = MutableStateFlow(GlassesConnectionMode.BLE); val glassesConnectionMode: StateFlow<GlassesConnectionMode> = _glassesConnectionMode
        internal val _glassesWifiSupported = MutableStateFlow(false); val glassesWifiSupported: StateFlow<Boolean> = _glassesWifiSupported
        internal val _glassesWifiConnectionEnabled = MutableStateFlow<Boolean?>(null); val glassesWifiConnectionEnabled: StateFlow<Boolean?> = _glassesWifiConnectionEnabled
        internal val _glassesWifiConnected = MutableStateFlow<Boolean?>(null); val glassesWifiConnected: StateFlow<Boolean?> = _glassesWifiConnected
        internal val _glassesWifiSecure = MutableStateFlow<Boolean?>(null); val glassesWifiSecure: StateFlow<Boolean?> = _glassesWifiSecure
        internal val _glassesMicStatusText = MutableStateFlow("IDLE"); val glassesMicStatusText: StateFlow<String> = _glassesMicStatusText
        internal val _glassesMicDataText = MutableStateFlow("Sem dados"); val glassesMicDataText: StateFlow<String> = _glassesMicDataText
        internal val _glassesMicSampleRateHz = MutableStateFlow(16_000); val glassesMicSampleRateHz: StateFlow<Int> = _glassesMicSampleRateHz
        internal val _glassesMicBitDepth = MutableStateFlow(8); val glassesMicBitDepth: StateFlow<Int> = _glassesMicBitDepth
        internal val _glassesMicPlaybackSupported = MutableStateFlow(true); val glassesMicPlaybackSupported: StateFlow<Boolean> = _glassesMicPlaybackSupported
        internal val _audioRecordingActive = MutableStateFlow(false); val audioRecordingActive: StateFlow<Boolean> = _audioRecordingActive
        internal val _recordedAudios = MutableStateFlow<List<RecordedAudio>>(emptyList()); val recordedAudios: StateFlow<List<RecordedAudio>> = _recordedAudios
        internal val _whisperConnected = MutableStateFlow(false); val whisperConnected: StateFlow<Boolean> = _whisperConnected
        internal val _whisperTranscription = MutableStateFlow<List<WhisperSegment>>(emptyList()); val whisperTranscription: StateFlow<List<WhisperSegment>> = _whisperTranscription
        internal val _lastWhisperText = MutableStateFlow(""); val lastWhisperText: StateFlow<String> = _lastWhisperText
        internal val _latestPhotoLatency = MutableStateFlow<LatencySample?>(null); val latestPhotoLatency: StateFlow<LatencySample?> = _latestPhotoLatency
        internal val _latestMicrophoneLatency = MutableStateFlow<LatencySample?>(null); val latestMicrophoneLatency: StateFlow<LatencySample?> = _latestMicrophoneLatency
        internal val _phoneGps = MutableStateFlow<PhoneGpsLocation?>(null); val phoneGps: StateFlow<PhoneGpsLocation?> = _phoneGps
        internal val _phoneAccel = MutableStateFlow<PhoneAccelSample?>(null); val phoneAccel: StateFlow<PhoneAccelSample?> = _phoneAccel
        internal val _phoneSensorsActive = MutableStateFlow(false); val phoneSensorsActive: StateFlow<Boolean> = _phoneSensorsActive
        internal val _pdrPosition = MutableStateFlow<PdrPosition?>(null); val pdrPosition: StateFlow<PdrPosition?> = _pdrPosition
        internal val _pdrStepCount = MutableStateFlow(0); val pdrStepCount: StateFlow<Int> = _pdrStepCount
        internal val _savedRoutes = MutableStateFlow<List<SavedRoute>>(emptyList()); val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes
        internal val _activeRoute = MutableStateFlow<SavedRoute?>(null); val activeRoute: StateFlow<SavedRoute?> = _activeRoute
        internal val _routeRecording = MutableStateFlow(false); val routeRecording: StateFlow<Boolean> = _routeRecording
        internal val _routeRecordingWaypointCount = MutableStateFlow(0); val routeRecordingWaypointCount: StateFlow<Int> = _routeRecordingWaypointCount
        internal val _routeRecordingWaypoints = MutableStateFlow<List<Pair<Double, Double>>>(emptyList()); val routeRecordingWaypoints: StateFlow<List<Pair<Double, Double>>> = _routeRecordingWaypoints
        internal val _glassesInferenceMode = MutableStateFlow(InferenceMode.CLOUD); val glassesInferenceMode: StateFlow<InferenceMode> = _glassesInferenceMode
        internal val _glassesInferenceCloudUrl = MutableStateFlow(CloudConfig.DETECT_URL); val glassesInferenceCloudUrl: StateFlow<String> = _glassesInferenceCloudUrl
        internal val _watchState = MutableStateFlow(WatchClient.State.DISCONNECTED); val watchState: StateFlow<WatchClient.State> = _watchState
        internal val _watchName = MutableStateFlow<String?>(null); val watchName: StateFlow<String?> = _watchName
        internal val _watchBattery = MutableStateFlow(-1); val watchBattery: StateFlow<Int> = _watchBattery
        internal val _watchSampleRateHz = MutableStateFlow(100); val watchSampleRateHz: StateFlow<Int> = _watchSampleRateHz
        internal val _watchSamplesPerSec = MutableStateFlow(0); val watchSamplesPerSec: StateFlow<Int> = _watchSamplesPerSec
        internal val _watchHeartRateBpm = MutableStateFlow(-1); val watchHeartRateBpm: StateFlow<Int> = _watchHeartRateBpm
        internal val _watchLastError = MutableStateFlow<String?>(null); val watchLastError: StateFlow<String?> = _watchLastError
        internal val _watchImuStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 256); val watchImuStream: SharedFlow<ImuSample> = _watchImuStream
        internal val _navisensImuSource = MutableStateFlow(NavisensImuSource.PHONE); val navisensImuSource: StateFlow<NavisensImuSource> = _navisensImuSource
        internal val _fusedImuStream = MutableSharedFlow<com.example.peciwearables.integration.protocol.GlassesImuSample>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val fusedImuStream: SharedFlow<com.example.peciwearables.integration.protocol.GlassesImuSample> = _fusedImuStream
        internal val _fusedImuSourceCount = MutableStateFlow(0); val fusedImuSourceCount: StateFlow<Int> = _fusedImuSourceCount
        internal val _safetyUc1Enabled = MutableStateFlow(false); val safetyUc1Enabled: StateFlow<Boolean> = _safetyUc1Enabled
        internal val _safetyUc3Enabled = MutableStateFlow(false); val safetyUc3Enabled: StateFlow<Boolean> = _safetyUc3Enabled
        internal val _crossingZones = MutableStateFlow<List<CrossingZone>>(emptyList()); val crossingZones: StateFlow<List<CrossingZone>> = _crossingZones
        internal val _pedestrianDecision = MutableStateFlow<PedestrianSafetyDecision>(PedestrianSafetyDecision.Safe); val pedestrianDecision: StateFlow<PedestrianSafetyDecision> = _pedestrianDecision
        internal val _crossingDecision = MutableStateFlow<CrossingValidationDecision>(CrossingValidationDecision.NotRequested); val crossingDecision: StateFlow<CrossingValidationDecision> = _crossingDecision
        internal val _osmZonesStatus = MutableStateFlow(CrossingZoneManager.OsmStatus.IDLE); val osmZonesStatus: StateFlow<CrossingZoneManager.OsmStatus> = _osmZonesStatus
        internal val _safetyLog = MutableStateFlow<List<String>>(emptyList()); val safetyLog: StateFlow<List<String>> = _safetyLog
        internal val _headRotationSpreadDeg = MutableStateFlow(0f); val headRotationSpreadDeg: StateFlow<Float> = _headRotationSpreadDeg
        internal val _motionPeakCount = MutableStateFlow(0); val motionPeakCount: StateFlow<Int> = _motionPeakCount
        internal val _decelerationRatio = MutableStateFlow(1f); val decelerationRatio: StateFlow<Float> = _decelerationRatio
        internal val _imuRateHz = MutableStateFlow(0); val imuRateHz: StateFlow<Int> = _imuRateHz

        internal fun appendSafetyLog(msg: String) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val line = "[$ts] $msg"
            _safetyLog.value = (_safetyLog.value + line).takeLast(50)
            Log.d("SafetyLog", line)
        }

        internal val _safetyUc1_2Enabled = MutableStateFlow(false); val safetyUc1_2Enabled: StateFlow<Boolean> = _safetyUc1_2Enabled
        internal val _safetyUc1_4StrictEnabled = MutableStateFlow(false); val safetyUc1_4StrictEnabled: StateFlow<Boolean> = _safetyUc1_4StrictEnabled
        internal val _safetyUc4_5Enabled = MutableStateFlow(false); val safetyUc4_5Enabled: StateFlow<Boolean> = _safetyUc4_5Enabled
        internal val _vehicleApproachDecision = MutableStateFlow<VehicleApproachEvaluator.Decision>(VehicleApproachEvaluator.Decision.Clear); val vehicleApproachDecision: StateFlow<VehicleApproachEvaluator.Decision> = _vehicleApproachDecision
        internal val _safetyUc2Enabled = MutableStateFlow(false); val safetyUc2Enabled: StateFlow<Boolean> = _safetyUc2Enabled
        internal val _safetyUc3IncomingEnabled = MutableStateFlow(false); val safetyUc3IncomingEnabled: StateFlow<Boolean> = _safetyUc3IncomingEnabled
        internal val _safetyUc4_3Enabled = MutableStateFlow(false); val safetyUc4_3Enabled: StateFlow<Boolean> = _safetyUc4_3Enabled
        internal val _safetyUc1_4Enabled = MutableStateFlow(false); val safetyUc1_4Enabled: StateFlow<Boolean> = _safetyUc1_4Enabled
        internal val _cyclistMode = MutableStateFlow(CyclistModeDetector.Mode.UNKNOWN); val cyclistMode: StateFlow<CyclistModeDetector.Mode> = _cyclistMode
        internal val _atcllStatus = MutableStateFlow(AtcllClient.Status.OFFLINE); val atcllStatus: StateFlow<AtcllClient.Status> = _atcllStatus
        internal val _atcllEndpoint = MutableStateFlow<String?>(null); val atcllEndpoint: StateFlow<String?> = _atcllEndpoint
        internal val _unifiedServerUrl = MutableStateFlow(UNIFIED_SERVER_DEFAULT_URL); val unifiedServerUrl: StateFlow<String> = _unifiedServerUrl

        var instance: WearableService? = null
            private set

        private val UDP_DBG_KEYWORDS = listOf("handshake", "socket UDP aberto", "sessao terminada",
            "timeout", "erro", "connected", "confirmado")
        private val NOISY_PREFIXES = listOf(
            "UDP DBG: datagrama recebido", "UDP DBG: socket datagram parseado",
            "UDP DBG: RX socket MESSAGE", "UDP DBG: MESSAGE recebido", "UDP DBG: routing MESSAGE",
            "imageSize=", "Image expected:", "checkAndAssemble(",
        )

        fun appendLog(msg: String) {
            if (msg.startsWith("UDP DBG:") && UDP_DBG_KEYWORDS.none { msg.contains(it, ignoreCase = true) }) return
            if (NOISY_PREFIXES.any { msg.startsWith(it) }) return
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val current = _bleLog.value
            val trimmed = current.lines().takeLast(100).joinToString("\n").let { if (it == current) current else it }
            _bleLog.value = if (trimmed.isEmpty()) "[$timestamp] $msg" else "$trimmed\n[$timestamp] $msg"
            Log.d(TAG, msg)
        }

        fun clearLog() { _bleLog.value = "" }
    }

    internal val dispatcher = ServiceLifecycleDispatcher(this)
    override val lifecycle: Lifecycle get() = dispatcher.lifecycle

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val wifiUdpCallbacks = object : com.example.peciwearables.integration.udp.WifiUdpRecoveryCoordinator.Callbacks {
        override val glassesConnectionMode get() = _glassesConnectionMode.value
        override val glassesIp get() = _glassesIp.value
        override val isStreaming get() = _isStreaming.value
        override val isAudioRecording get() = _audioRecordingActive.value
        override val isGlassesMicStreaming get() = _glassesMicStreaming.value
        override fun setGlassesState(state: BleDeviceState) { _glassesState.value = state }
        override fun setGlassesMicStreaming(v: Boolean) { _glassesMicStreaming.value = v }
        override fun setGlassesMicStatus(text: String) { _glassesMicStatusText.value = text }
        override fun setGlassesMicDataText(text: String) { _glassesMicDataText.value = text }
        override fun setUdpActive(v: Boolean) { _udpActive.value = v }
        override fun setGlassesIp(ip: String) { _glassesIp.value = ip }
        override fun onPersistAudioOnDisconnect() {
            _audioRecordingActive.value = false
            persistAudioRecording()?.let { rec ->
                _recordedAudios.value = _recordedAudios.value + rec
                appendLog("✅ Gravação guardada (#${rec.index}) ${"%.1f".format(rec.durationSec)}s @${rec.sampleRateHz}Hz")
            }
            audioRecordPcm = ByteArrayOutputStream(); audioRecordSamples = 0L
        }
        override fun resetPhotoState() { photoCaptureInFlight = false }
        override fun updateWristbandTrafficProfile(reason: String) = this@WearableService.updateWristbandTrafficProfile(reason)
        override fun isWifiCredentialsSent() = wifiCredentialsSent
    }

    internal val wifiUdpRecovery by lazy {
        com.example.peciwearables.integration.udp.WifiUdpRecoveryCoordinator(
            scope = serviceScope, glassesClient = { glassesBleClient }, wifiLock = udpWifiLock,
            streamTargetFps = ::streamTargetFps, onLog = ::appendLog, callbacks = wifiUdpCallbacks,
        )
    }
    internal val glassesConnectedViaUdp: Boolean get() = wifiUdpRecovery.isConnected
    @Volatile internal var wifiCredentialsSent = false

    internal val scanAndConnect by lazy {
        com.example.peciwearables.integration.wearable.ScanAndConnectCoordinator(
            serviceScope, wearableHub, capabilityScanner,
            { _glassesState.value }, { _wristbandState.value }, ::appendLog,
        )
    }
    internal var glassesMicFrameCount = 0L
    internal var glassesMicLastUiUpdateMs = 0L
    internal var glassesMicTransitionAtMs = 0L
    internal var audioRecordPcm = ByteArrayOutputStream()
    internal var audioRecordSamples = 0L
    internal var audioRecordSampleRate = 16_000
    internal val streamFpsTimestamps = mutableListOf<Long>()
    internal var wristbandSensorRateAppliedMs = -1
    @Volatile internal var photoCaptureInFlight = false
    internal var photoCaptureTimeoutJob: Job? = null

    /** Writer CSV partilhado por todos os backends de latência. */
    internal val latencyCsvWriter: com.example.peciwearables.integration.latency.LatencyCsvWriter by lazy {
        com.example.peciwearables.integration.latency.LatencyCsvWriter(
            getExternalFilesDir("benchmarks") ?: filesDir
        )
    }

    internal val photoLatencyTracker by lazy {
        com.example.peciwearables.integration.latency.PhotoLatencyTracker(
            ::currentRouteLabel, ::currentTransportEstimateMs, ::benchLog,
            { _latestPhotoLatency.value = it }, ::appendLog,
        )
    }
    internal val microphoneLatencyTracker by lazy {
        com.example.peciwearables.integration.latency.MicrophoneLatencyTracker(
            ::currentRouteLabel, ::currentTransportEstimateMs, ::benchLog,
            { _latestMicrophoneLatency.value = it }, ::appendLog,
        )
    }
    internal var lastBleTransportMs: Long = 30L
    internal val imuMlBenchmarkRunner by lazy {
        com.example.peciwearables.integration.latency.ImuMlBenchmarkRunner(
            serviceScope, cloudLatencyReporter!!, ::currentRouteLabel, ::benchLog, ::appendLog,
            latestInferenceProvider = {
                com.example.peciwearables.integration.latency.ImuMlBenchmarkRunner.InferenceSnapshot(
                    latestInferenceClass, latestInferenceScore, latestInferenceTsMs,
                )
            },
        )
    }
    @Volatile internal var latestInferenceClass: String = "unknown"
    @Volatile internal var latestInferenceScore: Float = 0f
    @Volatile internal var latestInferenceTsMs: Long = 0L
    internal var onDeviceClassifier: PeciOnDeviceClassifier? = null
    internal var serverClassifier: PeciServerClassifier? = null
    internal val serverInferenceInFlight = AtomicBoolean(false)

    internal val kwsCoordinator by lazy {
        com.example.peciwearables.integration.stt.sherpa.SherpaKwsCoordinator(
            context = this, scope = serviceScope,
            onConnectedChanged = { _whisperConnected.value = it }, onLog = ::appendLog,
            onKeyword = { keyword ->
                _lastWhisperText.value = keyword
                _whisperTranscription.value = listOf(WhisperSegment(0f, 0f, keyword, completed = true))
                cloudKwsForwarder.submit(keyword)
            },
            glassesMicStreaming = { _glassesMicStreaming.value },
            csvWriter = latencyCsvWriter,
        )
    }
    internal val kwsSession get() = kwsCoordinator.activeSession
    internal val routeRecording by lazy {
        com.example.peciwearables.integration.route.RouteRecordingController(
            routeManager, { _phoneSensorsActive.value }, { _phoneGps.value },
            _routeRecordingWaypointCount, ::startPhoneSensorsInternal, ::appendLog,
        )
    }
    internal val routeImuOnly get() = routeRecording.routeImuOnly
    internal val waitingForAnchorFix get() = routeRecording.waitingForAnchorFix
    internal val glassesQuaternionTracker by lazy {
        com.example.peciwearables.integration.protocol.GlassesQuaternionTracker(_latestGlassesQuaternion)
    }
    internal val latestGlassesQuaternionMs get() = glassesQuaternionTracker.latestMs
    internal val glassesGyroIntegrators by lazy {
        com.example.peciwearables.integration.safety.GlassesGyroIntegrators(
            _latestGyIntegralDeg, _latestGySpreadDeg, _latestGxIntegralDeg, _latestGzIntegralDeg,
        )
    }

    internal lateinit var glassesWearableAdapter: OmiGlassesWearableAdapter
    internal lateinit var wristbandWearableAdapter: BrilliantSoleWristbandWearableAdapter
    internal lateinit var wearableHub: DefaultWearableHub
    internal var wearableBridge: WearableServiceLegacyBridge? = null
    internal val wearableRealtimeSink = object : WearableRealtimeSink {
        override fun onCameraChunk(id: WearableId, subType: Int, bytes: ByteArray) = handleGlassesCameraData(subType, bytes)
        override fun onStreamingJpeg(id: WearableId, jpegBytes: ByteArray) = handleStreamingJpeg(jpegBytes)
        override fun onCameraImageFile(id: WearableId, bytes: ByteArray) = handleGlassesCameraImageFile(bytes)
        override fun onPhotoJpeg(id: WearableId, jpegBytes: ByteArray, orientationDeg: Int) =
            handleGlassesPhoto(jpegBytes, orientationDeg)
        override fun onAudioPacket(id: WearableId, bytes: ByteArray, codecId: Int?) {
            Log.d(TAG, "DBG realtimeSink.onAudioPacket ${bytes.size}B codec=$codecId udp=${_udpActive.value}")
            handleGlassesAudioPacket(bytes)
        }
        override fun onImuPacket(id: WearableId, bytes: ByteArray) = handleGlassesImuPacket(bytes)
        override fun onImuSample(id: WearableId, sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) =
            handleWristbandImuSample(sample, timestampRaw16, timestampEstimatedMs)
        override fun onQuaternion(id: WearableId, quaternion: FloatArray) = handleGlassesQuaternion(quaternion)
        override fun onTfliteInference(id: WearableId, label: String, score: Float, timestampEstimatedMs: Long, values: FloatArray) =
            handleWristbandTfliteInference(label, score, timestampEstimatedMs, values)
    }
    internal val legacyGlassesClientId = WearableId("__legacy_omi__")
    internal val legacyWristbandClientId = WearableId("__legacy_sole__")
    internal lateinit var capabilityScanner: CapabilityDiscoveryScanner
    internal lateinit var udpServer: UdpServer
    internal lateinit var audioPipeline: AudioPipeline
    internal lateinit var imagePipeline: ImagePipeline
    internal val cameraMetrics = com.example.peciwearables.integration.image.camera.CameraMetrics(
        transportLabelProvider = {
            when {
                glassesConnectedViaUdp -> "UDP"
                _glassesState.value == BleDeviceState.CONNECTED -> "BLE"
                else -> "NONE"
            }
        },
        targetFpsProvider = { if (_isStreaming.value) streamTargetFps() else 0 },
    )
    internal lateinit var bleCameraPipeline: BleCameraPipeline
    @Volatile internal var latestCameraJpeg: ByteArray? = null
    @Volatile internal var latestYoloDetections: List<com.example.peciwearables.Detection> = emptyList()
    @Volatile internal var latestYoloDetectionsMs: Long = 0L
    internal lateinit var glassesCameraManager: GlassesCameraManager
    internal lateinit var glassesMicrophoneManager: GlassesMicrophoneManager
    internal lateinit var timeSyncManager: TimeSyncManager
    internal var cloudLatencyReporter: CloudLatencyReporter? = null
    internal lateinit var watchClient: WatchClient
    internal lateinit var crossingZoneStore: CrossingZoneStore
    internal lateinit var crossingZoneManager: CrossingZoneManager
    internal lateinit var safetyDiagnostics: SafetyDiagnostics
    internal val safetyToggles = SafetyToggles()
    internal val safetyDecisionFeed = DefaultSafetyDecisionFeed()
    internal val cloudZoneSync by lazy {
        com.example.peciwearables.integration.safety.CloudZoneSync(
            serviceScope, { _unifiedServerUrl.value }, { _crossingZones.value },
        )
    }
    internal val cloudKwsForwarder by lazy {
        com.example.peciwearables.integration.stt.sherpa.CloudKwsForwarder(serviceScope) { _unifiedServerUrl.value }
    }
    internal lateinit var safetyOrchestrator: SafetyOrchestrator
    internal lateinit var atcllClient: AtcllClient
    internal lateinit var telemetryReporter: com.example.peciwearables.integration.telemetry.TelemetryReporter
    internal val glassesPoseReporter by lazy {
        com.example.peciwearables.integration.bridge.GlassesPoseReporter(
            serviceScope, fusionHttpClient, { _latestGlassesQuaternion.value }, { latestGlassesQuaternionMs },
        )
    }
    internal val fusionHttpClient = okhttp3.OkHttpClient.Builder().apply {
        val ms = java.util.concurrent.TimeUnit.MILLISECONDS
        connectTimeout(3_000, ms); readTimeout(3_000, ms); writeTimeout(3_000, ms)
        retryOnConnectionFailure(true)
    }.build()
    internal val safetyEventBus = com.example.peciwearables.integration.safety.SafetyEventBus()
    internal val coAxialImuFusion = com.example.peciwearables.integration.sensors.CoAxialImuFusion(serviceScope)
    internal val crossingZoneTracker = com.example.peciwearables.integration.safety.CrossingZoneTracker()
    internal val cyclistDetector = CyclistModeDetector()
    internal val intentionEvaluator = CrossingIntentionEvaluator()
    internal val atcllAlerts = AtcllSafetyAlerts()
    internal val ambientSoundClassifier: AmbientSoundClassifier by lazy {
        val yamnet = com.example.peciwearables.integration.audio.YamnetClassifier.tryLoad(this)
        if (yamnet != null) appendLog("🎵 YAMNet carregado (UC4.3 com 521 classes)")
        else appendLog("🎵 YAMNet não encontrado — UC4.3 em modo heurística (RMS)")
        AmbientSoundClassifier(yamnet)
    }
    val depthManager by lazy { DepthManager(csvWriter = latencyCsvWriter) }
    internal val imuSampleTotalCounter = java.util.concurrent.atomic.AtomicLong(0)
    internal val cyclistVehicleAlert = com.example.peciwearables.integration.safety.CyclistVehicleAlertEvaluator()
    internal val ttsEngine by lazy {
        com.example.peciwearables.integration.audio.TextToSpeechEngine(this)
            .also { appendLog("🗣 TTS engine inicializado (UC4.5)") }
    }
    internal val conversationTranscriber by lazy { com.example.peciwearables.integration.safety.ConversationTranscriber(ttsEngine) }
    internal val visualAssistantEvaluator by lazy { com.example.peciwearables.integration.safety.VisualAssistantEvaluator(ttsEngine) }
    internal val vehicleApproachEvaluator = VehicleApproachEvaluator()

    internal val nsdRegistration by lazy {
        com.example.peciwearables.integration.network.NsdServiceRegistration(this, NSD_SERVICE_NAME, NSD_SERVICE_TYPE, UDP_PORT)
    }
    internal val udpWifiLock by lazy { com.example.peciwearables.integration.network.UdpWifiLock(this) }

    internal lateinit var phoneSensors: PhoneSensorManager
    internal lateinit var pdr: PedestrianDeadReckoning
    internal fun isPdrReady() = ::pdr.isInitialized
    internal lateinit var routeManager: RouteManager
    lateinit var glassesInferenceManager: InferenceManager
        private set
    @Volatile internal var glassesRawAudioCodec: Int = -1

    override fun onBind(intent: Intent?): IBinder? {
        dispatcher.onServicePreSuperOnBind()
        return null
    }

    internal fun currentSessionId(adapterId: String): WearableId? =
        if (!::wearableHub.isInitialized) null
        else wearableHub.sessions.value.values.firstOrNull { it.adapterId == adapterId }?.id
    internal fun currentGlassesSessionId(): WearableId? = currentSessionId(OmiGlassesWearableAdapter.ADAPTER_ID)
    internal fun currentWristbandSessionId(): WearableId? = currentSessionId(BrilliantSoleWristbandWearableAdapter.ADAPTER_ID)
    internal fun currentGlassesClient(): OmiGlassesBleClientApi? = currentGlassesSessionId()?.let { glassesWearableAdapter.getOrCreateClient(it) }
    internal fun currentWristbandClient(): BrilliantSoleWristbandBleClientApi? = currentWristbandSessionId()?.let { wristbandWearableAdapter.getOrCreateClient(it) }
    internal val glassesBleClient: OmiGlassesBleClientApi
        get() = currentGlassesClient() ?: glassesWearableAdapter.getOrCreateClient(legacyGlassesClientId)
    internal val wristbandBleClient: BrilliantSoleWristbandBleClientApi
        get() = currentWristbandClient() ?: wristbandWearableAdapter.getOrCreateClient(legacyWristbandClientId)

    internal fun sendGlassesCommand(command: WearableCommand, label: String, onAccepted: (() -> Unit)? = null) {
        val id = currentGlassesSessionId() ?: run { appendLog("Omi: sem sessao ativa para $label"); return }
        sendWearableCommand(id, command, label, "Omi", onAccepted)
    }

    internal fun sendWearableCommand(
        id: WearableId, command: WearableCommand, label: String, deviceLabel: String,
        onAccepted: (() -> Unit)? = null,
    ) {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (val result = wearableHub.send(id, command)) {
                CommandResult.Accepted -> onAccepted?.invoke()
                is CommandResult.Rejected -> appendLog("$deviceLabel: comando rejeitado ($label): ${result.reason}")
                is CommandResult.Unsupported -> appendLog("$deviceLabel: comando nao suportado ($label): ${result.capability}")
                is CommandResult.Failed -> appendLog("$deviceLabel: comando falhou ($label): ${result.cause.message}")
            }
        }
    }

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
        instance = this

        ForegroundNotification.ensureChannel(this)
        startForeground(NOTIFICATION_ID, ForegroundNotification.build(this))

        bootstrapAdaptersAndHub()
        capabilityScanner = CapabilityDiscoveryScanner(this)
        udpServer = UdpServer(UDP_PORT); audioPipeline = AudioPipeline(); imagePipeline = ImagePipeline()
        bootstrapCameraAndMicManagers()
        timeSyncManager = TimeSyncManager(); cloudLatencyReporter = CloudLatencyReporter()
        appendLog("☁ IMU host-inference reporter ativo")

        wireCoAxialFusion(); bootstrapWatchClient(); bootstrapCrossingZones(); bootstrapSafety()
        connectKwsInternal()
        serviceScope.launch { safetyToggles.uc1.collect { _safetyUc1Enabled.value = it } }
        serviceScope.launch { safetyToggles.uc3.collect { _safetyUc3Enabled.value = it } }
        serviceScope.launch { crossingZoneManager.zones.collect { _crossingZones.value = it } }
        serviceScope.launch { crossingZoneManager.osmStatus.collect { _osmZonesStatus.value = it } }

        val cloudPrefs = getSharedPreferences(ATCLL_PREFS, Context.MODE_PRIVATE)
        bootstrapAtcllAndTelemetry(cloudPrefs)
        // InferenceManager tem de existir antes de wireConnectivityAndAtcllLoops/
        // wireSafetyVisionLoops, que avaliam o safetyLoopsBuilder (usa-o).
        glassesInferenceManager = InferenceManager(this, csvWriter = latencyCsvWriter).also {
            it.setMode(_glassesInferenceMode.value)
            it.setCloudUrl(_glassesInferenceCloudUrl.value)
            it.setCloudUrl("${_unifiedServerUrl.value}/detect")
        }
        wireConnectivityAndAtcllLoops(); wireSafetyVisionLoops()
        wireWatchActionReactor()
        bootstrapMlClassifiers(cloudPrefs); resetGlassesMicState()

        phoneSensors = PhoneSensorManager(this); pdr = PedestrianDeadReckoning(); routeManager = RouteManager(this)

        wirePhoneSensorsAndPdr()
        startCameraObservers()
        startCameraStreamHealthLoop(); startGlassesConnectedAddressObserver()
        setupWearableLegacyBridge(); startAndWireUdpServer()
        nsdRegistration.register(); startDiagnosticsAndStatsLoops()

        Log.d(TAG, "WearableService created")
    }


    internal val telemetryPayloadBuilder by lazy {
        com.example.peciwearables.integration.telemetry.TelemetryPayloadBuilder(crossingZoneTracker) {
            com.example.peciwearables.integration.telemetry.TelemetryPayloadBuilder.Snapshot(
                _phoneAccel.value, _phoneGps.value, _pdrPosition.value, _crossingZones.value,
                _glassesState.value, _wristbandState.value, _watchState.value, _cyclistMode.value.name,
                _latestGlassesImu.value, coAxialImuFusion.lastFusedSourceCount,
                if (::safetyDiagnostics.isInitialized) safetyDiagnostics.headSpreadDeg() else null,
            )
        }
    }
    internal fun BleDeviceState.isOnlineBle() = this == BleDeviceState.READY || this == BleDeviceState.CONNECTED

    internal fun wirePhoneSensorsAndPdr() = com.example.peciwearables.integration.sensors.PhoneSensorsAndPdrWiring(
        scope = serviceScope,
        phoneSensors = phoneSensors, pdr = pdr, routeManager = routeManager, cyclistDetector = cyclistDetector,
        coAxialImuFusion = coAxialImuFusion,
        phoneAccel = _phoneAccel, phoneGps = _phoneGps, pdrPosition = _pdrPosition, pdrStepCount = _pdrStepCount,
        savedRoutes = _savedRoutes, activeRoute = _activeRoute, routeRecording = _routeRecording,
        routeRecordingWaypointCount = _routeRecordingWaypointCount, routeRecordingWaypoints = _routeRecordingWaypoints,
        latestImuSamples = _latestImuSamples, cyclistMode = _cyclistMode, imuSampleTotalCounter = imuSampleTotalCounter,
        routeImuOnly = { routeImuOnly }, waitingForAnchorFix = { waitingForAnchorFix },
        onAnchor = { lat, lon -> routeRecording.consumeAnchor(lat, lon) },
        uc1_4Enabled = { _safetyUc1_4Enabled.value },
    ).start()

    internal fun startCameraObservers() = com.example.peciwearables.integration.image.camera.CameraObserversWiring(
        scope = serviceScope, bleCameraPipeline = bleCameraPipeline, imagePipeline = imagePipeline,
        inferenceManager = glassesInferenceManager,
        latestCameraBitmap = _latestCameraBitmap, latestBitmap = _latestBitmap,
        isStreaming = _isStreaming, streamFps = _streamFps, streamFrameCount = _streamFrameCount,
        streamFpsTimestamps = streamFpsTimestamps, lastFrameTimestampMs = _lastFrameTimestampMs,
        inferenceMode = _glassesInferenceMode,
        latestCameraJpeg = { latestCameraJpeg },
        onDetections = { d, t -> latestYoloDetections = d; latestYoloDetectionsMs = t },
        publishCapturedPhoto = ::publishCapturedPhoto,
    ).start()

    internal fun startDiagnosticsAndStatsLoops() = com.example.peciwearables.integration.diagnostics.DiagnosticsLoopRunner(
        scope = serviceScope, safetyDiagnostics = safetyDiagnostics, imuSampleTotalCounter = imuSampleTotalCounter,
        headRotationSpreadDeg = _headRotationSpreadDeg, motionPeakCount = _motionPeakCount,
        decelerationRatio = _decelerationRatio, imuRateHz = _imuRateHz, stats = _stats,
        udpServer = udpServer, audioPipeline = audioPipeline, imagePipeline = imagePipeline,
        bleCameraPipeline = bleCameraPipeline, timeSyncManager = timeSyncManager,
        udpStatus = { wifiUdpRecovery.activeUdpManager?.status?.value?.toString() },
    ).start()


    internal val safetyLoopsBuilder by lazy {
        com.example.peciwearables.integration.safety.SafetyLoopsBuilder(
            scope = serviceScope, atcllClient = atcllClient, atcllAlerts = atcllAlerts,
            watchClient = watchClient, intentionEvaluator = intentionEvaluator,
            crossingZoneManager = crossingZoneManager, safetyEventBus = safetyEventBus,
            glassesInferenceManager = glassesInferenceManager, depthManager = depthManager,
            vehicleApproachEvaluator = vehicleApproachEvaluator, cyclistVehicleAlert = cyclistVehicleAlert,
            ambientSoundClassifier = ambientSoundClassifier, conversationTranscriber = conversationTranscriber,
            visualAssistantEvaluator = visualAssistantEvaluator, ttsEngine = ttsEngine,
            flows = com.example.peciwearables.integration.safety.SafetyLoopsBuilder.Flows(
                _glassesState, _wristbandState, _watchState, _phoneGps,
                _pedestrianDecision, _lastWhisperText, _whisperTranscription,
                _vehicleApproachDecision, _cyclistMode,
            ),
            callbacks = com.example.peciwearables.integration.safety.SafetyLoopsBuilder.Callbacks(
                sendStopVibration = { wristbandBleClient.sendStopAlertVibration() },
                sendGoVibration = { wristbandBleClient.sendGoAlertVibration() },
                sendWaveformVibration = { a -> runCatching { wristbandBleClient.sendWaveformVibration(a, durationMs = 250, locationBitmask = 0x03) } },
                latestJpeg = { latestCameraJpeg }, latestBitmap = { _latestCameraBitmap.value },
                frameProvider = {
                    when (cameraSource.value) {
                        CameraSource.PHONE -> phoneCameraBitmap.value
                        CameraSource.GLASSES -> latestCameraBitmap.value
                    }
                },
                onDetections = { latestYoloDetections = it; latestYoloDetectionsMs = System.currentTimeMillis() },
                cloudUrlProvider = { glassesInferenceManager.cloudUrl.value },
            ),
            gates = com.example.peciwearables.integration.safety.SafetyLoopsBuilder.Gates(
                { _safetyUc1_2Enabled.value }, { _safetyUc1_4StrictEnabled.value },
                { _safetyUc2Enabled.value }, { _safetyUc3IncomingEnabled.value },
                { _safetyUc4_3Enabled.value }, { _safetyUc4_5Enabled.value },
            ),
            appendLog = ::appendLog, appendSafetyLog = ::appendSafetyLog, notifyDeviceLost = ::notifyDeviceLost,
        )
    }
    internal fun wireConnectivityAndAtcllLoops() = safetyLoopsBuilder.wireConnectivityAndAtcll()
    internal fun wireSafetyVisionLoops() = safetyLoopsBuilder.wireVision()


    internal fun wireWatchActionReactor() = com.example.peciwearables.integration.watch.WatchActionRouter(
        scope = serviceScope, watch = watchClient,
        startPhoneSensors = { if (!_phoneSensorsActive.value) { phoneSensors.startAll(); _phoneSensorsActive.value = true } },
        stopPhoneSensors = { phoneSensors.stopAll(); _phoneSensorsActive.value = false },
        startAutoConnect = ::startAutoConnectFlow,
        appendLog = ::appendLog,
    ).start()



    internal val glassesMicStateMachine by lazy {
        com.example.peciwearables.integration.managers.GlassesMicStateMachine(
            _glassesMicStreaming, _glassesMicStatusText, _glassesMicState, _glassesMicDataText, _audioRecordingActive,
        )
    }
    internal val glassesCameraStateMachine by lazy {
        com.example.peciwearables.integration.managers.GlassesCameraStateMachine(
            _cameraStatus, _glassesCameraState, glassesCameraManager::onCameraStatusChanged,
        )
    }

    internal val mlProcessingCoordinator by lazy {
        com.example.peciwearables.integration.ml.MlProcessingCoordinator(
            serviceScope, { wristbandBleClient }, { serverClassifier },
            { wristbandSensorRateAppliedMs = it }, ::updateWristbandTrafficProfile, ::appendLog,
        )
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        routeIntent(intent)
        return START_STICKY
    }

    internal val deviceLossNotifier by lazy {
        com.example.peciwearables.integration.safety.DeviceLossNotifier(this, watchClient, safetyEventBus, ::appendLog, ::appendSafetyLog)
    }
    internal fun notifyDeviceLost(deviceName: String) = deviceLossNotifier.notifyLost(deviceName)
    internal fun startGlassesPoseReporter(baseUrl: String) = glassesPoseReporter.start(baseUrl)


    fun getLocalIp(): String = com.example.peciwearables.integration.network.NetworkInterfaceUtils.localIpv4() ?: "N/A"
}
