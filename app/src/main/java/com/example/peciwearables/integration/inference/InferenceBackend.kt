package com.example.peciwearables.integration.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.peciwearables.Detection

/**
 * Abstração para inferência de deteção de objetos.
 * Permite alternar entre processamento local (ONNX/TFLite) e cloud (HTTP).
 */
interface InferenceBackend {
    suspend fun runDetection(frame: Bitmap): List<Detection>

    // Overload que evita decode+encode quando os bytes JPEG já estão disponíveis.
    // Por omissão faz decode para Bitmap e delega — backends cloud devem sobrepor.
    suspend fun runDetection(jpeg: ByteArray): List<Detection> {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return emptyList()
        return try {
            runDetection(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun close()
}

enum class InferenceMode {
    LOCAL,
    CLOUD
}
