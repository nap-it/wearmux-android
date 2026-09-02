package com.example.peciwearables.integration.safety

class YawSpreadCalculator(private val windowMs: Long = 8_000L) {

    private val window = ArrayDeque<Pair<Long, Float>>()

    @Synchronized
    fun onYawSample(yaw: Float, nowMs: Long): Float {
        window.addLast(nowMs to yaw)
        window.removeAll { (ts, _) -> nowMs - ts >= windowMs }
        return spread()
    }

    @Synchronized
    fun spread(): Float {
        if (window.size < 2) return 0f
        // Map yaw [-180, 180] to [0, 360) for circular arithmetic
        val angles = window.map { (_, y) -> ((y % 360f) + 360f) % 360f }.sorted()
        val gaps = mutableListOf<Float>()
        for (i in 0 until angles.size - 1) gaps.add(angles[i + 1] - angles[i])
        gaps.add(360f - angles.last() + angles.first()) // wrap-around gap
        return 360f - gaps.max()
    }

    @Synchronized
    fun reset() {
        window.clear()
    }
}
