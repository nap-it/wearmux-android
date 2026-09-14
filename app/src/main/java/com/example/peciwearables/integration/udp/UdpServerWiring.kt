package com.example.peciwearables.integration.udp

import android.util.Log
import com.example.peciwearables.integration.audio.AudioPipeline
import com.example.peciwearables.integration.image.ImagePipeline
import com.example.peciwearables.integration.managers.GlassesMicrophoneManager
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.sync.TimeSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Liga os 4 callbacks do [UdpServer] aos pipelines de áudio/imagem/IMU + arranque.
 * Encapsula ~25 linhas que viviam em `WearableService.setupUdpCallbacks`/`startUdpServer`.
 */
class UdpServerWiring(
    private val udpServer: UdpServer,
    private val audioPipeline: AudioPipeline,
    private val imagePipeline: ImagePipeline,
    private val timeSyncManager: TimeSyncManager,
    private val glassesMicrophoneManager: GlassesMicrophoneManager,
    private val latestImuSamples: MutableStateFlow<List<ImuSample>>,
    private val onLatestImuSample: (ImuSample) -> Unit,
    private val onActive: (Boolean) -> Unit,
) {
    fun start(scope: CoroutineScope) {
        udpServer.onAudioFrame = { header, frame ->
            timeSyncManager.addSample(header.deviceId.toString(), header.timestampUs)
            val pcm = glassesMicrophoneManager.decodeToPcm(frame.audioBytes)
            if (pcm.isNotEmpty()) audioPipeline.enqueueChunk(pcm, frame.sampleRateHz)
        }
        udpServer.onImageChunk = { header, chunk ->
            timeSyncManager.addSample(header.deviceId.toString(), header.timestampUs); imagePipeline.onImageChunk(header, chunk)
        }
        udpServer.onImuPayload = { header, payload ->
            timeSyncManager.addSample(header.deviceId.toString(), header.timestampUs)
            latestImuSamples.update { (it + payload.samples).takeLast(40) }
            payload.samples.lastOrNull()?.let(onLatestImuSample)
        }
        udpServer.onControlMessage = { h, p -> Log.d(TAG, "Control message from ${h.deviceId}: ${p.size} bytes") }
        udpServer.start(scope); audioPipeline.start(scope); onActive(true)
    }
    private companion object { const val TAG = "UdpServerWiring" }
}
