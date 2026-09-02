package com.example.peciwearables.integration.safety

class GyIntegralCalculator(private val windowMs: Long = 8_000L) {

    private val window = ArrayDeque<Pair<Long, Float>>()
    private var integratedAngle = 0f
    private var lastTs: Long = -1L

    @Synchronized
    fun onGySample(gy: Float, nowMs: Long) {
        if (lastTs >= 0L) {
            val dtSec = (nowMs - lastTs) / 1000f
            integratedAngle += gy * dtSec
        }
        lastTs = nowMs
        window.addLast(nowMs to integratedAngle)
        while (window.isNotEmpty() && nowMs - window.first().first >= windowMs) {
            window.removeFirst()
        }
    }

    @Synchronized
    fun integralDeg(): Float = integratedAngle

    @Synchronized
    fun windowRelativeDeg(): Float? {
        val base = window.firstOrNull()?.second ?: return null
        return integratedAngle - base
    }

    @Synchronized
    fun spreadDeg(): Float {
        if (window.size < 2) return 0f
        val vals = window.map { (_, v) -> v }
        return vals.max() - vals.min()
    }

    @Synchronized
    fun reset() {
        window.clear()
        integratedAngle = 0f
        lastTs = -1L
    }
}
