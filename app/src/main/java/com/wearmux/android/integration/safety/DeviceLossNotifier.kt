package com.wearmux.android.integration.safety

import android.content.Context
import com.wearmux.android.integration.modules.wearos.WatchClient
import com.wearmux.android.integration.modules.wearos.WatchProtocol

/** Notifica perda de wearable: Toast + watch DOUBLE vibrate + WARNING + EventBus. */
class DeviceLossNotifier(
    private val context: Context,
    private val watch: WatchClient,
    private val eventBus: SafetyEventBus,
    private val appendLog: (String) -> Unit,
    private val appendSafetyLog: (String) -> Unit,
) {
    fun notifyLost(deviceName: String) {
        appendLog("⚠ $deviceName disconnected")
        appendSafetyLog("🔌 $deviceName disconnected")
        runCatching {
            android.widget.Toast.makeText(context, "$deviceName disconnected", android.widget.Toast.LENGTH_LONG).show()
        }
        runCatching { watch.requestVibrate(WatchProtocol.VibratePattern.DOUBLE) }
        runCatching {
            watch.requestNotify(
                type = WatchProtocol.NotifyType.WARNING,
                title = "Device disconnected",
                body = "$deviceName lost connection.",
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
