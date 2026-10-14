package com.wearmux.android.integration.protocol

import org.junit.Assert.*
import org.junit.Test

class WristbandFusedImuSampleTest {

    @Test
    fun `parses a MSG_SENSOR_DATA packet with linearAcceleration and gameRotation blocks into a WristbandFusedImuSample`() {
        val packet = buildSensorDataPacket(
            timestampRaw16 = 0,
            blocks = listOf(
                3 to shortArrayOf(100, 200, 300),           // SENSOR_TYPE_LINEAR_ACCELERATION, raw int16 LE x,y,z
                6 to shortArrayOf(1638, 3277, 4915, 14746), // SENSOR_TYPE_GAME_ROTATION, raw int16 LE x,y,z,w (Q-point /16384)
            ),
        )

        val result = parseWristbandSensorData(packet)

        assertNotNull(result?.fused)
        val sample = result!!.fused!!
        assertEquals(100.toShort(), sample.ax); assertEquals(200.toShort(), sample.ay); assertEquals(300.toShort(), sample.az)
        // Scaled by the BNO085 Q-point convention (/16384), confirmed against real hardware in Task 8.
        assertEquals(0.1f, sample.qx, 0.001f); assertEquals(0.2f, sample.qy, 0.001f)
        assertEquals(0.3f, sample.qz, 0.001f); assertEquals(0.9f, sample.qw, 0.001f)
    }

    @Test
    fun `returns null fused sample when only linearAcceleration has arrived, no gameRotation yet`() {
        val packet = buildSensorDataPacket(
            timestampRaw16 = 0,
            blocks = listOf(3 to shortArrayOf(100, 200, 300)),
        )

        val result = parseWristbandSensorData(packet)

        assertNull(result?.fused)
    }

    /** Builds a raw MSG_SENSOR_DATA payload: [timestamp(2B LE)] then [sensorType(1B)][length(1B)][data...] per block. */
    private fun buildSensorDataPacket(timestampRaw16: Int, blocks: List<Pair<Int, ShortArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(timestampRaw16 and 0xFF)
        out.write((timestampRaw16 shr 8) and 0xFF)
        for ((sensorType, values) in blocks) {
            out.write(sensorType)
            out.write(values.size * 2)
            for (v in values) {
                out.write(v.toInt() and 0xFF)
                out.write((v.toInt() shr 8) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
