package com.example.peciwearables.integration.image.camera

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.integration.image.BleCameraPipeline
import com.example.peciwearables.integration.image.ImagePipeline
import com.example.peciwearables.integration.inference.InferenceManager
import com.example.peciwearables.integration.inference.InferenceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onDetections: (List<com.example.peciwearables.Detection>, Long) -> Unit,
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
                if (inferenceMode.value == InferenceMode.CLOUD && jpeg != null && jpeg !== lastSent &&
                    cloudPreviewInFlight.compareAndSet(false, true)) {
                    try {
                        onDetections(inferenceManager.detect(jpeg), System.currentTimeMillis())
                        lastSent = jpeg
                    } catch (e: Exception) { Log.w(TAG, "Cloud camera preview falhou: ${e.message}") }
                    finally { cloudPreviewInFlight.set(false) }
                }
                delay(500)
            }
        }
    }
    private companion object { const val TAG = "CameraObservers" }
}
