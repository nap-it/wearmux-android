package com.example.peciwearables.integration.safety

import com.example.peciwearables.integration.protocol.GlassesImuSample
import kotlin.math.atan2


class HeadRotationDetector(
    private val windowMs: Long = 8_000L,
    private val minTotalDeg: Float = 30f,
    private val minSideDeg: Float = 22f,
) {
    private data class HeadingSample(
        val timestampMs: Long,
        val hx: Float,
        val hy: Float,
        val hz: Float,
    )
    private data class MagSample(val timestampMs: Long, val relDeg: Float)

    private val gyroSamples = ArrayDeque<HeadingSample>()
    private val magSamples = ArrayDeque<MagSample>()
    private var hx = 0f
    private var hy = 0f
    private var hz = 0f
    private var lastSampleMs = 0L
    private var magBaselineDeg: Float? = null

    @Synchronized
    fun onSample(sample: GlassesImuSample, timestampMs: Long = System.currentTimeMillis()) {
        val dt = if (lastSampleMs > 0L) {
            (timestampMs - lastSampleMs).coerceAtLeast(0).coerceAtMost(100) / 1000f
        } else 0f
        lastSampleMs = timestampMs

        hx += sample.gx * dt
        hy += sample.gy * dt
        hz += sample.gz * dt
        gyroSamples.addLast(HeadingSample(timestampMs, hx, hy, hz))

        val mx = sample.mx
        val my = sample.my
        if (mx * mx + my * my > 25f) {
            val absDeg = (Math.toDegrees(atan2(my.toDouble(), mx.toDouble())).toFloat() + 360f) % 360f
            if (magBaselineDeg == null) magBaselineDeg = absDeg
            val rel = signedAngularDelta(absDeg - magBaselineDeg!!)
            magSamples.addLast(MagSample(timestampMs, rel))
        }

        val cutoff = timestampMs - windowMs
        while (gyroSamples.isNotEmpty() && gyroSamples.first().timestampMs < cutoff) {
            gyroSamples.removeFirst()
        }
        while (magSamples.isNotEmpty() && magSamples.first().timestampMs < cutoff) {
            magSamples.removeFirst()
        }
    }

    private fun signedAngularDelta(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    @Synchronized
    fun spreadDeg(): Float {
        if (gyroSamples.isEmpty()) return 0f
        val spreadX = gyroSamples.maxOf { it.hx } - gyroSamples.minOf { it.hx }
        val spreadY = gyroSamples.maxOf { it.hy } - gyroSamples.minOf { it.hy }
        val spreadZ = gyroSamples.maxOf { it.hz } - gyroSamples.minOf { it.hz }
        return maxOf(spreadX, spreadY, spreadZ)
    }

    @Synchronized
    fun rotatedRecently(): Boolean = spreadDeg() >= minTotalDeg

    @Synchronized
    fun lookedBothWays(): Boolean {
        if (gyroSamples.isEmpty()) return false
        // Check each axis for symmetric rotation; accept the axis with the best bilateral signal
        return listOf(
            { s: HeadingSample -> s.hx },
            { s: HeadingSample -> s.hy },
            { s: HeadingSample -> s.hz },
        ).any { axis ->
            val max = gyroSamples.maxOf { axis(it) }
            val min = gyroSamples.minOf { axis(it) }
            if (min > -minSideDeg || max < minSideDeg) return@any false

            if (magSamples.size >= 4) {
                val magMax = magSamples.maxOf { it.relDeg }
                val magMin = magSamples.minOf { it.relDeg }
                val magSpread = magMax - magMin
                val magClearlyBilateral =
                    magMin <= -(minSideDeg * 0.6f) && magMax >= (minSideDeg * 0.6f)
                if (magClearlyBilateral) return@any true
                // The BNO085 gyro is the primary signal here. Magnetometer can
                // be weak/noisy indoors and should only veto when it is stable
                // enough to say the head barely moved.
                magSpread >= minSideDeg * 0.8f
            } else {
                true
            }
        }
    }

    @Synchronized
    fun reset() {
        gyroSamples.clear()
        magSamples.clear()
        hx = 0f
        hy = 0f
        hz = 0f
        lastSampleMs = 0L
        magBaselineDeg = null
    }
}
