package com.wearmux.android.integration.inference

import android.content.Context
import android.graphics.Bitmap
import com.wearmux.android.Detection
import com.wearmux.android.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inferência local usando YOLOv8n via ONNX Runtime.
 */
class LocalInferenceBackend(context: Context) : InferenceBackend {

    private val detector = YoloDetector(context)

    override suspend fun runDetection(frame: Bitmap, observedAtMs: Long?): List<Detection> =
        withContext(Dispatchers.Default) {
            detector.detect(frame)
        }

    override fun close() {
        detector.close()
    }
}
