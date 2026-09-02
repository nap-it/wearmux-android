package com.example.peciwearables.integration.safety

import android.util.Log
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol


class SafetyOutputs(
    private val watchClient: WatchClient,
    private val vibrateWristband: () -> Unit,
    private val vibrateWristbandIntensity: (Int) -> Unit = { vibrateWristband() },
    private val eventBus: SafetyEventBus? = null,
) {
    private fun safe(target: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "$target falhou: ${e.message}") }
    }

    /** Pré-alerta antes da passadeira: beep + WARNING; sem vibrar a pulseira. */
    fun dispatchApproach(zoneName: String, distanceMeters: Double) {
        safe("watch beep approach")   { watchClient.requestBeep(660, 120) }
        safe("watch vibrate approach") { watchClient.requestVibrate(WatchProtocol.VibratePattern.SHORT) }
        safe("watch notify approach") {
            watchClient.requestNotify(
                type = WatchProtocol.NotifyType.WARNING,
                title = "Passadeira a ${distanceMeters.toInt()} m",
                body = "$zoneName — olha para os dois lados antes de atravessar.",
            )
        }
        eventBus?.publish(SafetyEventBus.Event.Approach(zoneName, distanceMeters))
    }

    /** Pedestre dentro da zona sem olhar — vibrar pulseira + alarme no watch. */
    fun dispatchDanger(zoneName: String) {
        safe("wristband danger") { vibrateWristband() }
        safe("watch notify danger") {
            watchClient.requestNotify(
                type = WatchProtocol.NotifyType.DANGER,
                title = "Atenção!",
                body = "A aproximares-te de $zoneName e não olhaste — atravessa com cuidado.",
            )
        }
        eventBus?.publish(SafetyEventBus.Event.Danger(zoneName))
    }

    /** Olhou para os dois lados / parou — confirmação positiva. */
    fun dispatchSafeToCross(zoneName: String = "passadeira") {
        safe("phone tone safe") { AudioTestEngine.playTone(880, 180) }
        safe("watch beep safe") { watchClient.requestBeep(880, 160) }
        safe("watch vibrate safe") { watchClient.requestVibrate(WatchProtocol.VibratePattern.DOUBLE) }
        safe("watch notify safe") {
            watchClient.requestNotify(
                type = WatchProtocol.NotifyType.SAFE,
                title = "Safe to cross",
                body = "Olhaste para os dois lados. Podes atravessar com atenção.",
            )
        }
        eventBus?.publish(SafetyEventBus.Event.CrossingOk(zoneName = zoneName))
    }

    /** UC1.3 ok — tom agudo + beep duplo. */
    fun dispatchCrossingOk() {
        dispatchSafeToCross()
    }

    /** UC1.3 espera — tom grave + beep longo. */
    fun dispatchCrossingWait(reason: String) {
        safe("phone tone wait")    { AudioTestEngine.playTone(420, 600) }
        safe("watch beep wait")    { watchClient.requestBeep(420, 600) }
        safe("watch vibrate wait") { watchClient.requestVibrate(WatchProtocol.VibratePattern.LONG) }
        safe("watch notify wait") {
            watchClient.requestNotify(
                type = WatchProtocol.NotifyType.WARNING,
                title = "Espera",
                body = reason,
            )
        }
        eventBus?.publish(SafetyEventBus.Event.CrossingWait(reason))
    }

    /** UC1.4 / ATCLL — veículo a aproximar-se; intensidade ∈ [0,100]. */
    fun dispatchVehicleAlert(label: String, depthOrDistance: Float, intensityPct: Int = 0) {
        if (intensityPct > 0) {
            safe("wristband vehicle") { vibrateWristbandIntensity(intensityPct) }
        }
        safe("watch notify vehicle") {
            watchClient.requestNotify(
                type = WatchProtocol.NotifyType.DANGER,
                title = "Veículo perto",
                body = "$label — ${"%.1f".format(depthOrDistance)} m",
            )
        }
        eventBus?.publish(SafetyEventBus.Event.VehicleAlert(label, depthOrDistance))
    }

    private companion object { const val TAG = "SafetyOutputs" }
}
