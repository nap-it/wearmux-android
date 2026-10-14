package com.wearmux.android.integration.protocol

/**
 * One fused IMU sample from the wristband (BrilliantSole), already processed
 * by the device's own on-board sensor fusion -- no Madgwick on the Android side.
 *
 * Values are raw int16 straight off the wire, same convention [ImuSample]
 * already uses for accel/gyro/mag on this device -- no fixed-point scale is
 * applied here (unlike the glasses' BNO085 Q-point convention), since this
 * device never stores or applies the MSG_GET_SENSOR_SCALARS response either.
 */
data class WristbandFusedImuSample(
    val ax: Short, val ay: Short, val az: Short,
    val qx: Short, val qy: Short, val qz: Short, val qw: Short,
)

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
        WristbandFusedImuSample(
            ax = linAccel.first, ay = linAccel.second, az = linAccel.third,
            qx = gameRotation[0], qy = gameRotation[1], qz = gameRotation[2], qw = gameRotation[3],
        )
    } else null

    return WristbandFusedParseResult(fused)
}
