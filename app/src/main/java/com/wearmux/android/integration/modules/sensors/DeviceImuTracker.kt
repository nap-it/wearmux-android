package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.protocol.DeviceImuState
import com.wearmux.android.integration.protocol.ImuSample

/**
 * Regista o IMU **por dispositivo**.
 *
 * Cada dispositivo tem o seu próprio contador e a sua própria janela de
 * timestamps — as amostras de duas pulseiras nunca são misturadas. É o
 * contrário de "um stream global com tudo dentro": aqui cada chave é o MAC
 * address de quem enviou a amostra.
 */
class DeviceImuTracker {

    private val counters = mutableMapOf<String, Counter>()

    /**
     * Regista uma amostra e devolve o estado actualizado desse dispositivo.
     *
     * @param id identificador do dispositivo (MAC address).
     */
    fun record(id: String, sample: ImuSample, nowMs: Long = System.currentTimeMillis()): DeviceImuState {
        val counter = counters.getOrPut(id) { Counter() }
        return synchronized(counter) {
            counter.latest = sample
            counter.count++
            counter.lastUpdateMs = nowMs
            counter.recent.addLast(nowMs)
            // Janela de ~2s (ou 128 amostras, o que vier primeiro).
            while (counter.recent.size > MAX_WINDOW ||
                (counter.recent.size > 2 && nowMs - counter.recent.first() > WINDOW_MS)
            ) {
                counter.recent.removeFirst()
            }
            val span = counter.recent.last() - counter.recent.first()
            counter.rateHz = if (counter.recent.size >= 2 && span > 0) {
                ((counter.recent.size - 1) * 1_000.0 / span).toInt()
            } else {
                0
            }
            DeviceImuState(
                latest = counter.latest,
                sampleCount = counter.count,
                rateHz = counter.rateHz,
                lastUpdateMs = counter.lastUpdateMs,
            )
        }
    }

    /** Estado actual de um dispositivo, ou `null` se nunca enviou amostras. */
    fun stateOf(id: String): DeviceImuState? = counters[id]?.let { counter ->
        synchronized(counter) {
            DeviceImuState(
                latest = counter.latest,
                sampleCount = counter.count,
                rateHz = counter.rateHz,
                lastUpdateMs = counter.lastUpdateMs,
            )
        }
    }

    /** Esquece um dispositivo (quando a sessão sai do hub). */
    fun remove(id: String) {
        counters.remove(id)
    }

    fun ids(): Set<String> = counters.keys.toSet()

    private class Counter {
        var latest: ImuSample? = null
        var count: Long = 0
        var rateHz: Int = 0
        var lastUpdateMs: Long = 0
        val recent = ArrayDeque<Long>()
    }

    private companion object {
        const val MAX_WINDOW = 128
        const val WINDOW_MS = 2_000L
    }
}
