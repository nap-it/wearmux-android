package com.example.peciwearables.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer
import java.nio.ByteOrder


class PhoneCommandListener : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneCommandListener"
    }

    /** NodeId do telemóvel — guardado em cada mensagem para poder enviar o ACK de volta. */
    @Volatile private var phoneNodeId: String? = null

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "msg path=${event.path} from=${event.sourceNodeId} (${event.data.size}B)")
        phoneNodeId = event.sourceNodeId  // actualizar sempre — é sempre o telemóvel
        when (event.path) {
            WatchProtocol.PATH_START_IMU -> tryStartFgs(ImuStreamingService.ACTION_START)
            WatchProtocol.PATH_STOP_IMU -> tryStartFgs(ImuStreamingService.ACTION_STOP)
            WatchProtocol.PATH_SET_SAMPLE_RATE -> {
                if (event.data.size >= 2) {
                    val rate = ByteBuffer.wrap(event.data)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF
                    tryStartFgs(ImuStreamingService.ACTION_SET_RATE) {
                        putExtra(ImuStreamingService.EXTRA_SAMPLE_RATE_HZ, rate)
                    }
                }
            }
            WatchProtocol.PATH_BEEP -> {
                if (event.data.size >= 5) {
                    val bb = ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN)
                    val freq = bb.short.toInt() and 0xFFFF
                    val dur = bb.short.toInt() and 0xFFFF
                    val volume = bb.get().toInt() and 0xFF
                    BeepPlayer.playLocal(this, freq, dur, volume / 100f)
                } else {
                    BeepPlayer.playLocal(this, 880, 250)
                }
            }
            WatchProtocol.PATH_VIBRATE -> {
                val pattern = event.data.getOrNull(0)?.toInt()?.and(0xFF)
                    ?: WatchProtocol.VibratePattern.SHORT
                val amplitude = event.data.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
                Vibrations.play(this, pattern, amplitude)
            }
            WatchProtocol.PATH_NOTIFY -> handleNotify(event.data)
            WatchProtocol.PATH_PHONE_STATE -> {
                if (event.data.size >= 5) {
                    val bb = ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN)
                    PhoneStateMirror.update(
                        glassesState = bb.get().toInt() and 0xFF,
                        wristbandState = bb.get().toInt() and 0xFF,
                        phoneSensorsActive = (bb.get().toInt() and 0xFF) == 1,
                        audioTestActive = (bb.get().toInt() and 0xFF) == 1,
                        deviceCount = bb.get().toInt() and 0xFF,
                    )
                }
            }
            else -> Log.w(TAG, "path desconhecido: ${event.path}")
        }
    }

    private fun handleNotify(data: ByteArray) {
        // Novo formato: [alertId:4B LE][type:1B][titleLen:1B][title][body]
        if (data.size < 6) return
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val alertId = buf.int                       // 4 bytes
        val type = buf.get().toInt() and 0xFF       // 1 byte
        val titleLen = buf.get().toInt() and 0xFF   // 1 byte
        if (data.size < 6 + titleLen) return
        val title = String(data, 6, titleLen, Charsets.UTF_8)
        val body = if (data.size > 6 + titleLen) {
            String(data, 6 + titleLen, data.size - 6 - titleLen, Charsets.UTF_8)
        } else ""
        when (type) {
            WatchProtocol.NotifyType.DANGER  -> WatchNotifier.showDanger(this, title, body)
            WatchProtocol.NotifyType.SAFE    -> WatchNotifier.showSafe(this, title, body)
            else                             -> WatchNotifier.showWarning(this, title, body)
        }
        // Enviar ACK de volta ao telemóvel para medir RTT do alerta
        phoneNodeId?.let { nodeId ->
            val ack = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(alertId).array()
            Wearable.getMessageClient(this)
                .sendMessage(nodeId, WatchProtocol.PATH_NOTIFY_ACK, ack)
                .addOnFailureListener { e ->
                    Log.w(TAG, "notify ACK send failed: ${e.message}")
                }
        }
    }

    private fun tryStartFgs(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, ImuStreamingService::class.java)
            .setAction(action)
            .apply(configure)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService($action) falhou: ${e.message}")
            try { startService(intent) } catch (_: Exception) {}
        }
    }
}
