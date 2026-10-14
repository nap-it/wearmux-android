package com.wearmux.android.integration.modules.sensors

import com.wearmux.android.integration.api.ImuCoCoSinkConfig
import com.wearmux.android.integration.protocol.GlassesFusedImuSample
import com.wearmux.android.integration.protocol.ImuSample
import com.wearmux.android.integration.protocol.PhoneFusedImuSample
import com.wearmux.android.integration.protocol.WristbandFusedImuSample
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
    fun `glasses sample is sent using the already-fused quaternion, with zero gravity and gyro fields`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(
            scope = scope,
            config = { config },
            glassesImuStream = glassesStream,
            watchImuStream = watchStream,
            phoneImuStream = phoneStream,
            wristbandImuStream = wristbandStream,
            transport = transport,
        )
        sink.start()
        // Let the collector coroutines actually start collecting before anything
        // emits -- otherwise the first emit() can land in the flow's buffer before
        // any collector is scheduled to read it.
        testScheduler.runCurrent()

        glassesStream.emit(GlassesFusedImuSample(ax = 1f, ay = 2f, az = 3f, qx = 0.1f, qy = 0.2f, qz = 0.3f, qw = 0.9f))
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
        val fields = sample.split(" ")
        // timestamp userAcc(3) grav(3) gyro(3) quat(4) = 14 fields total.
        assertEquals(14, fields.size)
        assertEquals(1f, fields[1].toFloat(), 0.0001f) // userAcc.x -- straight from the fused sample, no Madgwick
        assertEquals(2f, fields[2].toFloat(), 0.0001f)
        assertEquals(3f, fields[3].toFloat(), 0.0001f)
        assertEquals(0f, fields[4].toFloat(), 0.0001f) // grav.x -- zero-filled, IMUCoCo never reads this
        assertEquals(0f, fields[5].toFloat(), 0.0001f)
        assertEquals(0f, fields[6].toFloat(), 0.0001f)
        assertEquals(0f, fields[7].toFloat(), 0.0001f) // gyro.x -- zero-filled, IMUCoCo never reads this
        assertEquals(0f, fields[8].toFloat(), 0.0001f)
        assertEquals(0f, fields[9].toFloat(), 0.0001f)
        assertEquals(0.1f, fields[10].toFloat(), 0.0001f) // quat.x -- straight from the fused sample, no Madgwick
        assertEquals(0.2f, fields[11].toFloat(), 0.0001f)
        assertEquals(0.3f, fields[12].toFloat(), 0.0001f)
        assertEquals(0.9f, fields[13].toFloat(), 0.0001f)
    }

    @Test
    fun `disabled config sends nothing`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = false, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, wristbandStream, transport)
        sink.start()
        testScheduler.runCurrent()
        glassesStream.emit(GlassesFusedImuSample(ax = 1f, ay = 2f, az = 3f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()
        sink.stop()
        scope.cancel()

        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `device stops sending once its active flag turns false, without affecting other devices`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")
        var watchActive = true

        val sink = ImuUdpTriStreamSink(
            scope = scope,
            config = { config },
            glassesImuStream = glassesStream,
            watchImuStream = watchStream,
            phoneImuStream = phoneStream,
            wristbandImuStream = wristbandStream,
            transport = transport,
            isWatchActive = { watchActive },
        )
        sink.start()
        testScheduler.runCurrent()

        watchStream.emit(imuSample(1, 0, 0))
        phoneStream.emit(PhoneFusedImuSample(ax = 1f, ay = 0f, az = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        assertTrue("watch should send while active", transport.sent.any { it.third.contains(";watch;") })
        assertTrue("phone should send regardless of watch", transport.sent.any { it.third.contains(";phone;") })

        transport.sent.clear()
        watchActive = false
        // Watch stream has gone quiet (IMU turned off); its stale cached
        // sample must not keep being flushed on subsequent ticks.
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue("watch must stop sending once inactive", transport.sent.none { it.third.contains(";watch;") })
        assertTrue("phone must keep sending after watch goes inactive", transport.sent.any { it.third.contains(";phone;") })
    }

    @Test
    fun `one device offline does not stop the others from sending`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, wristbandStream, transport)
        sink.start()
        testScheduler.runCurrent()

        // Only glasses and phone report; watch and wristband never send anything.
        glassesStream.emit(GlassesFusedImuSample(ax = 1f, ay = 0f, az = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        phoneStream.emit(PhoneFusedImuSample(ax = 0f, ay = 1f, az = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue(transport.sent.any { it.third.contains(";glasses;") })
        assertTrue(transport.sent.any { it.third.contains(";phone;") })
        assertTrue(transport.sent.none { it.third.contains(";watch;") })
        assertTrue(transport.sent.none { it.third.contains(";wristband;") })
    }

    @Test
    fun `transport throwing on one tick does not stop subsequent ticks`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(scope, { config }, glassesStream, watchStream, phoneStream, wristbandStream, transport)
        sink.start()
        testScheduler.runCurrent()

        glassesStream.emit(GlassesFusedImuSample(ax = 1f, ay = 0f, az = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        transport.failNext = true
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()
        val afterFirstTick = transport.sent.size

        glassesStream.emit(GlassesFusedImuSample(ax = 1f, ay = 0f, az = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        assertTrue("sink should keep ticking after a send failure", transport.sent.size > afterFirstTick)
    }

    @Test
    fun `phone sample is sent using the already-fused quaternion, with zero gravity and gyro fields`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(
            scope = scope,
            config = { config },
            glassesImuStream = glassesStream,
            watchImuStream = watchStream,
            phoneImuStream = phoneStream,
            wristbandImuStream = wristbandStream,
            transport = transport,
        )
        sink.start()
        testScheduler.runCurrent()

        phoneStream.emit(PhoneFusedImuSample(ax = 1f, ay = 2f, az = 3f, qx = 0.1f, qy = 0.2f, qz = 0.3f, qw = 0.9f))
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop()
        scope.cancel()

        val (_, _, message) = transport.sent.first { it.third.contains(";phone;") }
        val sample = message.substringAfter("motion:").trim()
        val fields = sample.split(" ")
        // timestamp userAcc(3) grav(3) gyro(3) quat(4) = 14 fields total.
        assertEquals(14, fields.size)
        assertEquals(1f, fields[1].toFloat(), 0.0001f) // userAcc.x -- straight from the fused sample, no gravity subtraction
        assertEquals(2f, fields[2].toFloat(), 0.0001f) // userAcc.y
        assertEquals(3f, fields[3].toFloat(), 0.0001f) // userAcc.z
        assertEquals(0f, fields[4].toFloat(), 0.0001f) // grav.x -- zero-filled, IMUCoCo never reads this
        assertEquals(0f, fields[5].toFloat(), 0.0001f)
        assertEquals(0f, fields[6].toFloat(), 0.0001f)
        assertEquals(0f, fields[7].toFloat(), 0.0001f) // gyro.x -- zero-filled, IMUCoCo never reads this
        assertEquals(0f, fields[8].toFloat(), 0.0001f)
        assertEquals(0f, fields[9].toFloat(), 0.0001f)
        assertEquals(0.1f, fields[10].toFloat(), 0.0001f) // quat.x -- straight from the fused sample, no Madgwick
        assertEquals(0.2f, fields[11].toFloat(), 0.0001f) // quat.y
        assertEquals(0.3f, fields[12].toFloat(), 0.0001f) // quat.z
        assertEquals(0.9f, fields[13].toFloat(), 0.0001f) // quat.w
    }

    @Test
    fun `wristband sample is sent using the already-fused quaternion, with zero gravity and gyro fields`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val glassesStream = MutableSharedFlow<GlassesFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val watchStream = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 16)
        val phoneStream = MutableSharedFlow<PhoneFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val wristbandStream = MutableSharedFlow<WristbandFusedImuSample>(replay = 0, extraBufferCapacity = 16)
        val transport = FakeTransport()
        val config = ImuCoCoSinkConfig(enabled = true, host = "127.0.0.1", port = 8001, userId = "u1")

        val sink = ImuUdpTriStreamSink(
            scope = scope,
            config = { config },
            glassesImuStream = glassesStream,
            watchImuStream = watchStream,
            phoneImuStream = phoneStream,
            wristbandImuStream = wristbandStream,
            transport = transport,
        )
        sink.start()
        testScheduler.runCurrent()

        wristbandStream.emit(
            WristbandFusedImuSample(
                ax = 1, ay = 2, az = 3,
                qx = 0.1f, qy = 0.2f, qz = 0.3f, qw = 0.9f,
            ),
        )
        testScheduler.advanceTimeBy(25)
        testScheduler.runCurrent()

        sink.stop(); scope.cancel()

        val (_, _, message) = transport.sent.first { it.third.contains(";wristband;") }
        val fields = message.substringAfter("motion:").trim().split(" ")
        assertEquals(14, fields.size)
        assertEquals(1f, fields[1].toFloat(), 0.0001f)
        assertEquals(2f, fields[2].toFloat(), 0.0001f)
        assertEquals(3f, fields[3].toFloat(), 0.0001f)
        assertEquals(0f, fields[4].toFloat(), 0.0001f); assertEquals(0f, fields[5].toFloat(), 0.0001f); assertEquals(0f, fields[6].toFloat(), 0.0001f)
        assertEquals(0f, fields[7].toFloat(), 0.0001f); assertEquals(0f, fields[8].toFloat(), 0.0001f); assertEquals(0f, fields[9].toFloat(), 0.0001f)
        assertEquals(0.1f, fields[10].toFloat(), 0.0001f)
        assertEquals(0.2f, fields[11].toFloat(), 0.0001f)
        assertEquals(0.3f, fields[12].toFloat(), 0.0001f)
        assertEquals(0.9f, fields[13].toFloat(), 0.0001f)
    }
}
