package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.atcll.AtcllClient
import com.example.peciwearables.integration.atcll.AtcllIncomingLoop
import com.example.peciwearables.integration.atcll.AtcllOutgoingLoops
import com.example.peciwearables.integration.audio.AmbientSoundClassifier
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.audio.TextToSpeechEngine
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.depth.DepthManager
import com.example.peciwearables.integration.inference.InferenceManager
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import com.example.peciwearables.integration.stt.whisper.WhisperSegment
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Constrói e arranca todos os loops UC (1.2/1.4/4.1/4.3/4.5 + ATCLL incoming/outgoing + connectivity).
 * Encapsula ~70 linhas de wiring que viviam no `WearableService.onCreate`.
 */
class SafetyLoopsBuilder(
    private val scope: CoroutineScope,
    private val atcllClient: AtcllClient,
    private val atcllAlerts: AtcllSafetyAlerts,
    private val watchClient: WatchClient,
    private val intentionEvaluator: CrossingIntentionEvaluator,
    private val crossingZoneManager: CrossingZoneManager,
    private val safetyEventBus: SafetyEventBus,
    private val glassesInferenceManager: InferenceManager,
    private val depthManager: DepthManager,
    private val vehicleApproachEvaluator: VehicleApproachEvaluator,
    private val cyclistVehicleAlert: CyclistVehicleAlertEvaluator,
    private val ambientSoundClassifier: AmbientSoundClassifier,
    private val conversationTranscriber: ConversationTranscriber,
    private val visualAssistantEvaluator: VisualAssistantEvaluator,
    private val ttsEngine: TextToSpeechEngine,
    private val flows: Flows,
    private val callbacks: Callbacks,
    private val gates: Gates,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
    private val notifyDeviceLost: (String) -> Unit,
) {
    data class Flows(
        val glassesState: StateFlow<BleDeviceState>,
        val wristbandState: StateFlow<BleDeviceState>,
        val watchState: StateFlow<WatchClient.State>,
        val phoneGps: StateFlow<PhoneGpsLocation?>,
        val pedestrianDecision: StateFlow<PedestrianSafetyDecision>,
        val lastWhisperText: MutableStateFlow<String>,
        val whisperTranscription: MutableStateFlow<List<WhisperSegment>>,
        val vehicleApproachDecision: MutableStateFlow<VehicleApproachEvaluator.Decision>,
        val cyclistMode: StateFlow<CyclistModeDetector.Mode>,
    )
    data class Callbacks(
        val sendStopVibration: () -> Unit,
        val sendGoVibration: () -> Unit,
        val sendWaveformVibration: (Float) -> Unit,
        val latestJpeg: () -> ByteArray?,
        val latestBitmap: () -> android.graphics.Bitmap?,
        val frameProvider: () -> android.graphics.Bitmap?,
        val onDetections: (List<com.example.peciwearables.Detection>) -> Unit,
        val cloudUrlProvider: () -> String,
    )
    data class Gates(
        val uc1_2Enabled: () -> Boolean,
        val uc1_4StrictEnabled: () -> Boolean,
        val uc2Enabled: () -> Boolean,
        val uc3IncomingEnabled: () -> Boolean,
        val uc4_3Enabled: () -> Boolean,
        val uc4_5Enabled: () -> Boolean,
    )

    fun wireConnectivityAndAtcll() {
        DeviceConnectivityObservers(scope, safetyEventBus, notifyDeviceLost)
            .attach(flows.glassesState, flows.wristbandState, flows.watchState)
        AtcllIncomingLoop(
            atcll = atcllClient, alerts = atcllAlerts, watch = watchClient,
            onSendStopVibration = callbacks.sendStopVibration,
            onEmergencyTone = { AudioTestEngine.playTone(640, 800) },
            enabled = gates.uc3IncomingEnabled, phoneGps = { flows.phoneGps.value },
        ).start(scope)
        AtcllOutgoingLoops(
            atcll = atcllClient, intentionEvaluator = intentionEvaluator, watch = watchClient,
            onSendStopVibration = callbacks.sendStopVibration, onSendGoVibration = callbacks.sendGoVibration,
            uc2Enabled = gates.uc2Enabled, phoneGps = { flows.phoneGps.value },
            zones = { crossingZoneManager.zones.value },
            pedestrianDecision = { flows.pedestrianDecision.value },
            lastWhisperText = flows.lastWhisperText,
            appendLog = appendLog, appendSafetyLog = appendSafetyLog,
        ).start(scope)
    }

    fun wireVision() {
        Uc12Loop(
            enabled = gates.uc1_2Enabled,
            latestJpeg = callbacks.latestJpeg, latestBitmap = callbacks.latestBitmap,
            inferenceManager = glassesInferenceManager, depthManager = depthManager,
            evaluator = vehicleApproachEvaluator,
            onDetections = callbacks.onDetections,
            onDecision = { flows.vehicleApproachDecision.value = it },
            onAlert = { d ->
                watchClient.requestNotify(
                    WatchProtocol.NotifyType.DANGER,
                    "${d.label.uppercase()} a aproximar",
                    "Distância normalizada ${"%.2f".format(d.depthNow)} · ${"%.2f".format(d.closingRatePerSec)}/s",
                )
                callbacks.sendStopVibration()
            },
            appendLog = appendLog, appendSafetyLog = appendSafetyLog,
        ).start(scope)
        Uc14StrictLoop(
            enabled = gates.uc1_4StrictEnabled, cyclistMode = { flows.cyclistMode.value },
            latestBitmap = callbacks.latestBitmap,
            inferenceManager = glassesInferenceManager, depthManager = depthManager,
            evaluator = cyclistVehicleAlert,
            onIntensityAlert = { d ->
                val a = (d.intensityPct * 2.55f).toInt().coerceIn(60, 255)
                callbacks.sendWaveformVibration(a / 255f)
            },
            appendLog = appendLog, appendSafetyLog = appendSafetyLog,
        ).start(scope)
        Uc45Loop(gates.uc4_5Enabled, flows.whisperTranscription, conversationTranscriber).start(scope)
        Uc41Loop(
            transcription = flows.whisperTranscription, frameProvider = callbacks.frameProvider,
            evaluator = visualAssistantEvaluator, cloudUrl = callbacks.cloudUrlProvider,
            tts = ttsEngine, appendLog = appendLog,
        ).start(scope)
        Uc43Loop(gates.uc4_3Enabled, ambientSoundClassifier, watchClient, appendLog, appendSafetyLog).start(scope)
    }
}
