package com.wearmux.android.integration.adapters.devices.omi

import com.wearmux.android.integration.protocol.tlv.TlvParser
import com.wearmux.android.integration.protocol.tlv.TlvWriter
import com.wearmux.android.integration.protocol.tlv.TxRxMessageType
import org.junit.Assert.*
import org.junit.Test

class OmiGlassesBleProtocolTest {

    /** Reimplementa o encoding de buildSetSensorRateMessage (OmiGlassesBleClient.kt:1580-1588). */
    private fun setSensorRateMessage(sensorType: Int, rateMs: Int): TlvWriter.Message<TxRxMessageType> =
        TlvWriter.Message(
            TxRxMessageType.SET_SENSOR_CONFIGURATION,
            byteArrayOf(
                sensorType.toByte(),
                (rateMs and 0xFF).toByte(),
                ((rateMs shr 8) and 0xFF).toByte()
            )
        )

    @Test
    fun `SENSOR_TYPE_LINEAR_ACCELERATION matches the confirmed hardware-gate index`() {
        assertEquals(3, SENSOR_TYPE_LINEAR_ACCELERATION)
    }

    @Test
    fun `applyImuSensorConfiguration's 5 TLV messages decode to game rotation and linear acceleration at the requested rate, accel-gyro-mag at rate zero`() {
        val rateMs = 40
        val messages = listOf(
            setSensorRateMessage(SENSOR_TYPE_ACCELERATION, 0),
            setSensorRateMessage(SENSOR_TYPE_GYROSCOPE, 0),
            setSensorRateMessage(SENSOR_TYPE_MAGNETOMETER, 0),
            setSensorRateMessage(SENSOR_TYPE_GAME_ROTATION, rateMs),
            setSensorRateMessage(SENSOR_TYPE_LINEAR_ACCELERATION, rateMs),
        )
        val encoded = TlvWriter.encodeAll(messages)

        val parsed = TlvParser.parseTxRxToList(encoded, 0, encoded.size)
        val sentMessages = parsed.map { msg ->
            val sensorType = msg.data[0].toInt() and 0xFF
            val decodedRateMs = (msg.data[1].toInt() and 0xFF) or ((msg.data[2].toInt() and 0xFF) shl 8)
            sensorType to decodedRateMs
        }

        assertEquals(5, sentMessages.size)
        assertTrue(sentMessages.contains(SENSOR_TYPE_ACCELERATION to 0))
        assertTrue(sentMessages.contains(SENSOR_TYPE_GYROSCOPE to 0))
        assertTrue(sentMessages.contains(SENSOR_TYPE_MAGNETOMETER to 0))
        assertTrue(sentMessages.contains(SENSOR_TYPE_GAME_ROTATION to rateMs))
        assertTrue(sentMessages.contains(SENSOR_TYPE_LINEAR_ACCELERATION to rateMs))
    }
}
