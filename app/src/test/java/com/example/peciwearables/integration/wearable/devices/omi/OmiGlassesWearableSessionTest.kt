package com.example.peciwearables.integration.wearable.devices.omi

import app.cash.turbine.test
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.wearable.AudioCodec
import com.example.peciwearables.integration.wearable.CommandResult
import com.example.peciwearables.integration.wearable.ImageFormat
import com.example.peciwearables.integration.wearable.WearableCapability
import com.example.peciwearables.integration.wearable.WearableCommand
import com.example.peciwearables.integration.wearable.WearableConnectedDeviceProfile
import com.example.peciwearables.integration.wearable.WearableConnectionState
import com.example.peciwearables.integration.wearable.WearableEvent
import com.example.peciwearables.integration.wearable.WearableId
import com.example.peciwearables.integration.wearable.WearableRealtimeSink
import com.example.peciwearables.integration.wearable.WearableScanCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class OmiGlassesWearableSessionTest {

    private val id = WearableId("AA:11:22:33:44:55")
    private val photoControlUuid = UUID.fromString("19B10006-E8F2-537E-4F6C-D104768A1214")
    private val audioDataUuid = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
    private val imuDataUuid = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
    private val batteryUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val firmwareUuid = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    private fun makeSession(
        chars: Set<UUID> = setOf(photoControlUuid, audioDataUuid, imuDataUuid, batteryUuid),
        probes: Map<String, Any?> = emptyMap(),
        scope: CoroutineScope,
        realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
    ): Pair<OmiGlassesWearableSession, FakeOmiGlassesBleClient> {
        val client = FakeOmiGlassesBleClient()
        val scan = WearableScanCandidate(
            id = id, displayName = "Omi", rssi = -50,
            advertisedServiceUuids = emptySet(), manufacturerData = emptyMap(),
            serviceData = emptyMap(), raw = null,
        )
        val profile = WearableConnectedDeviceProfile(
            scan = scan,
            discoveredServices = emptySet(),
            discoveredCharacteristics = chars,
            firmwareVersion = "1.0",
            deviceName = "Omi",
            handshakeProbes = probes,
        )
        val session = OmiGlassesWearableSession(id, profile, client, scope, realtimeSink = realtimeSink)
        return session to client
    }

    @Test
    fun hotPathCallbacks_forwardedToRealtimeSink_directly() = runTest {
        val sink = RecordingRealtimeSink()
        val (session, client) = makeSession(scope = this, realtimeSink = sink)

        val photo = byteArrayOf(1, 2, 3, 4)
        val cameraChunk = byteArrayOf(9, 8, 7)
        val imageFile = byteArrayOf(5, 6, 7, 8)
        val audio = byteArrayOf(0x0A, 0x0B)
        val imu = byteArrayOf(100, 0, 200.toByte(), 0, 50, 0)
        val quaternion = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)

        client.simulatePhoto(photo, 90)
        client.simulateCameraData(2, cameraChunk)
        client.simulateCameraImageFile(imageFile)
        client.simulateAudioPacket(audio)
        client.simulateImuData(imu)
        client.simulateQuaternion(quaternion)
        advanceUntilIdle()

        assertArrayEquals(photo, sink.photo?.first)
        assertEquals(90, sink.photo?.second)
        assertEquals(2, sink.cameraChunk?.first)
        assertArrayEquals(cameraChunk, sink.cameraChunk?.second)
        assertArrayEquals(imageFile, sink.cameraImageFile)
        assertArrayEquals(audio, sink.audioPacket?.first)
        assertArrayEquals(imu, sink.imuPacket)
        assertArrayEquals(quaternion, sink.quaternion, 0.0001f)
        session.close()
    }

    @Test
    fun photo_emitsImageFrameReceived() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulatePhoto(byteArrayOf(1, 2, 3), 180)
            var event: WearableEvent? = null
            repeat(5) {
                if (event !is WearableEvent.ImageFrameReceived) event = awaitItem()
            }
            assertTrue(event is WearableEvent.ImageFrameReceived)
            event as WearableEvent.ImageFrameReceived
            assertEquals(ImageFormat.JPEG, event.format)
            assertEquals(180, event.orientationDeg)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun audio_emitsAudioFrameReceivedWithMulawCodec() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateAudioCodecId(2)     // MULAW
            client.simulateAudioPacket(byteArrayOf(0xDE.toByte(), 0xAD.toByte()))
            val items = mutableListOf<WearableEvent>()
            repeat(5) {
                if (items.none { item -> item is WearableEvent.AudioFrameReceived }) {
                    items += awaitItem()
                }
            }
            val audioEvent = items.filterIsInstance<WearableEvent.AudioFrameReceived>().first()
            assertEquals(AudioCodec.MULAW, audioEvent.codec)
            assertEquals(16_000, audioEvent.sampleRateHz)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun audio_emitsAudioFrameReceivedWithMulawCodec21Compatibility() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateAudioCodecId(21)
            client.simulateAudioPacket(byteArrayOf(0x01, 0x02, 0x03))
            val items = mutableListOf<WearableEvent>()
            repeat(5) {
                if (items.none { item -> item is WearableEvent.AudioFrameReceived }) {
                    items += awaitItem()
                }
            }
            val audioEvent = items.filterIsInstance<WearableEvent.AudioFrameReceived>().first()
            assertEquals(AudioCodec.MULAW, audioEvent.codec)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun imuData_emitsImuSampleReceived() = runTest {
        val (session, client) = makeSession(scope = this)
        // 6 bytes: 3 shorts LE
        val bytes = byteArrayOf(100, 0, 200.toByte(), 0, 50, 0)
        session.events.test {
            client.simulateImuData(bytes)
            var event: WearableEvent? = null
            repeat(5) {
                if (event !is WearableEvent.ImuSampleReceived) event = awaitItem()
            }
            assertTrue(event is WearableEvent.ImuSampleReceived)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun quaternion_emitsImuSampleReceivedWithQuaternion() = runTest {
        val (session, client) = makeSession(scope = this)
        val q = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f)
        session.events.test {
            client.simulateQuaternion(q)
            var event: WearableEvent? = null
            repeat(5) {
                if (event !is WearableEvent.ImuSampleReceived) event = awaitItem()
            }
            assertTrue(event is WearableEvent.ImuSampleReceived)
            event as WearableEvent.ImuSampleReceived
            assertTrue(event.quaternion != null)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun takePicture_callsClientAndReturnsAccepted() = runTest {
        val (session, client) = makeSession(scope = this)
        val result = session.send(WearableCommand.TakePicture)
        assertEquals(CommandResult.Accepted, result)
        assertEquals(1, client.takePictureCalls)
        session.close()
    }

    @Test
    fun vibrate_returnsUnsupportedHaptic() = runTest {
        val (session, _) = makeSession(scope = this)
        val result = session.send(WearableCommand.Vibrate(durationMs = 100))
        assertTrue(result is CommandResult.Unsupported)
        assertEquals(WearableCapability.HAPTIC, (result as CommandResult.Unsupported).capability)
        session.close()
    }

    @Test
    fun startAudioStream_callsStartMicrophone() = runTest {
        val (session, client) = makeSession(scope = this)
        val result = session.send(WearableCommand.StartAudioStream)
        assertEquals(CommandResult.Accepted, result)
        assertEquals(1, client.startMicrophoneCalls)
        session.close()
    }

    @Test
    fun setImuStreaming_callsClient() = runTest {
        val (session, client) = makeSession(scope = this)
        val result = session.send(WearableCommand.SetImuStreaming(enabled = true, rateMs = 50))
        assertEquals(CommandResult.Accepted, result)
        assertEquals(1, client.setImuCalls.size)
        assertEquals(true to 50, client.setImuCalls.first())
        session.close()
    }

    @Test
    fun bleStateError_emitsConnectionChanged() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateState(BleDeviceState.ERROR)
            // Drena até 10 eventos à procura de um ConnectionChanged
            var found: WearableEvent.ConnectionChanged? = null
            repeat(10) {
                if (found != null) return@repeat
                val item = awaitItem()
                if (item is WearableEvent.ConnectionChanged) found = item
            }
            assertTrue("Expected ConnectionChanged event", found != null)
            assertEquals(WearableConnectionState.ERROR, found!!.state)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun close_callsDisconnect() = runTest {
        val (session, client) = makeSession(scope = this)
        session.close()
        assertEquals(1, client.disconnectCalls)
    }

    @Test
    fun cameraQueue_isBoundedAndKeepsNewestChunks() = runTest {
        val sink = RecordingRealtimeSink()
        val (session, client) = makeSession(scope = this, realtimeSink = sink)

        repeat(40) { idx ->
            client.simulateCameraData(subType = 3, bytes = byteArrayOf(idx.toByte()))
        }
        advanceUntilIdle()

        assertTrue("camera stream should deliver chunks", sink.cameraChunkEvents.isNotEmpty())
        assertArrayEquals(byteArrayOf(39.toByte()), sink.cameraChunkEvents.last().second)
        session.close()
    }

    private class RecordingRealtimeSink : WearableRealtimeSink {
        var photo: Pair<ByteArray, Int>? = null
        var cameraChunk: Pair<Int, ByteArray>? = null
        val cameraChunkEvents = mutableListOf<Pair<Int, ByteArray>>()
        var cameraImageFile: ByteArray? = null
        var audioPacket: Pair<ByteArray, Int?>? = null
        var imuPacket: ByteArray? = null
        var quaternion: FloatArray? = null

        override fun onCameraChunk(id: WearableId, subType: Int, bytes: ByteArray) {
            cameraChunk = subType to bytes
            cameraChunkEvents += subType to bytes
        }

        override fun onCameraImageFile(id: WearableId, bytes: ByteArray) {
            cameraImageFile = bytes
        }

        override fun onPhotoJpeg(id: WearableId, jpegBytes: ByteArray, orientationDeg: Int) {
            photo = jpegBytes to orientationDeg
        }

        override fun onAudioPacket(id: WearableId, bytes: ByteArray, codecId: Int?) {
            audioPacket = bytes to codecId
        }

        override fun onImuPacket(id: WearableId, bytes: ByteArray) {
            imuPacket = bytes
        }

        override fun onQuaternion(id: WearableId, quaternion: FloatArray) {
            this.quaternion = quaternion
        }
    }
}
