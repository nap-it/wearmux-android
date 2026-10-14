package com.wearmux.android.integration.adapters

import com.wearmux.android.integration.protocol.GlassesFusedImuSample
import com.wearmux.android.integration.protocol.ImuSample
import com.wearmux.android.integration.protocol.WristbandFusedImuSample

/**
 * Hot path para dados críticos com default no-op.
 * Mantém eventos semânticos fora deste contrato.
 */
interface WearableRealtimeSink {
    fun onCameraChunk(id: WearableId, subType: Int, bytes: ByteArray) = Unit
    fun onStreamingJpeg(id: WearableId, jpegBytes: ByteArray) = Unit
    fun onCameraImageFile(id: WearableId, bytes: ByteArray) = Unit
    fun onPhotoJpeg(id: WearableId, jpegBytes: ByteArray, orientationDeg: Int) = Unit
    fun onAudioPacket(id: WearableId, bytes: ByteArray, codecId: Int?) = Unit
    fun onImuPacket(id: WearableId, bytes: ByteArray) = Unit
    fun onImuSample(id: WearableId, sample: ImuSample, timestampRaw16: Int, timestampEstimatedMs: Long) = Unit
    fun onQuaternion(id: WearableId, quaternion: FloatArray) = Unit
    fun onGlassesFusedSample(id: WearableId, sample: GlassesFusedImuSample) = Unit
    fun onWristbandFusedSample(id: WearableId, sample: WristbandFusedImuSample) = Unit
    fun onTfliteInference(
        id: WearableId,
        label: String,
        score: Float,
        timestampEstimatedMs: Long,
        values: FloatArray,
    ) = Unit

    companion object {
        val NONE: WearableRealtimeSink = object : WearableRealtimeSink {}
    }
}
