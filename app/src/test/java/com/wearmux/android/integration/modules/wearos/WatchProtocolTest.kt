package com.wearmux.android.integration.modules.wearos

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun `parseSample decodes quaternion Q14 fields from reserved bytes`() {
        val buf = ByteBuffer.allocate(WatchProtocol.IMU_SAMPLE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(123_456L)
        buf.putShort(100); buf.putShort(200); buf.putShort(300) // accel
        buf.putShort(10); buf.putShort(20); buf.putShort(30)    // gyro
        buf.putShort(1); buf.putShort(2); buf.putShort(3)       // mag
        buf.putShort(8192); buf.putShort(-4096); buf.putShort(0) // qx=0.5, qy=-0.25, qz=0.0 em Q14
        buf.flip()

        val sample = WatchProtocol.parseSample(buf)!!

        assertEquals(8192.toShort(), sample.qxQ14)
        assertEquals((-4096).toShort(), sample.qyQ14)
        assertEquals(0.toShort(), sample.qzQ14)
    }
}
