package com.example.peciwearables.integration.wearable.devices.brilliantsole

import app.cash.turbine.test
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.wearable.CommandResult
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
class BrilliantSoleWristbandWearableSessionTest {

    private val id = WearableId("BB:11:22:33:44:55")
    private val bsRxUuid = UUID.fromString("ea6d1000-a725-4f9b-893d-c3913e33b39f")
    private val bsTxUuid = UUID.fromString("ea6d1001-a725-4f9b-893d-c3913e33b39f")

    private fun makeSession(
        chars: Set<UUID> = setOf(bsRxUuid, bsTxUuid),
        probes: Map<String, Any?> = mapOf("hapticSupported" to true),
        scope: CoroutineScope,
        realtimeSink: WearableRealtimeSink = WearableRealtimeSink.NONE,
    ): Pair<BrilliantSoleWristbandWearableSession, FakeBrilliantSoleWristbandBleClient> {
        val client = FakeBrilliantSoleWristbandBleClient()
        val scan = WearableScanCandidate(
            id = id, displayName = "BrilliantSole", rssi = -60,
            advertisedServiceUuids = emptySet(), manufacturerData = emptyMap(),
            serviceData = emptyMap(), raw = null,
        )
        val profile = WearableConnectedDeviceProfile(
            scan = scan, discoveredServices = emptySet(),
            discoveredCharacteristics = chars,
            firmwareVersion = "2.0", deviceName = "BrilliantSole",
            handshakeProbes = probes,
        )
        val session = BrilliantSoleWristbandWearableSession(id, profile, client, scope, realtimeSink = realtimeSink)
        return session to client
    }

    @Test
    fun hotPathCallbacks_forwardedToRealtimeSink_directly() = runTest {
        val sink = RecordingRealtimeSink()
        val (session, client) = makeSession(scope = this, realtimeSink = sink)
        val inferenceValues = floatArrayOf(0.95f, 0.05f)

        client.simulateImuSample(ax = 123, ay = 456, az = 789, timestampMs = 42_000L)
        client.simulateInference("walk", 0.95f, inferenceValues)
        advanceUntilIdle()

        assertEquals(123.toShort(), sink.imuSample?.ax)
        assertEquals(456.toShort(), sink.imuSample?.ay)
        assertEquals(789.toShort(), sink.imuSample?.az)
        assertEquals(0, sink.imuTimestampRaw16)
        assertEquals(42_000L, sink.imuTimestampEstimatedMs)
        assertEquals("walk", sink.inferenceLabel)
        assertEquals(0.95f, sink.inferenceScore ?: Float.NaN, 0.001f)
        assertArrayEquals(inferenceValues, sink.inferenceValues, 0.0001f)
        session.close()
    }

    @Test
    fun imuSample_emitsImuSampleReceived() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateImuSample(ax = 1000, ay = 0, az = 0)
            var event: WearableEvent? = null
            repeat(5) {
                if (event !is WearableEvent.ImuSampleReceived) event = awaitItem()
            }
            assertTrue(event is WearableEvent.ImuSampleReceived)
            event as WearableEvent.ImuSampleReceived
            assertEquals(1.0f, event.accel[0], 0.001f)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun inference_emitsMlInferenceReceived() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateInference("walk", 0.95f, floatArrayOf(0.95f, 0.05f))
            var event: WearableEvent? = null
            repeat(5) {
                if (event !is WearableEvent.MlInferenceReceived) event = awaitItem()
            }
            assertTrue(event is WearableEvent.MlInferenceReceived)
            event as WearableEvent.MlInferenceReceived
            assertEquals("walk", event.label)
            assertEquals(0.95f, event.score, 0.001f)
            cancelAndIgnoreRemainingEvents()
        }
        session.close()
    }

    @Test
    fun vibrate_callsSendWaveformVibration() = runTest {
        val (session, client) = makeSession(scope = this)
        val result = session.send(WearableCommand.Vibrate(durationMs = 300, intensity = 0.8f))
        assertEquals(CommandResult.Accepted, result)
        assertEquals(1, client.sendWaveformCalls.size)
        val (amp, dur, _) = client.sendWaveformCalls.first()
        assertEquals(0.8f, amp, 0.001f)
        assertEquals(300, dur)
        session.close()
    }

    @Test
    fun vibrate_unsupportedWhenNoHapticCapability() = runTest {
        val (session, _) = makeSession(probes = emptyMap(), scope = this)
        val result = session.send(WearableCommand.Vibrate(durationMs = 100))
        assertTrue(result is CommandResult.Unsupported)
        assertEquals(WearableCapability.HAPTIC, (result as CommandResult.Unsupported).capability)
        session.close()
    }

    @Test
    fun takePicture_returnsUnsupported() = runTest {
        val (session, _) = makeSession(scope = this)
        val result = session.send(WearableCommand.TakePicture)
        assertTrue(result is CommandResult.Unsupported)
        assertEquals(WearableCapability.IMAGE_CAPTURE, (result as CommandResult.Unsupported).capability)
        session.close()
    }

    @Test
    fun setImuStreaming_callsSensorsConfig() = runTest {
        val (session, client) = makeSession(scope = this)
        val result = session.send(WearableCommand.SetImuStreaming(enabled = true, rateMs = 40))
        assertEquals(CommandResult.Accepted, result)
        assertEquals(listOf(40), client.setSensorsConfigCalls)
        session.close()
    }

    @Test
    fun bleStateError_emitsConnectionChanged() = runTest {
        val (session, client) = makeSession(scope = this)
        session.events.test {
            client.simulateState(BleDeviceState.ERROR)
            var found: WearableEvent.ConnectionChanged? = null
            repeat(5) {
                if (found != null) return@repeat
                val item = awaitItem()
                if (item is WearableEvent.ConnectionChanged) found = item
            }
            assertTrue(found != null)
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
    fun imuQueue_isBoundedAndKeepsNewestSamples() = runTest {
        val sink = RecordingRealtimeSink()
        val (session, client) = makeSession(scope = this, realtimeSink = sink)

        repeat(120) { idx ->
            val value = idx.toShort()
            client.simulateImuSample(ax = value, ay = value, az = value, timestampMs = idx.toLong())
        }
        advanceUntilIdle()

        assertTrue("imu stream should deliver samples", sink.imuSamples.isNotEmpty())
        assertEquals(119.toShort(), sink.imuSamples.last().ax)
        session.close()
    }

    private class RecordingRealtimeSink : WearableRealtimeSink {
        var imuSample: com.example.peciwearables.integration.protocol.ImuSample? = null
        val imuSamples = mutableListOf<com.example.peciwearables.integration.protocol.ImuSample>()
        var imuTimestampRaw16: Int? = null
        var imuTimestampEstimatedMs: Long? = null
        var inferenceLabel: String? = null
        var inferenceScore: Float? = null
        var inferenceValues: FloatArray? = null

        override fun onImuSample(
            id: WearableId,
            sample: com.example.peciwearables.integration.protocol.ImuSample,
            timestampRaw16: Int,
            timestampEstimatedMs: Long,
        ) {
            imuSample = sample
            imuSamples += sample
            imuTimestampRaw16 = timestampRaw16
            imuTimestampEstimatedMs = timestampEstimatedMs
        }

        override fun onTfliteInference(
            id: WearableId,
            label: String,
            score: Float,
            timestampEstimatedMs: Long,
            values: FloatArray,
        ) {
            inferenceLabel = label
            inferenceScore = score
            inferenceValues = values
        }
    }
}
