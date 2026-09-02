package com.example.peciwearables.integration.safety

import android.content.Context
import com.example.peciwearables.integration.watch.WatchClient
import com.example.peciwearables.integration.watch.WatchProtocol

/** Notifica perda de wearable: Toast + watch DOUBLE vibrate + WARNING + EventBus. */
class DeviceLossNotifier(
    private val context: Context,
    private val watch: WatchClient,
    private val eventBus: SafetyEventBus,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun notifyLost(deviceName: String) {
        appendLog("⚠ $deviceName desligado")
        appendSafetyLog("🔌 $deviceName desligado")
        runCatching {
            android.widget.Toast.makeText(context, "$deviceName desligado", android.widget.Toast.LENGTH_LONG).show()
        }
        runCatching { watch.requestVibrate(WatchProtocol.VibratePattern.DOUBLE) }
        runCatching {
            watch.requestNotify(
                type = WatchProtocol.NotifyType.WARNING,
                title = "Dispositivo desligado",
                body = "$deviceName perdeu ligação.",
            )
        }
        val short = when {
            deviceName.contains("Omi", ignoreCase = true) -> "omi"
            deviceName.contains("Sole", ignoreCase = true) -> "sole"
            deviceName.contains("Watch", ignoreCase = true) -> "watch"
            else -> deviceName.lowercase()
        }
        eventBus.publish(SafetyEventBus.Event.DeviceDisconnected(short))
    }
}
