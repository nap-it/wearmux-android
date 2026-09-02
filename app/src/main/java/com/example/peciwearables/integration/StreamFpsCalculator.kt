package com.example.peciwearables.integration

internal fun calculateRollingStreamFps(frameTimestampsMs: List<Long>): Float {
    if (frameTimestampsMs.size < 2) return 0f

    val elapsedMs = (frameTimestampsMs.last() - frameTimestampsMs.first()).coerceAtLeast(1L)
    val frameIntervals = (frameTimestampsMs.size - 1).coerceAtLeast(1)
    return frameIntervals * 1000f / elapsedMs.toFloat()
}
