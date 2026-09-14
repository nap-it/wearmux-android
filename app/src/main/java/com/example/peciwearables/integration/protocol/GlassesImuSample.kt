package com.example.peciwearables.integration.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One IMU sample from the óculos BNO085.
 *
 * BLE packet layout (18 bytes = 9 x int16 little-endian):
 *  [0-1]   ax  : int16 (mg - milli-g)
 *  [2-3]   ay  : int16 (mg)
 *  [4-5]   az  : int16 (mg)
 *  [6-7]   gx  : int16 (0.1 dps - deci-degrees per second)
 *  [8-9]   gy  : int16 (0.1 dps)
 *  [10-11] gz  : int16 (0.1 dps)
 *  [12-13] mx  : int16 (0.1 uT - deci-microtesla)
 *  [14-15] my  : int16 (0.1 uT)
 *  [16-17] mz  : int16 (0.1 uT)
 */
data class GlassesImuSample(
    val ax: Float, val ay: Float, val az: Float,  // m/s²
    val gx: Float, val gy: Float, val gz: Float,  // deg/s
    val mx: Float, val my: Float, val mz: Float,  // uT
) {
    companion object {
        const val PACKET_SIZE = 18

        fun parse(data: ByteArray): GlassesImuSample? {
            if (data.size < PACKET_SIZE) return null
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Accelerometer: mg -> m/s² (divide by 101.97)
            val ax = bb.short / 101.97f
            val ay = bb.short / 101.97f
            val az = bb.short / 101.97f

            // Gyroscope: 0.1 dps -> deg/s (divide by 10)
            val gx = bb.short / 10.0f
            val gy = bb.short / 10.0f
            val gz = bb.short / 10.0f

            // Magnetometer: 0.1 uT -> uT (divide by 10)
            val mx = bb.short / 10.0f
            val my = bb.short / 10.0f
            val mz = bb.short / 10.0f

            return GlassesImuSample(ax, ay, az, gx, gy, gz, mx, my, mz)
        }
    }
}
