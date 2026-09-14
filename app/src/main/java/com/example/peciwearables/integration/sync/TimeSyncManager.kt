package com.example.peciwearables.integration.sync

import android.os.SystemClock
import android.util.Log


class TimeSyncManager {

    companion object {
        private const val TAG = "TimeSyncManager"
        private const val WINDOW_SIZE = 60 // últimos N pontos (30–60 s)
    }

    /**
     * Dados de calibração por dispositivo.
     */
    data class DeviceSync(
        val deviceId: String,
        var alpha: Double = 1.0,    // escala (tipicamente ~1.0)
        var beta: Double = 0.0,     // offset
        val samples: MutableList<Pair<Double, Double>> = mutableListOf(), // (tdev, tphone)
        var lastUpdateTimeMs: Long = 0,
        var rmsError: Double = 0.0
    )

    private val devices = mutableMapOf<String, DeviceSync>()

    /**
     * Regista um ponto de calibração (chamado a cada pacote recebido).
     *
     * @param deviceId identificador do dispositivo
     * @param deviceTimestampUs timestamp do dispositivo (µs, do campo timestamp_us do cabeçalho)
     */
    fun addSample(deviceId: String, deviceTimestampUs: Long) {
        val phoneTimestampUs = SystemClock.elapsedRealtimeNanos() / 1000

        val sync = devices.getOrPut(deviceId) { DeviceSync(deviceId) }

        sync.samples.add(Pair(deviceTimestampUs.toDouble(), phoneTimestampUs.toDouble()))

        // Manter janela deslizante
        while (sync.samples.size > WINDOW_SIZE) {
            sync.samples.removeAt(0)
        }

        // Recalcular regressão com pelo menos 5 amostras
        if (sync.samples.size >= 5) {
            computeRegression(sync)
        }
    }

    /**
     * Converte timestamp de dispositivo para timestamp do telefone.
     */
    fun deviceToPhoneTime(deviceId: String, deviceTimestampUs: Long): Long {
        val sync = devices[deviceId] ?: return deviceTimestampUs
        return (sync.alpha * deviceTimestampUs + sync.beta).toLong()
    }

    /**
     * Regressão linear: α = cov(x,y)/var(x), β = ȳ - α·x̄
     */
    private fun computeRegression(sync: DeviceSync) {
        val n = sync.samples.size
        if (n < 2) return

        var sumX = 0.0; var sumY = 0.0
        var sumXX = 0.0; var sumXY = 0.0

        for ((x, y) in sync.samples) {
            sumX += x; sumY += y
            sumXX += x * x; sumXY += x * y
        }

        val meanX = sumX / n
        val meanY = sumY / n
        val varX = sumXX / n - meanX * meanX
        val covXY = sumXY / n - meanX * meanY

        if (varX == 0.0) return

        sync.alpha = covXY / varX
        sync.beta = meanY - sync.alpha * meanX
        sync.lastUpdateTimeMs = System.currentTimeMillis()

        // Calcular RMS do erro residual
        var sumSqErr = 0.0
        for ((x, y) in sync.samples) {
            val predicted = sync.alpha * x + sync.beta
            val err = y - predicted
            sumSqErr += err * err
        }
        sync.rmsError = Math.sqrt(sumSqErr / n)

        Log.d(TAG, "Device ${sync.deviceId}: α=${sync.alpha}, β=${sync.beta}, " +
                "RMS=${sync.rmsError}µs, samples=$n")
    }

    /**
     * Obtém informação de sincronização de um dispositivo.
     */
    fun getSyncInfo(deviceId: String): DeviceSync? = devices[deviceId]

    /**
     * Obtém todos os dispositivos sincronizados.
     */
    fun getAllDevices(): Map<String, DeviceSync> = devices.toMap()

    fun getStats(): String {
        return devices.entries.joinToString("; ") { (id, sync) ->
            "$id: α=%.6f β=%.0f RMS=%.1fµs".format(sync.alpha, sync.beta, sync.rmsError)
        }
    }
}
