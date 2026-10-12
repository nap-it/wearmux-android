package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.api.ImuCoCoSinkConfig
import com.wearmux.android.integration.protocol.GlassesImuSample
import com.wearmux.android.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport : ImuUdpTransport {
    val sent = mutableListOf<Triple<String, Int, String>>() // host, port, decoded ascii message
    var failNext = false

    override fun send(host: String, port: Int, bytes: ByteArray) {
        if (failNext) {
            failNext = false
            throw java.io.IOException("simulated send failure")
        }
        sent.add(Triple(host, port, String(bytes, Charsets.US_ASCII)))
    }
}

class ImuUdpTriStreamSinkTest {

    private fun imuSample(ax: Int, ay: Int, az: Int): ImuSample = ImuSample(
        ax.toShort(), ay.toShort(), az.toShort(),
        0, 0, 0,
        0, 0, 0,
        ShortArray(8),
    )

    @Test
    fun `glasses sample produces a correctly formatted UDP message with 13 fields`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(
            scope = scope,
            config = { config },
            glassesImuStream = glassesStream,
            watchImuStream = watchStream,
            phoneImuStream = phoneStream,
            transport = transport,
        )
        sink.start()
        // Let the collector coroutines actually start collecting before anything
        // emits -- otherwise the first emit() can land in the flow's buffer before
        // any collector is scheduled to read it.
        testScheduler.runCurrent()

        glassesStream.emit(GlassesImuSample(ax = 1f, ay = 2f, az = 3f, gx = 0f, gy = 0f, gz = 0f, mx = 10f, my = 0f, mz = 0f))
        testScheduler.advanceTimeBy(25) // one tick at 50Hz (20ms period)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue("expected at least one packet sent", transport.sent.isNotEmpty())
        val (host, port, message) = transport.sent.first { it.third.contains(";glasses;") }
        assertEquals("127.0.0.1", host)
        assertEquals(8001, port)
        assertTrue(message.startsWith("u1;glasses;motion:"))
        val sample = message.substringAfter("motion:").trim()
        val allFields = sample.split(" ")
        // live_demo.py's parse_motion_message does motion_text.split(' ')[1:]
        // (drops the timestamp) before counting "13 values" -- confirmed against
        // live_demo_simulation_data_sender.py's reference sender, which emits
        // exactly timestamp + userAcc(3) + grav(3) + gyro(3) + quat(4) = 14 total,
        // 13 after dropping the timestamp.
        assertEquals(14, allFields.size)
        assertEquals(13, allFields.drop(1).size)
    }

    @Test
    fun `disabled config sends nothing`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = false, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, transport)
        sink.start()
        testScheduler.runCurrent()
        glassesStream.emit(GlassesImuSample(ax = 1f, ay = 2f, az = 3f, gx = 0f, gy = 0f, gz = 0f, mx = 10f, my = 0f, mz = 0f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()
        sink.stop()
        scope.cancel()

        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `one device offline does not stop the other two from sending`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, transport)
        sink.start()
        testScheduler.runCurrent()

        // Only glasses and phone report; watch never sends anything.
        glassesStream.emit(GlassesImuSample(ax = 1f, ay = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f, mx = 10f, my = 0f, mz = 0f))
        phoneStream.emit(GlassesImuSample(ax = 0f, ay = 1f, az = 0f, gx = 0f, gy = 0f, gz = 0f, mx = 5f, my = 0f, mz = 0f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue(transport.sent.any { it.third.contains(";glasses;") })
        assertTrue(transport.sent.any { it.third.contains(";phone;") })
        assertTrue(transport.sent.none { it.third.contains(";watch;") })
    }

    @Test
    fun `transport throwing on one tick does not stop subsequent ticks`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<GlassesImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, transport)
        sink.start()
        testScheduler.runCurrent()

        glassesStream.emit(GlassesImuSample(ax = 1f, ay = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f, mx = 10f, my = 0f, mz = 0f))
        transport.failNext = true
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()
        val afterFirstTick = transport.sent.size

        glassesStream.emit(GlassesImuSample(ax = 1f, ay = 0f, az = 0f, gx = 0f, gy = 0f, gz = 0f, mx = 10f, my = 0f, mz = 0f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue("sink should keep ticking after a send failure", transport.sent.size > afterFirstTick)
    }
}
