package com.example.peciwearables.integration.atcll

import com.example.peciwearables.integration.safety.AtcllSafetyAlerts
import com.example.peciwearables.integration.safety.CrossingIntentionEvaluator
import com.example.peciwearables.integration.safety.CrossingZone
import com.example.peciwearables.integration.safety.PedestrianSafetyDecision
import com.example.peciwearables.integration.safety.VoiceCommandMatcher
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** UC2.1/2.2/2.3 — envio ATCLL: VAM, DENM stop, data stream. */
class AtcllOutgoingLoops(
    private val atcll: AtcllClient,
    private val intentionEvaluator: CrossingIntentionEvaluator,
    private val watch: WatchClient,
    private val onSendStopVibration: () -> Unit,
    private val onSendGoVibration: () -> Unit,
    private val uc2Enabled: () -> Boolean,
    private val phoneGps: () -> PhoneGpsLocation?,
    private val zones: () -> List<CrossingZone>,
    private val pedestrianDecision: () -> PedestrianSafetyDecision,
    private val lastWhisperText: StateFlow<String>,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                if (uc2Enabled()) {
                    val gps = phoneGps()
                    if (gps != null) {
                        val moving = !((pedestrianDecision() as? PedestrianSafetyDecision.Watching)
                            ?.reason?.contains("parado") ?: false)
                        val intent = intentionEvaluator.evaluate(
                            zones = zones(), gps = gps, moving = moving, lookedBothWays = false,
                        )
                        if (intent is CrossingIntentionEvaluator.Decision.IntentDetected) {
                            atcll.sendVam(gps, gps.bearing, intentToCross = true)
                            runCatching { onSendGoVibration() }
                            appendLog("📡 UC2.1 → VAM enviado (zona ${intent.zoneName})")
                            appendSafetyLog("📡 UC2.1 VAM enviado · zona=${intent.zoneName}")
                        }
                    }
                }
                delay(500)
            }
        }
        scope.launch {
            lastWhisperText.collect { transcript ->
                if (!uc2Enabled()) return@collect
                if (!VoiceCommandMatcher.matchesStop(transcript)) return@collect
                val gps = phoneGps() ?: return@collect
                atcll.sendDenmStop(gps)
                runCatching { onSendStopVibration() }
                watch.requestVibrate(WatchProtocol.VibratePattern.LONG)
                appendLog("📡 UC2.2 → DENM \"stop\" enviado")
                appendSafetyLog("📡 UC2.2 DENM stop enviado")
            }
        }
        scope.launch {
            while (true) {
                if (uc2Enabled()) {
                    val gps = phoneGps()
                    if (gps != null) {
                        val payload = JSONObject()
                            .put("type", "data").put("lat", gps.latitude).put("lon", gps.longitude)
                            .put("speed", gps.speed).put("ts", System.currentTimeMillis())
                        atcll.sendDataStream(payload)
                    }
                }
                delay(5_000)
            }
        }
    }
}

/** UC3.1/3.2 — alertas recebidos do ATCLL. */
class AtcllIncomingLoop(
    private val atcll: AtcllClient,
    private val alerts: AtcllSafetyAlerts,
    private val watch: WatchClient,
    private val onSendStopVibration: () -> Unit,
    private val onEmergencyTone: () -> Unit,
    private val enabled: () -> Boolean,
    private val phoneGps: () -> PhoneGpsLocation?,
) {
    fun start(scope: CoroutineScope) = scope.launch {
        atcll.incomingMessages.collect { msg ->
            if (!enabled()) return@collect
            val alert = alerts.evaluate(msg, phoneGps()) ?: return@collect
            when (alert) {
                is AtcllSafetyAlerts.Alert.Speeding -> {
                    watch.requestNotify(
                        WatchProtocol.NotifyType.WARNING,
                        title = "Carro em alta velocidade",
                        body = "≈${alert.speedKmh.toInt()} km/h a ${alert.distanceMeters.toInt()} m de ti.",
                    )
                    watch.requestVibrate(WatchProtocol.VibratePattern.DOUBLE)
                    runCatching { onSendStopVibration() }
                }
                is AtcllSafetyAlerts.Alert.Emergency -> {
                    runCatching { onEmergencyTone() }
                    watch.requestNotify(
                        WatchProtocol.NotifyType.DANGER,
                        title = "Veículo de emergência",
                        body = "${alert.type} a ${alert.distanceMeters.toInt()} m — desvia-te.",
                    )
                    watch.requestVibrate(WatchProtocol.VibratePattern.ALARM)
                }
            }
        }
    }
}
