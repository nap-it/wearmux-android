package com.example.peciwearables.integration

import android.util.Log
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.protocol.ImuSample
import com.example.peciwearables.integration.safety.CloudSafetyDecision
import com.example.peciwearables.integration.safety.CrossingValidationDecision
import com.example.peciwearables.integration.safety.PedestrianSafetyDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "WearableService"

internal fun WearableService.handleGlassesPhoto(jpegBytes: ByteArray, orientation: Int) {
    notePhotoPayloadArrival()
    latestCameraJpeg = jpegBytes
    serviceScope.launch(Dispatchers.Default) {
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) }
            .onFailure { withContext(Dispatchers.Main) { WearableService.appendLog("Foto: erro a descodificar: ${it.message}") } }
            .getOrNull() ?: return@launch
        withContext(Dispatchers.Main) {
            photoCaptureInFlight = false; photoCaptureTimeoutJob?.cancel()
            publishCapturedPhoto(bitmap); updateWristbandTrafficProfile("foto recebida")
        }
    }
}

internal fun WearableService.handleStreamingJpeg(jpegBytes: ByteArray) {
    latestCameraJpeg = jpegBytes
    serviceScope.launch(Dispatchers.Default) {
        try {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return@launch
            WearableService._latestCameraBitmap.value = bitmap
            WearableService._isStreaming.value = true
            com.example.peciwearables.integration.image.camera.recordStreamFrameArrival(
                streamFpsTimestamps, WearableService._streamFps, WearableService._streamFrameCount, WearableService._lastFrameTimestampMs,
            )
            publishCapturedPhoto(bitmap)
        } catch (e: Exception) { Log.e(TAG, "Error decoding streaming JPEG: ${e.message}") }
    }
}

internal fun WearableService.handleGlassesCameraData(subType: Int, payload: ByteArray) {
    notePhotoPayloadArrival(); glassesCameraManager.onCameraData(subType, payload)
}

internal fun WearableService.handleGlassesCameraImageFile(blob: ByteArray) {
    notePhotoPayloadArrival(); glassesCameraManager.onCameraImageFileReceived(blob)
}

internal fun WearableService.handleGlassesImuPacket(data: ByteArray) {
    val sample = GlassesImuSample.parse(data) ?: return
    WearableService._latestGlassesImu.value = sample
    WearableService._glassesImuStream.tryEmit(sample)
    coAxialImuFusion.feedGlasses(sample)
    if (isPdrReady()) pdr.onGlassesImu(sample)
    glassesGyroIntegrators.onSample(sample.gx, sample.gy, sample.gz, System.currentTimeMillis())
    Log.v("OmiImu", "gx=%.1f gy=%.1f gz=%.1f".format(sample.gx, sample.gy, sample.gz))
}

internal fun WearableService.handleGlassesQuaternion(quat: FloatArray) = glassesQuaternionTracker.onSample(quat)

internal fun WearableService.handleGlassesAudioPacket(opusData: ByteArray) {
    glassesMicFrameCount++
    WearableService._glassesMicStreaming.value = true
    WearableService._glassesMicStatusText.value = "STREAMING"
    noteMicrophonePacketArrival()
    val now = System.currentTimeMillis()
    if (now - glassesMicLastUiUpdateMs >= 250L) {
        WearableService._glassesMicDataText.value = "frames=$glassesMicFrameCount, ultimo pacote=${opusData.size}B"
        glassesMicLastUiUpdateMs = now
    }
    if (glassesMicFrameCount <= 3L || glassesMicFrameCount % 60 == 0L) {
        Log.d(TAG, "DBG audio pkt #$glassesMicFrameCount size=${opusData.size}B codecKnown=${glassesMicrophoneManager.isCodecKnown} codec=${glassesMicrophoneManager.currentCodecId} recording=${WearableService._audioRecordingActive.value} ${audioPipeline.getStats()}")
    }
    val pcm = glassesMicrophoneManager.decodeToPcm(opusData)
    if (pcm.isNotEmpty()) kwsSession?.feedAudio(pcm)
    if (WearableService._audioRecordingActive.value) {
        audioRecordPcm.write(shortsToLeBytes(pcm)); audioRecordSamples += pcm.size
    } else glassesMicrophoneManager.onMicrophoneData(opusData)
    finalizeMicrophoneLatencyIfNeeded()
}

internal fun WearableService.refreshGlassesMicPlaybackSupport() {
    WearableService._glassesMicPlaybackSupported.value = glassesMicrophoneManager.canMonitorLocally()
}

internal fun WearableService.handleWristbandImuSample(sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) {
    WearableService._latestImuSamples.update { (it + sample).takeLast(40) }
    val tsMs = if (timestampRaw16 != 0) timestampEstimatedMs else System.currentTimeMillis()
    WearableService._wristbandImuStream.tryEmit(com.example.peciwearables.integration.protocol.WristbandImuSample(sample, tsMs))
    coAxialImuFusion.feedWristband(sample, tsMs)
    val tsRaw = timestampRaw16.takeUnless { it == 0 }
    runImuCloudLatencyPipeline("wristband_ble", sample, tsRaw, if (tsRaw != null) timestampEstimatedMs else null)
    when (WearableService._mlProcessingLocation.value) {
        MlProcessingLocation.APP -> onDeviceClassifier?.addSample(sample, timestampEstimatedMs)?.let {
            latestInferenceClass = it.label; latestInferenceScore = it.score
            latestInferenceTsMs = it.timestampMs; WearableService.wristbandActivity.value = it.label
        }
        MlProcessingLocation.SERVER -> {
            val classifier = serverClassifier ?: return
            val features = classifier.addSample(sample) ?: return
            if (!serverInferenceInFlight.compareAndSet(false, true)) return
            serviceScope.launch(Dispatchers.IO) {
                val result = runCatching { classifier.classify(features) }.getOrNull()
                serverInferenceInFlight.set(false)
                if (result != null && WearableService._mlProcessingLocation.value == MlProcessingLocation.SERVER) {
                    latestInferenceClass = result.label; latestInferenceScore = result.score
                    latestInferenceTsMs = timestampEstimatedMs; WearableService.wristbandActivity.value = result.label
                }
            }
        }
        else -> Unit
    }
}

internal fun WearableService.handleWristbandTfliteInference(label: String, score: Float, timestampEstimatedMs: Long, @Suppress("UNUSED_PARAMETER") values: FloatArray) {
    if (WearableService._mlProcessingLocation.value != MlProcessingLocation.WRISTBAND) return
    latestInferenceClass = label; latestInferenceScore = score
    latestInferenceTsMs = timestampEstimatedMs; WearableService.wristbandActivity.value = label
}

internal fun WearableService.mirrorDecisionForUi(decision: CloudSafetyDecision) {
    when (decision) {
        is CloudSafetyDecision.PedestrianApproaching -> {
            WearableService._pedestrianDecision.value = PedestrianSafetyDecision.Approaching(decision.zoneName, decision.distanceMeters)
            WearableService.appendSafetyLog("🚸 UC1.1 approaching · ${decision.zoneName} a ${"%.0f".format(decision.distanceMeters)} m")
        }
        is CloudSafetyDecision.PedestrianDanger -> {
            WearableService._pedestrianDecision.value = PedestrianSafetyDecision.Danger(decision.zoneName, moving = true, headRotated = false, decelerating = false)
            WearableService.appendSafetyLog("⚠️ UC1.1 danger · ${decision.zoneName} · não olhou para os lados")
        }
        is CloudSafetyDecision.PedestrianSafeToCross -> {
            WearableService._pedestrianDecision.value = PedestrianSafetyDecision.Watching("olhou para os dois lados")
            WearableService.appendSafetyLog("✅ UC1.1 safe to cross · olhou para os dois lados")
        }
        is CloudSafetyDecision.CrossingClear -> {
            WearableService._crossingDecision.value = CrossingValidationDecision.Ok(decision.zoneName)
            WearableService.appendSafetyLog("✅ UC1.3 safe to cross · sem veículo detetado")
        }
        is CloudSafetyDecision.CrossingBlocked -> {
            WearableService._crossingDecision.value = CrossingValidationDecision.Wait(decision.reason)
            WearableService.appendSafetyLog("🚗 UC1.3 blocked · ${decision.reason}")
        }
        is CloudSafetyDecision.VehicleApproaching ->
            WearableService.appendSafetyLog("🚗 veículo · ${decision.label} · ${"%.1f".format(decision.depthOrDistance)} m")
    }
}
