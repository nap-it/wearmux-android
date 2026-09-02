package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.protocol.ImuSample
import org.junit.Test

class WearableRealtimeSinkTest {

    @Test
    fun none_acceptsAllCriticalPayloadShapes() {
        val sink = WearableRealtimeSink.NONE
        val id = WearableId("AA:BB:CC")
        val imu = ImuSample(1, 2, 3, 4, 5, 6, 7, 8, 9, ShortArray(8))
        val bytes = byteArrayOf(1, 2, 3)
        val quat = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)

        sink.onCameraChunk(id, subType = 1, bytes = bytes)
        sink.onCameraImageFile(id, bytes)
        sink.onPhotoJpeg(id, jpegBytes = bytes, orientationDeg = 90)
        sink.onAudioPacket(id, bytes, codecId = 21)
        sink.onImuPacket(id, bytes)
        sink.onImuSample(id, imu, timestampRaw16 = 7, timestampEstimatedMs = 42L)
        sink.onQuaternion(id, quat)
        sink.onTfliteInference(id, label = "walk", score = 0.9f, timestampEstimatedMs = 42L, values = floatArrayOf(0.9f))
    }
}
