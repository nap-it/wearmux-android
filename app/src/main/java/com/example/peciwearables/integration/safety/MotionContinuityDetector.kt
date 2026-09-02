package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.protocol.ImuSample
import kotlin.math.sqrt


/** Detecta se há **movimento contínuo** baseado no IMU do pulso (Brilliant
 * Sole) ou do Galaxy Watch.
 *
 * Não conta passos com precisão — apenas confirma que o pulso oscila
 * suficientemente nos últimos N segundos. A heurística:
 *
 * 1. Calcula `||a_linear||` em g (acelerómetro com gravidade subtraída via
 *    high-pass) para cada amostra.
 * 2. Conta amostras com pico ≥ `peakThresholdG` numa janela deslizante.
 * 3. Considera "em movimento" se houve ≥ `minPeaksInWindow` picos.
 *
 * Imune a tremor parado: limiar suficientemente alto (1.10 g) para exigir
 * movimento real do braço.
 */
class MotionContinuityDetector(
    private val windowMs: Long = 5_000L,
    private val peakThresholdG: Float = 1.10f,
    private val minPeaksInWindow: Int = 3,
) {
    private data class Peak(val tMs: Long)

    private val peaks = ArrayDeque<Peak>()
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 9.81f
    private val hpAlpha = 0.92f
    private var lastPeakMs = 0L

    @Synchronized
    fun onSample(sample: ImuSample, timestampMs: Long = System.currentTimeMillis()) {
        val ax = sample.ax.toFloat() / 101.97f
        val ay = sample.ay.toFloat() / 101.97f
        val az = sample.az.toFloat() / 101.97f
        gravX = hpAlpha * gravX + (1 - hpAlpha) * ax
        gravY = hpAlpha * gravY + (1 - hpAlpha) * ay
        gravZ = hpAlpha * gravZ + (1 - hpAlpha) * az
        val lx = ax - gravX
        val ly = ay - gravY
        val lz = az - gravZ
        val magG = sqrt(lx * lx + ly * ly + lz * lz) / 9.80665f

        // Anti-pico: 200 ms entre picos contados.
        if (magG >= peakThresholdG && timestampMs - lastPeakMs > 200) {
            lastPeakMs = timestampMs
            peaks.addLast(Peak(timestampMs))
        }
        while (peaks.isNotEmpty() && timestampMs - peaks.first().tMs > windowMs) {
            peaks.removeFirst()
        }
    }

    @Synchronized
    fun isMoving(): Boolean = peaks.size >= minPeaksInWindow

    @Synchronized
    fun peakCountInWindow(): Int = peaks.size

    @Synchronized
    fun reset() {
        peaks.clear()
        gravX = 0f; gravY = 0f; gravZ = 9.81f
        lastPeakMs = 0L
    }
}
