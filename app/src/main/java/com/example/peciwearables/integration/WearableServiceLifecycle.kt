package com.example.peciwearables.integration

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.protocol.ImuSample
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "WearableService"

internal fun WearableService.onGlassesDeviceDisconnected() {
    photoCaptureTimeoutJob?.cancel(); photoCaptureTimeoutJob = null
    photoCaptureInFlight = false
    wifiUdpRecovery.cancelWatchdog()
    glassesPoseReporter.stop()
    glassesQuaternionTracker.reset()
    if (WearableService._audioRecordingActive.value) {
        audioRecordPcm.reset(); audioRecordSamples = 0L; WearableService._audioRecordingActive.value = false
    }
    audioPipeline.clearQueue()
    disconnectKwsInternal()
    WearableService._glassesMicStreaming.value = false
    WearableService._glassesMicStatusText.value = "IDLE"
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.IDLE
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.IDLE
    streamFpsTimestamps.clear()
    WearableService._lastFrameTimestampMs.value = null
}

internal fun WearableService.stopBackgroundJobs() {
    runCatching { telemetryReporter.stop() }
    glassesPoseReporter.stop(); clearGlassesUdpRouting()
    wifiCredentialsSent = false
    photoCaptureTimeoutJob?.cancel(); photoCaptureInFlight = false
    wristbandSensorRateAppliedMs = -1
    wifiUdpRecovery.cancelWatchdog()
    scanAndConnect.cancelAutoConnect(); scanAndConnect.stopDiscovery()
}

internal fun WearableService.disconnectAllWearables() {
    udpServer.stop(); capabilityScanner.stopScan()
    wifiUdpRecovery.disconnectUdpSession()
    glassesBleClient.disconnect(); wristbandBleClient.disconnect()
    nsdRegistration.unregister(); udpWifiLock.release()
}

internal fun WearableService.cleanupRuntimeResources() {
    runCatching { phoneSensors.stopAll() }
    runCatching { pdr.stop() }
    runCatching { audioPipeline.stop() }
    runCatching { glassesInferenceManager.close() }
    runCatching { bleCameraPipeline.close() }
    runCatching { disconnectKwsInternal() }
    runCatching { watchClient.stop() }
    runCatching { safetyOrchestrator.cancel() }
    runCatching { safetyDiagnostics.stop() }
    runCatching { crossingZoneManager.cancel() }
    runCatching { atcllClient.stop() }
    runCatching { ttsEngine.shutdown() }
    runCatching { AudioTestEngine.stop() }
    runCatching { latencyCsvWriter.close() }
    WearableService._phoneSensorsActive.value = false
}

internal fun WearableService.resetCompanionState() {
    WearableService._udpServerActive.value = false
    WearableService._latestCameraBitmap.value = null
    WearableService._latestBitmap.value = null
    WearableService._allPhotos.value = emptyList()
    WearableService._isStreaming.value = false
    WearableService._streamFps.value = 0f
    WearableService._streamFrameCount.value = 0
    streamFpsTimestamps.clear()
    WearableService._lastFrameTimestampMs.value = null
    WearableService._glassesMicStreaming.value = false
    WearableService._glassesMicStatusText.value = "IDLE"
    WearableService._glassesMicState.value = WearableService.Companion.GlassesMicStreamState.IDLE
    WearableService._glassesCameraState.value = WearableService.Companion.GlassesCameraStreamState.IDLE
    WearableService._glassesMicDataText.value = "Sem dados"
    WearableService._glassesMicSampleRateHz.value = 16_000
    WearableService._glassesMicBitDepth.value = 8
    WearableService._glassesMicPlaybackSupported.value = true
    WearableService._audioRecordingActive.value = false
    glassesMicFrameCount = 0L; glassesMicLastUiUpdateMs = 0L
    cloudLatencyReporter = null
    onDeviceClassifier?.close(); onDeviceClassifier = null; serverClassifier = null
    audioRecordPcm = ByteArrayOutputStream(); audioRecordSamples = 0L; audioRecordSampleRate = 16_000
}

internal fun WearableService.publishCapturedPhoto(bitmap: Bitmap) {
    finalizePhotoLatencyMeasurement()
    WearableService._latestCameraBitmap.value = bitmap
    WearableService._latestPhoto.value = bitmap
    if (WearableService._isStreaming.value) {
        WearableService._streamFrameCount.value++
        val now = System.currentTimeMillis()
        streamFpsTimestamps.add(now)
        streamFpsTimestamps.removeAll { it < now - 2000L }
        WearableService._streamFps.value = if (streamFpsTimestamps.size > 1) streamFpsTimestamps.size / 2f else 0f
        return
    }
    val count = WearableService._photoCount.value + 1
    WearableService._photoCount.value = count
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    WearableService._allPhotos.value = WearableService._allPhotos.value + CapturedPhoto(bitmap, ts, count)
    WearableService.appendLog("Foto recebida: ${bitmap.width}x${bitmap.height}px (foto #$count)")
}

internal fun WearableService.currentRouteLabel(): String =
    if (WearableService._udpActive.value || glassesConnectedViaUdp) "Wi-Fi" else "BLE"

internal fun WearableService.benchLog(event: String) { Log.d(TAG, "BENCH|$event") }

internal fun WearableService.requestImuMlBenchmarkSample() = imuMlBenchmarkRunner.request()

internal fun WearableService.runImuCloudLatencyPipeline(
    source: String, sample: ImuSample,
    wristbandTimestampRaw16: Int? = null, wristbandTimestampEstimatedMs: Long? = null,
) = imuMlBenchmarkRunner.process(source, sample, wristbandTimestampRaw16, wristbandTimestampEstimatedMs)

internal fun WearableService.currentTransportEstimateMs(): Long =
    if (WearableService._udpActive.value || glassesConnectedViaUdp) {
        val rtt = wifiUdpRecovery.activeUdpManager?.averageRttMs?.coerceAtLeast(1L) ?: 40L
        (rtt / 2L).coerceAtLeast(1L)
    } else (lastBleTransportMs * 2).coerceAtLeast(10L)

internal fun WearableService.startPhotoLatencyMeasurement() = photoLatencyTracker.start()
internal fun WearableService.notePhotoPayloadArrival() = photoLatencyTracker.notePayload()
internal fun WearableService.finalizePhotoLatencyMeasurement() = photoLatencyTracker.finalize()
internal fun WearableService.startMicrophoneLatencyMeasurement() = microphoneLatencyTracker.start()
internal fun WearableService.noteMicrophonePacketArrival() = microphoneLatencyTracker.notePacket()
internal fun WearableService.finalizeMicrophoneLatencyIfNeeded() = microphoneLatencyTracker.finalizeIfNeeded()

internal fun WearableService.shortsToLeBytes(samples: ShortArray): ByteArray =
    com.example.peciwearables.integration.audio.WavWriter.shortsToLeBytes(samples)

internal fun WearableService.startAndWireUdpServer() = com.example.peciwearables.integration.udp.UdpServerWiring(
    udpServer, audioPipeline, imagePipeline, timeSyncManager, glassesMicrophoneManager,
    WearableService._latestImuSamples, { runImuCloudLatencyPipeline("wristband_udp", it) },
    { WearableService._udpServerActive.value = it },
).start(serviceScope)

internal fun WearableService.persistAudioRecording(): RecordedAudio? {
    val pcmBytes = audioRecordPcm.toByteArray()
    if (pcmBytes.isEmpty() || audioRecordSamples <= 0L) return null
    val sampleRate = if (audioRecordSampleRate > 0) audioRecordSampleRate else 16_000
    val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
    val file = File(recordingsDir, "glasses_mic_${System.currentTimeMillis()}.wav")
    val ok = runCatching { com.example.peciwearables.integration.audio.WavWriter.write(file, pcmBytes, sampleRate) }
        .onFailure { WearableService.appendLog("⚠  Erro a guardar WAV: ${it.message}") }
        .isSuccess
    if (!ok) return null
    val durationSec = audioRecordSamples.toDouble() / sampleRate.toDouble()
    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    audioRecordPcm = ByteArrayOutputStream(); audioRecordSamples = 0L
    return RecordedAudio(file.absolutePath, ts, WearableService._recordedAudios.value.size + 1, durationSec, sampleRate)
}
