package com.example.peciwearables.integration.wearable

import com.example.peciwearables.integration.audio.AudioPipeline
import com.example.peciwearables.integration.managers.GlassesMicrophoneManager
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.wearable.devices.brilliantsole.BrilliantSoleWristbandWearableSession
import com.example.peciwearables.integration.wearable.devices.brilliantsole.FakeBrilliantSoleWristbandBleClient
import com.example.peciwearables.integration.wearable.devices.omi.FakeOmiGlassesBleClient
import com.example.peciwearables.integration.wearable.devices.omi.OmiGlassesWearableSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeHotPathLatencyTest {

    @Test
    fun measuresHotPathLatencyWithFakes() = runTest {
        val iterations = 600

        val baselineOmiSink = BaselineOmiLatencySink()
        val baselineImuSink = BaselineImuLatencySink()
        repeat(iterations) {
            val cameraPayload = ByteArray(256) { (it + 1).toByte() }
            val audioPayload = ByteArray(160) { (it and 0x7F).toByte() }
            val imuSample = ImuSample(
                ax = 123,
                ay = 456,
                az = 789,
                gx = 1,
                gy = 2,
                gz = 3,
                mx = 4,
                my = 5,
                mz = 6,
                pressure = shortArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
            )

            baselineOmiSink.markCameraStart()
            baselineOmiSink.onCamera(cameraPayload)

            baselineOmiSink.markAudioStart()
            baselineOmiSink.onAudio(audioPayload)

            baselineImuSink.markImuStart()
            baselineImuSink.onImu(imuSample)
        }

        val glassesSink = OmiLatencySink()
        val wristbandSink = SoleLatencySink()
        val (glassesSession, glassesClient) = makeOmiSession(this, glassesSink)
        val (wristbandSession, wristbandClient) = makeSoleSession(this, wristbandSink)
        repeat(iterations) {
            val cameraPayload = ByteArray(256) { (it + 1).toByte() }
            val audioPayload = ByteArray(160) { (it and 0x7F).toByte() }

            glassesSink.markCameraStart()
            glassesClient.simulateCameraData(subType = 3, bytes = cameraPayload)

            glassesSink.markAudioStart()
            glassesClient.simulateAudioPacket(audioPayload)

            wristbandSink.markImuStart()
            wristbandClient.simulateImuSample(ax = 123, ay = 456, az = 789, timestampMs = 42_000L + it)
            advanceUntilIdle()
        }
        glassesSession.close()
        wristbandSession.close()

        assertEquals(iterations, baselineOmiSink.cameraLatenciesNs.size)
        assertEquals(iterations, baselineOmiSink.audioLatenciesNs.size)
        assertEquals(iterations, baselineImuSink.imuLatenciesNs.size)
        assertTrue(glassesSink.cameraLatenciesNs.isNotEmpty())
        assertTrue(glassesSink.cameraLatenciesNs.size <= iterations)
        assertEquals(iterations, glassesSink.audioLatenciesNs.size)
        assertTrue(wristbandSink.imuLatenciesNs.isNotEmpty())
        assertTrue(wristbandSink.imuLatenciesNs.size <= iterations)

        val baselineCamera = summarizeNs(baselineOmiSink.cameraLatenciesNs)
        val baselineAudio = summarizeNs(baselineOmiSink.audioLatenciesNs)
        val baselineImu = summarizeNs(baselineImuSink.imuLatenciesNs)
        val camera = summarizeNs(glassesSink.cameraLatenciesNs)
        val audio = summarizeNs(glassesSink.audioLatenciesNs)
        val imu = summarizeNs(wristbandSink.imuLatenciesNs)

        println("METRIC optimized_camera_delivered=${glassesSink.cameraLatenciesNs.size} of $iterations")
        println("METRIC optimized_imu_delivered=${wristbandSink.imuLatenciesNs.size} of $iterations")
        println("METRIC baseline_camera_payload_to_frame_ms p50=${"%.4f".format(baselineCamera.p50Ms)} p95=${"%.4f".format(baselineCamera.p95Ms)}")
        println("METRIC baseline_audio_payload_to_pcm_ms p50=${"%.4f".format(baselineAudio.p50Ms)} p95=${"%.4f".format(baselineAudio.p95Ms)}")
        println("METRIC baseline_imu_payload_to_ml_pdr_ms p50=${"%.4f".format(baselineImu.p50Ms)} p95=${"%.4f".format(baselineImu.p95Ms)}")
        println("METRIC camera_payload_to_frame_ms p50=${"%.4f".format(camera.p50Ms)} p95=${"%.4f".format(camera.p95Ms)}")
        println("METRIC audio_payload_to_pcm_ms p50=${"%.4f".format(audio.p50Ms)} p95=${"%.4f".format(audio.p95Ms)}")
        println("METRIC imu_payload_to_ml_pdr_ms p50=${"%.4f".format(imu.p50Ms)} p95=${"%.4f".format(imu.p95Ms)}")
    }

    private fun makeOmiSession(
        scope: CoroutineScope,
        sink: WearableRealtimeSink,
    ): Pair<OmiGlassesWearableSession, FakeOmiGlassesBleClient> {
        val client = FakeOmiGlassesBleClient()
        val session = OmiGlassesWearableSession(
            id = WearableId("AA:11:22:33:44:55"),
            profile = deviceProfile(scan = scanCandidate(id = "AA:11:22:33:44:55", name = "Omi")),
            client = client,
            scope = scope,
            realtimeSink = sink,
        )
        return session to client
    }

    private fun makeSoleSession(
        scope: CoroutineScope,
        sink: WearableRealtimeSink,
    ): Pair<BrilliantSoleWristbandWearableSession, FakeBrilliantSoleWristbandBleClient> {
        val client = FakeBrilliantSoleWristbandBleClient()
        val session = BrilliantSoleWristbandWearableSession(
            id = WearableId("BB:11:22:33:44:55"),
            profile = deviceProfile(scan = scanCandidate(id = "BB:11:22:33:44:55", name = "BrilliantSole")),
            client = client,
            scope = scope,
            realtimeSink = sink,
        )
        return session to client
    }

    private data class LatencySummary(val p50Ms: Double, val p95Ms: Double)

    private fun summarizeNs(values: List<Long>): LatencySummary {
        val sorted = values.sorted()
        fun percentile(p: Double): Double {
            val rank = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[rank] / 1_000_000.0
        }
        return LatencySummary(
            p50Ms = percentile(0.50),
            p95Ms = percentile(0.95),
        )
    }

    private class OmiLatencySink : WearableRealtimeSink {
        private val micManager = GlassesMicrophoneManager(AudioPipeline())
        private var cameraStartNs: Long = 0L
        private var audioStartNs: Long = 0L
        val cameraLatenciesNs = mutableListOf<Long>()
        val audioLatenciesNs = mutableListOf<Long>()

        fun markCameraStart() {
            cameraStartNs = System.nanoTime()
        }

        fun markAudioStart() {
            audioStartNs = System.nanoTime()
        }

        override fun onCameraChunk(id: WearableId, subType: Int, bytes: ByteArray) {
            cameraLatenciesNs += (System.nanoTime() - cameraStartNs)
        }

        override fun onAudioPacket(id: WearableId, bytes: ByteArray, codecId: Int?) {
            micManager.decodeToPcm(bytes)
            audioLatenciesNs += (System.nanoTime() - audioStartNs)
        }
    }

    private class BaselineOmiLatencySink {
        private val micManager = GlassesMicrophoneManager(AudioPipeline())
        private var cameraStartNs: Long = 0L
        private var audioStartNs: Long = 0L
        val cameraLatenciesNs = mutableListOf<Long>()
        val audioLatenciesNs = mutableListOf<Long>()

        fun markCameraStart() {
            cameraStartNs = System.nanoTime()
        }

        fun markAudioStart() {
            audioStartNs = System.nanoTime()
        }

        fun onCamera(bytes: ByteArray) {
            bytes.copyOfRange(0, bytes.size)
            cameraLatenciesNs += (System.nanoTime() - cameraStartNs)
        }

        fun onAudio(bytes: ByteArray) {
            micManager.decodeToPcm(bytes)
            micManager.decodeToPcm(bytes)
            audioLatenciesNs += (System.nanoTime() - audioStartNs)
        }
    }

    private class SoleLatencySink : WearableRealtimeSink {
        private var imuStartNs: Long = 0L
        val imuLatenciesNs = mutableListOf<Long>()

        fun markImuStart() {
            imuStartNs = System.nanoTime()
        }

        override fun onImuSample(id: WearableId, sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) {
            // Simula ponto de entrega ao pipeline ML/PDR.
            sample.ax
            imuLatenciesNs += (System.nanoTime() - imuStartNs)
        }
    }

    private class BaselineImuLatencySink {
        private var imuStartNs: Long = 0L
        private var recentSamples: List<ImuSample> = emptyList()
        val imuLatenciesNs = mutableListOf<Long>()

        fun markImuStart() {
            imuStartNs = System.nanoTime()
        }

        fun onImu(sample: ImuSample) {
            recentSamples = (recentSamples + sample).takeLast(40)
            imuLatenciesNs += (System.nanoTime() - imuStartNs)
        }
    }
}
