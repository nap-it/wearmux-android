package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.sensors.PhoneAccelSample
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlin.math.abs
import kotlin.math.sqrt


class CyclistModeDetector(
    private val minCyclingSpeedMps: Float = 2.5f,
    private val dwellMs: Long = 8_000L,
) {
    enum class Mode { UNKNOWN, WALKING, CYCLING }

    // --- Velocidade ---
    private var lastGoodSpeedMs: Long = 0L
    private var fastSinceMs: Long = 0L
    private var slowSinceMs: Long = 0L

    // --- Cadência (pulseira / watch) ---
    private val crossingsWindow = ArrayDeque<Long>()
    private var prevLin = 0f
    private var hpAlpha = 0.92f
    private var gravX = 0f; private var gravY = 0f; private var gravZ = 9.81f

    fun onGps(gps: PhoneGpsLocation, nowMs: Long = System.currentTimeMillis()) {
        if (gps.accuracy > 30f) return
        val now = nowMs
        lastGoodSpeedMs = now
        if (gps.speed >= minCyclingSpeedMps) {
            if (fastSinceMs == 0L) fastSinceMs = now
            slowSinceMs = 0L
        } else {
            fastSinceMs = 0L
            if (slowSinceMs == 0L) slowSinceMs = now
        }
    }

    fun onImu(sample: ImuSample, timestampMs: Long = System.currentTimeMillis()) {
        // High-pass para isolar aceleração linear do pulso.
        val ax = sample.ax.toFloat() / 101.97f
        val ay = sample.ay.toFloat() / 101.97f
        val az = sample.az.toFloat() / 101.97f
        gravX = hpAlpha * gravX + (1 - hpAlpha) * ax
        gravY = hpAlpha * gravY + (1 - hpAlpha) * ay
        gravZ = hpAlpha * gravZ + (1 - hpAlpha) * az
        val lx = ax - gravX
        val ly = ay - gravY
        val lz = az - gravZ
        val mag = sqrt(lx * lx + ly * ly + lz * lz)

        // Zero-crossing em torno do valor anterior (≈ derivada).
        if ((prevLin > 0.5f && mag <= 0.5f) || (prevLin <= 0.5f && mag > 0.5f)) {
            crossingsWindow.addLast(timestampMs)
        }
        prevLin = mag

        // Mantém janela de 4 s.
        while (crossingsWindow.isNotEmpty() && timestampMs - crossingsWindow.first() > 4_000) {
            crossingsWindow.removeFirst()
        }
    }

    /** Cadência aproximada em Hz (zero-crossings/s/2). */
    fun cadenceHz(): Float {
        if (crossingsWindow.size < 2) return 0f
        val span = (crossingsWindow.last() - crossingsWindow.first()).coerceAtLeast(1L)
        return (crossingsWindow.size - 1).toFloat() / 2f / (span / 1000f)
    }

    /**
     * Modo actual. Estável só após `dwellMs` no mesmo estado para evitar
     * flips de modo entre paragens curtas em sinaleiros.
     */
    fun currentMode(nowMs: Long = System.currentTimeMillis()): Mode {
        val cad = cadenceHz()
        val cyclingCadence = cad in 0.8f..1.6f
        val fastEnough = fastSinceMs > 0 && (nowMs - fastSinceMs) >= dwellMs
        val slowEnough = slowSinceMs > 0 && (nowMs - slowSinceMs) >= dwellMs
        return when {
            fastEnough && cyclingCadence -> Mode.CYCLING
            slowEnough && !cyclingCadence -> Mode.WALKING
            else -> Mode.UNKNOWN
        }
    }
}
