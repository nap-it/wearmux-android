package com.example.peciwearables.integration.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.peciwearables.Detection

/**
 * Abstração para inferência de deteção de objetos.
 * Permite alternar entre processamento local (ONNX/TFLite) e cloud (HTTP).
 */
interface InferenceBackend {
    /**
     * [observedAtMs] é o carimbo do [com.example.peciwearables.integration.observation.Timestamper]
     * aplicado quando o frame saiu da aquisição; `null` quando o pedido nasce
     * no próprio instante (UI). Backends locais ignoram-no.
     */
    suspend fun runDetection(frame: Bitmap, observedAtMs: Long? = null): List<Detection>

    // Overload que evita decode+encode quando os bytes JPEG já estão disponíveis.
    // Por omissão faz decode para Bitmap e delega — backends cloud devem sobrepor.
    suspend fun runDetection(jpeg: ByteArray, observedAtMs: Long? = null): List<Detection> {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return emptyList()
        return try {
            runDetection(bitmap, observedAtMs)
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
