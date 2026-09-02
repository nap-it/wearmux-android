package com.example.peciwearables.integration.safety

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.Detection
import com.example.peciwearables.integration.audio.AmbientSoundClassifier
import com.example.peciwearables.integration.audio.TextToSpeechEngine
import com.example.peciwearables.integration.depth.DepthManager
import com.example.peciwearables.integration.inference.InferenceManager
import com.example.peciwearables.integration.stt.whisper.WhisperSegment
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer

/** UC1.2 — Veículo a aproximar (YOLO + Depth Anything) a 4 Hz. */
class Uc12Loop(
    private val enabled: () -> Boolean,
    private val latestJpeg: () -> ByteArray?,
    private val latestBitmap: () -> Bitmap?,
    private val inferenceManager: InferenceManager,
    private val depthManager: DepthManager,
    private val evaluator: VehicleApproachEvaluator,
    private val onDetections: (List<Detection>) -> Unit,
    private val onDecision: (VehicleApproachEvaluator.Decision) -> Unit,
    private val onAlert: (VehicleApproachEvaluator.Decision.Alert) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        while (true) {
            if (enabled()) {
                val jpeg = latestJpeg(); val frame = latestBitmap()
                if (jpeg != null && frame != null) {
                    try {
                        coroutineScope {
                            val detectionsDeferred = async { inferenceManager.detect(jpeg) }
                            val depthDeferred = async { depthManager.estimateDepth(frame) }

                            val detections = detectionsDeferred.await()
                            onDetections(detections)          // notifica o fusion engine mal o YOLO termina

                            val depth = depthDeferred.await() // aguarda o depth (pode já ter terminado)
                            val lookingAt = detections.any {
                                val cx = (it.left + it.right) / 2f; cx in 0.30f..0.70f
                            }
                            val decision = evaluator.evaluate(detections, depth, lookingAtDetection = lookingAt)
                            onDecision(decision)
                            if (decision is VehicleApproachEvaluator.Decision.Alert) {
                                onAlert(decision)
                                appendLog("🚗 UC1.2 ALERTA: ${decision.label} a aproximar @ ${"%.2f".format(decision.depthNow)}")
                                appendSafetyLog("🚗 UC1.2 ALERT · ${decision.label} depth=${"%.2f".format(decision.depthNow)} rate=${"%.2f".format(decision.closingRatePerSec)}/s")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Uc12Loop", "tick falhou: ${e.message}")
                    }
                }
            }
            delay(250)
        }
    }
}

/** UC1.4 estrito — alerta ciclista (proporcional, só CYCLING). */
class Uc14StrictLoop(
    private val enabled: () -> Boolean,
    private val cyclistMode: () -> CyclistModeDetector.Mode,
    private val latestBitmap: () -> Bitmap?,
    private val inferenceManager: InferenceManager,
    private val depthManager: DepthManager,
    private val evaluator: CyclistVehicleAlertEvaluator,
    private val onIntensityAlert: (CyclistVehicleAlertEvaluator.Decision.IntensityAlert) -> Unit,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        while (true) {
            if (enabled() && cyclistMode() == CyclistModeDetector.Mode.CYCLING) {
                val frame = latestBitmap()
                if (frame != null) try {
                    coroutineScope {
                        val detectionsDeferred = async { inferenceManager.detect(frame) }
                        val depthDeferred = async { depthManager.estimateDepth(frame) }

                        val detections = detectionsDeferred.await()
                        val depth = depthDeferred.await()
                        val decision = evaluator.evaluate(detections, depth)
                        if (decision is CyclistVehicleAlertEvaluator.Decision.IntensityAlert) {
                            onIntensityAlert(decision)
                            appendLog("🚲 UC1.4 strict: ${decision.label} @${decision.side} intensity=${decision.intensityPct}%")
                            appendSafetyLog("🚲 UC1.4 strict · ${decision.label} ${decision.side} ${decision.intensityPct}%")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Uc14StrictLoop", "tick falhou: ${e.message}")
                }
            }
            delay(400)
        }
    }
}

/** UC4.3 — sons ambientes → vibração no watch. */
class Uc43Loop(
    private val enabled: () -> Boolean,
    private val classifier: AmbientSoundClassifier,
    private val watch: WatchClient,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        classifier.detections.collect { d ->
            if (!enabled()) return@collect
            val pattern = when (d.category) {
                AmbientSoundClassifier.SoundCategory.SIREN,
                AmbientSoundClassifier.SoundCategory.ALARM -> WatchProtocol.VibratePattern.ALARM
                AmbientSoundClassifier.SoundCategory.DOORBELL,
                AmbientSoundClassifier.SoundCategory.PHONE_RING -> WatchProtocol.VibratePattern.TRIPLE
                AmbientSoundClassifier.SoundCategory.KNOCK -> WatchProtocol.VibratePattern.DOUBLE
                AmbientSoundClassifier.SoundCategory.SHOUT,
                AmbientSoundClassifier.SoundCategory.LOUD_AMBIENT -> WatchProtocol.VibratePattern.LONG
                AmbientSoundClassifier.SoundCategory.SILENCE -> return@collect
            }
            watch.requestVibrate(pattern)
            appendLog("🔊 UC4.3 ${d.category.name} (${"%.0f".format(d.rmsDb)}dB)")
            appendSafetyLog("🔊 UC4.3 ${d.category.name} rms=${"%.0f".format(d.rmsDb)}dB")
        }
    }
}

/** UC4.5 — transcrição contínua → TTS. */
class Uc45Loop(
    private val enabled: () -> Boolean,
    private val transcription: StateFlow<List<WhisperSegment>>,
    private val transcriber: ConversationTranscriber,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        transcription.collect { segments ->
            if (!enabled()) return@collect
            transcriber.onSegments(segments)
        }
    }
}

/** UC4.1 — Assistente Visual on-demand (intent + frame). */
class Uc41Loop(
    private val transcription: StateFlow<List<WhisperSegment>>,
    private val frameProvider: () -> Bitmap?,
    private val evaluator: VisualAssistantEvaluator,
    private val cloudUrl: () -> String,
    private val tts: TextToSpeechEngine,
    private val appendLog: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        var lastTriggerMs = 0L
        transcription.collect { segments ->
            val latest = segments.lastOrNull { it.completed } ?: segments.lastOrNull() ?: return@collect
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs < 5_000) return@collect
            val normalized = Normalizer.normalize(latest.text.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("[\\p{InCombiningDiacriticalMarks}]"), "")
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val matches = normalized.contains("que esta a minha frente") ||
                normalized.contains("em minha frente") || normalized.contains("na minha frente") ||
                normalized.contains("que esta a frente") || normalized.contains("que vejo") ||
                normalized.contains("que esta na minha mao")
            if (!matches) return@collect
            lastTriggerMs = now
            appendLog("🤖 Intent UC4.1 detetado: Análise Visual")
            val bitmap = frameProvider()
            if (bitmap != null) {
                evaluator.analyzeAndSpeak(
                    image = bitmap,
                    intentContext = if (normalized.contains("mao")) "mão" else "frente",
                    cloudUrl = cloudUrl(),
                )
            } else {
                tts.speak("A câmara não está ativa.")
            }
        }
    }
}
