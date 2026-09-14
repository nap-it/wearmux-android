package com.example.peciwearables.integration.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder


data class ImuSample(
    val ax: Short, val ay: Short, val az: Short,   // accel mg
    val gx: Short, val gy: Short, val gz: Short,   // gyro mdps
    val mx: Short, val my: Short, val mz: Short,   // mag µT×10
    val pressure: ShortArray                        // 8 channels
) {
    companion object {
        const val SAMPLE_SIZE = 34

        // sensorMask bits
        const val MASK_ACCEL = 0x0001
        const val MASK_GYRO = 0x0002
        const val MASK_MAG = 0x0004
        const val MASK_PRESSURE = 0x0008
        const val MASK_STEP = 0x0010
        const val MASK_ACTIVITY = 0x0020
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return ax == other.ax && ay == other.ay && az == other.az &&
                gx == other.gx && gy == other.gy && gz == other.gz &&
                mx == other.mx && my == other.my && mz == other.mz &&
                pressure.contentEquals(other.pressure)
    }

    override fun hashCode(): Int = ax.toInt() * 31 + ay.toInt()
}

data class ImuPayload(
    val sensorMask: Int,
    val sampleCount: Int,
    val samplePeriodUs: Int,
    val baseTimestampUs: Long,
    val samples: List<ImuSample>
) {
    companion object {
        private const val SUB_HEADER_SIZE = 14

        fun parse(payload: ByteArray): ImuPayload {
            require(payload.size >= SUB_HEADER_SIZE) { "IMU payload too small" }

            val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

            val sensorMask = bb.short.toInt() and 0xFFFF
            val sampleCount = bb.get().toInt() and 0xFF

            // 3-byte samplePeriod (24-bit little-endian)
            val b0 = bb.get().toInt() and 0xFF
            val b1 = bb.get().toInt() and 0xFF
            val b2 = bb.get().toInt() and 0xFF
            val samplePeriodUs = b0 or (b1 shl 8) or (b2 shl 16)

            val baseTimestampUs = bb.long

            val samples = mutableListOf<ImuSample>()
            for (i in 0 until sampleCount) {
                if (bb.remaining() < ImuSample.SAMPLE_SIZE) break

                val ax = bb.short; val ay = bb.short; val az = bb.short
                val gx = bb.short; val gy = bb.short; val gz = bb.short
                val mx = bb.short; val my = bb.short; val mz = bb.short
                val pressure = ShortArray(8) { bb.short }

                samples.add(ImuSample(ax, ay, az, gx, gy, gz, mx, my, mz, pressure))
            }

            return ImuPayload(sensorMask, sampleCount, samplePeriodUs, baseTimestampUs, samples)
        }
    }
}
