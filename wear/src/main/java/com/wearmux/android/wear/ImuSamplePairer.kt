package com.wearmux.android.wear

/**
 * Pairs asynchronous accelerometer/gyroscope samples with a later-arriving fused
 * orientation (GAME_ROTATION_VECTOR) timestamp, picking the buffered accel/gyro
 * sample whose timestamp is closest to the orientation sample's timestamp.
 *
 * This class is intentionally framework-free (no android.* imports) so it can be
 * unit-tested on the plain JVM.
 */
class ImuSamplePairer(private val maxBufferSize: Int = 32) {

    data class AccelGyroSample(
        val timestampNs: Long,
        val ax: Short, val ay: Short, val az: Short,
        val gx: Short, val gy: Short, val gz: Short,
    )

    private val buffer = ArrayDeque<AccelGyroSample>()

    /** Appends [sample], evicting the oldest buffered sample if over [maxBufferSize]. */
    fun record(sample: AccelGyroSample) {
        buffer.addLast(sample)
        while (buffer.size > maxBufferSize) {
            buffer.removeFirst()
        }
    }

    /**
     * Returns the buffered sample whose [AccelGyroSample.timestampNs] is closest to
     * [orientationTimestampNs], or null if nothing has been recorded yet. On an exact
     * tie, the earlier-recorded (older) sample is returned.
     */
    fun pairWith(orientationTimestampNs: Long): AccelGyroSample? {
        var best: AccelGyroSample? = null
        var bestDistance = Long.MAX_VALUE
        for (sample in buffer) {
            val distance = Math.abs(sample.timestampNs - orientationTimestampNs)
            if (distance < bestDistance) {
                bestDistance = distance
                best = sample
            }
        }
        return best
    }
}
