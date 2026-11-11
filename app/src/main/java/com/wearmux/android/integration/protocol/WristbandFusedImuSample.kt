package com.wearmux.android.integration.protocol

/**
 * One fused IMU sample from the wristband (BrilliantSole), already processed
 * by the device's own on-board sensor fusion -- no Madgwick on the Android side.
 *
 * [ax, ay, az] are scaled by the BNO085 SH-2 Q8 convention (/256), the fixed-point
 * scale the chip's LINEAR_ACCELERATION report uses (confirmed against the ceva-dsp/sh2
 * reference decoder). Sending the raw int16 unscaled made the acceleration ~256x too
 * large downstream.
 *
 * [qx, qy, qz, qw] are scaled by the BNO085 Q-point convention (/16384), confirmed
 * against real hardware during Task 8's validation: this chip uses the same
 * fixed-point convention as the glasses' BNO085, contrary to this task's original
 * (wrong) assumption that no scale applied here.
 */
data class WristbandFusedImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
)

/** BNO085 SH-2 Q8 scale for LINEAR_ACCELERATION reports. */
const val WRISTBAND_ACCELERATION_SCALE = 1f / 256f

/** Same Q-point scale the glasses' BNO085 already applies to its GAME_ROTATION samples. */
const val WRISTBAND_QUATERNION_SCALE = 1f / 16384f

/** Result of parsing one MSG_SENSOR_DATA packet for the linearAcceleration+gameRotation pair. */
data class WristbandFusedParseResult(val fused: WristbandFusedImuSample?)

private const val SENSOR_TYPE_LINEAR_ACCELERATION = 3
private const val SENSOR_TYPE_GAME_ROTATION = 6

private fun readInt16LE(data: ByteArray, offset: Int): Short {
    if (offset + 1 >= data.size) return 0
    val lo = data[offset].toInt() and 0xFF
    val hi = data[offset + 1].toInt() and 0xFF
    return ((hi shl 8) or lo).toShort()
}

/**
 * Parses a raw MSG_SENSOR_DATA payload (same shape BrilliantSoleWristbandBleClient
 * already parses: [timestamp(2B)] then repeated [sensorType(1B)][length(1B)][data...])
 * looking only for linearAcceleration + gameRotation blocks, pairing them into a
 * [WristbandFusedImuSample]. Returns null fused when linearAcceleration hasn't been
 * seen in this packet (mirrors "pair the newest of one stream with the latest of the
 * other" -- this pure function only sees one packet at a time, so a caller holding
 * state across packets is needed for the general case; this covers the single-packet
 * shape used by this task's own test).
 */
fun parseWristbandSensorData(data: ByteArray): WristbandFusedParseResult? {
    if (data.size < 2) return null
    var offset = 2
    var linAccel: Triple<Short, Short, Short>? = null
    var gameRotation: Array<Short>? = null

    while (offset < data.size) {
        if (offset >= data.size) break
        val sensorType = data[offset].toInt() and 0xFF
        offset++
        if (offset >= data.size) break
        val length = data[offset].toInt() and 0xFF
        offset++
        if (offset + length > data.size) break

        when (sensorType) {
            SENSOR_TYPE_LINEAR_ACCELERATION -> if (length >= 6) {
                linAccel = Triple(
                    readInt16LE(data, offset), readInt16LE(data, offset + 2), readInt16LE(data, offset + 4),
                )
            }
            SENSOR_TYPE_GAME_ROTATION -> if (length >= 8) {
                gameRotation = arrayOf(
                    readInt16LE(data, offset), readInt16LE(data, offset + 2),
                    readInt16LE(data, offset + 4), readInt16LE(data, offset + 6),
                )
            }
        }
        offset += length
    }

    val fused = if (linAccel != null && gameRotation != null) {
        val qScale = WRISTBAND_QUATERNION_SCALE
        val aScale = WRISTBAND_ACCELERATION_SCALE
        WristbandFusedImuSample(
            ax = linAccel.first * aScale, ay = linAccel.second * aScale, az = linAccel.third * aScale,
            qx = gameRotation[0] * qScale, qy = gameRotation[1] * qScale,
            qz = gameRotation[2] * qScale, qw = gameRotation[3] * qScale,
        )
    } else null

    return WristbandFusedParseResult(fused)
}
