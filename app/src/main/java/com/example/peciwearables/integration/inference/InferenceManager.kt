package com.example.peciwearables.integration.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.Detection
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gere a alternância entre inferência local e cloud.
 * Usado tanto para YOLO (câmara) como futuramente para o modelo da pulseira (IMU).
 */
class InferenceManager(
    private val context: Context,
    private val csvWriter: LatencyCsvWriter? = null,
) {

    companion object {
        private const val TAG = "InferenceManager"
    }

    private val _mode = MutableStateFlow(InferenceMode.CLOUD)
    val mode: StateFlow<InferenceMode> = _mode

    private val _cloudUrl = MutableStateFlow(CloudConfig.DETECT_URL)
    val cloudUrl: StateFlow<String> = _cloudUrl

    private var currentBackend: InferenceBackend? = null

    fun setMode(mode: InferenceMode) {
        if (_mode.value == mode) return
        currentBackend?.close()
        currentBackend = null
        _mode.value = mode
        Log.d(TAG, "Inference mode → $mode")
    }

    fun setCloudUrl(url: String) {
        _cloudUrl.value = url
        if (_mode.value == InferenceMode.CLOUD) {
            currentBackend?.close()
            currentBackend = null
        }
    }

    private fun getOrCreateBackend(): InferenceBackend {
        currentBackend?.let { return it }
        val backend = when (_mode.value) {
            InferenceMode.LOCAL -> LocalInferenceBackend(context)
            InferenceMode.CLOUD -> CloudInferenceBackend(serverUrl = _cloudUrl.value, csvWriter = csvWriter)
        }
        currentBackend = backend
        return backend
    }

    suspend fun detect(frame: Bitmap): List<Detection> {
        return try {
            getOrCreateBackend().runDetection(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun detect(jpeg: ByteArray): List<Detection> {
        return try {
            getOrCreateBackend().runDetection(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed: ${e.message}")
            emptyList()
        }
    }

    fun close() {
        currentBackend?.close()
        currentBackend = null
    }
}
