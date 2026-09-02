package com.example.peciwearables.integration.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableEventContractTest {

    @Test
    fun imageFrameReceived_equalityIsContentBased() {
        val a = WearableEvent.ImageFrameReceived(
            bytes = byteArrayOf(1, 2, 3),
            format = ImageFormat.JPEG,
            orientationDeg = 180,
            timestampMs = 100L,
        )
        val b = WearableEvent.ImageFrameReceived(
            bytes = byteArrayOf(1, 2, 3),
            format = ImageFormat.JPEG,
            orientationDeg = 180,
            timestampMs = 100L,
        )
        val c = a.copy(bytes = byteArrayOf(9))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun audioFrameReceived_equalityIsContentBased() {
        val a = WearableEvent.AudioFrameReceived(
            bytes = byteArrayOf(1),
            codec = AudioCodec.MULAW,
            sampleRateHz = 16_000,
            channels = 1,
            seq = 5L,
            timestampMs = 50L,
        )
        val b = a.copy()
        val c = a.copy(codec = AudioCodec.PCM16)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun imuSampleReceived_equalityHandlesNullableArrays() {
        val a = WearableEvent.ImuSampleReceived(
            accel = floatArrayOf(0.1f, 0.2f, 0.3f),
            gyro = floatArrayOf(1f, 2f, 3f),
            timestampMs = 0L,
        )
        val b = a.copy()
        val c = a.copy(mag = floatArrayOf(0f, 0f, 0f))

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun whenIsExhaustive() {
        val event: WearableEvent = WearableEvent.BatteryChanged(percent = 50, timestampMs = 0L)
        // Compile-time check: branch missing causes "when must be exhaustive"
        val tag: String = when (event) {
            is WearableEvent.ImageFrameReceived -> "image"
            is WearableEvent.AudioFrameReceived -> "audio"
            is WearableEvent.ImuSampleReceived -> "imu"
            is WearableEvent.BatteryChanged -> "battery"
            is WearableEvent.ConnectionChanged -> "conn"
            is WearableEvent.DeviceInfoReceived -> "info"
            is WearableEvent.DeviceLog -> "log"
            is WearableEvent.DeviceError -> "error"
            is WearableEvent.MlInferenceReceived -> "ml"
        }
        assertTrue(tag == "battery")
    }

    @Test
    fun commandResult_unsupported_carriesCapability() {
        val r: CommandResult = CommandResult.Unsupported(WearableCapability.HAPTIC)
        assertTrue(r is CommandResult.Unsupported)
        assertEquals(WearableCapability.HAPTIC, (r as CommandResult.Unsupported).capability)
    }
}
