package com.example.peciwearables.integration.wearable.devices.esp32

import com.example.peciwearables.integration.protocol.ImageChunk
import com.example.peciwearables.integration.protocol.ImuPayload
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Esp32WearableSessionTest {

    private class FakeControlClient : Esp32ControlClient() {
        var startCalled = false
        var stopCalled = false
        override suspend fun checkStatus(): Esp32Status = Esp32Status("ok", true, false)
        override suspend fun startUdpStream(port: Int, ip: String?): Boolean {
            startCalled = true
            return true
        }
        override suspend fun stopUdpStream(): Boolean {
            stopCalled = true
            return true
        }
    }

    private class FakeUdpClient : Esp32UdpClient() {
        var started = false
        var stopped = false
        override fun startListening(scope: CoroutineScope, esp32Ip: String?) {
            started = true
        }
        override fun stop() {
            stopped = true
        }
    }

    private class FakeRealtimeSink : WearableRealtimeSink {
        var imageChunks = 0
        var imuPayloads = 0
        override fun onStreamingJpeg(id: WearableId, jpegBytes: ByteArray) { imageChunks++ }
        override fun onImuSample(id: WearableId, sample: com.example.peciwearables.integration.protocol.ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) { imuPayloads++ }
    }

    @Test
    fun `connect starts UDP and control clients`() = runTest {
        val control = FakeControlClient()
        val udp = FakeUdpClient()
        val session = Esp32WearableSession(
            id = WearableId("esp32-1"),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            controlClient = control,
            udpClient = udp
        )

        val result = session.connect()

        assertTrue(result)
        assertTrue(control.startCalled)
        assertTrue(udp.started)
    }

    @Test
    fun `close stops UDP and control clients`() = runTest {
        val control = FakeControlClient()
        val udp = FakeUdpClient()
        val session = Esp32WearableSession(
            id = WearableId("esp32-1"),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            controlClient = control,
            udpClient = udp
        )

        session.close()

        assertTrue(control.stopCalled)
        assertTrue(udp.stopped)
    }

    @Test
    fun `exposes correct capabilities`() {
        val session = Esp32WearableSession(
            id = WearableId("esp32-1"),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            controlClient = FakeControlClient(),
            udpClient = FakeUdpClient()
        )

        assertTrue(session.capabilities.contains(WearableCapability.VIDEO_STREAM))
        assertTrue(session.capabilities.contains(WearableCapability.IMU_STREAM))
        assertEquals(2, session.capabilities.size)
    }

    @Test
    fun `relays UDP events to realtime sink`() {
        val sink = FakeRealtimeSink()
        val udp = FakeUdpClient()
        val session = Esp32WearableSession(
            id = WearableId("esp32-1"),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            controlClient = FakeControlClient(),
            udpClient = udp,
            realtimeSink = sink
        )

        val dummyImageChunk = ImageChunk(1, 640, 480, 1L, 0, 1, 100, ByteArray(100))
        val dummyImuSample = com.example.peciwearables.integration.protocol.ImuSample(
            0, 0, 0, 0, 0, 0, 0, 0, 0, ShortArray(8)
        )
        val dummyImuPayload = ImuPayload(0, 1, 0, 0L, listOf(dummyImuSample))

        udp.onImageChunk?.invoke(dummyImageChunk)
        udp.onImuPayload?.invoke(dummyImuPayload)

        assertEquals(1, sink.imageChunks)
        assertEquals(1, sink.imuPayloads)
    }
}
