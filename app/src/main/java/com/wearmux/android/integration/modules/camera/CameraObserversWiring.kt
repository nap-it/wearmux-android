package com.wearmux.android.integration.modules.camera

import android.graphics.Bitmap
import android.util.Log
import com.wearmux.android.integration.inference.InferenceManager
import com.wearmux.android.integration.inference.InferenceMode
import com.wearmux.android.integration.modules.camera.BleCameraPipeline
import com.wearmux.android.integration.modules.camera.ImagePipeline
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Observa os bitmaps das pipelines BLE/imagem e dispara cloud-preview inference periodicamente.
 * Encapsula ~40 linhas de `wireCameraObservers` + `startCloudCameraPreviewLoop`.
 */
class CameraObserversWiring(
    private val scope: CoroutineScope,
    private val bleCameraPipeline: BleCameraPipeline,
    private val imagePipeline: ImagePipeline,
    private val inferenceManager: InferenceManager,
    private val latestCameraBitmap: MutableStateFlow<Bitmap?>,
    private val latestBitmap: MutableStateFlow<Bitmap?>,
    private val isStreaming: StateFlow<Boolean>,
    private val streamFps: MutableStateFlow<Float>,
    private val streamFrameCount: MutableStateFlow<Int>,
    private val streamFpsTimestamps: MutableList<Long>,
    private val lastFrameTimestampMs: MutableStateFlow<Long?>,
    private val inferenceMode: StateFlow<InferenceMode>,
    private val latestCameraJpeg: () -> ByteArray?,
    /** Carimbo do Timestamper aplicado ao JPEG à saída da aquisição. */
    private val latestCameraJpegAtMs: () -> Long,
    private val onDetections: (List<com.wearmux.android.Detection>, Long) -> Unit,
    private val publishCapturedPhoto: (Bitmap) -> Unit,
) {
    private val cloudPreviewInFlight = AtomicBoolean(false)
    fun start() {
        scope.launch {
            bleCameraPipeline.latestBitmap.collect { bitmap ->
                latestCameraBitmap.value = bitmap
                if (bitmap == null) return@collect
                if (isStreaming.value) {
                    recordStreamFrameArrival(streamFpsTimestamps, streamFps, streamFrameCount, lastFrameTimestampMs)
                }
                publishCapturedPhoto(bitmap)
            }
        }
        scope.launch { imagePipeline.latestBitmap.collect { latestBitmap.value = it } }
        scope.launch {
            var lastSent: ByteArray? = null
            while (true) {
                val jpeg = latestCameraJpeg()
                val observedAtMs = latestCameraJpegAtMs()
                if (inferenceMode.value == InferenceMode.CLOUD && jpeg != null && jpeg !== lastSent &&
                    cloudPreviewInFlight.compareAndSet(false, true)) {
                    try {
                        onDetections(inferenceManager.detect(jpeg, observedAtMs), observedAtMs)
                        lastSent = jpeg
                    } catch (e: Exception) { Log.w(TAG, "Cloud camera preview failed: ${e.message}") }
                    finally { cloudPreviewInFlight.set(false) }
                }
                delay(500)
            }
        }
    }
    private companion object { const val TAG = "CameraObservers" }
}
