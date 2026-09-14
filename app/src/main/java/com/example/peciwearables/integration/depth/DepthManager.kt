package com.example.peciwearables.integration.depth

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Visualização do mapa de profundidade — afecta o que é apresentado ao utilizador. */
enum class DepthVisualizationMode { RELATIVE, ABSOLUTE }

class DepthManager(private val csvWriter: LatencyCsvWriter? = null) {
    companion object {
        private const val TAG = "DepthManager"
        const val DEFAULT_URL = CloudConfig.DEPTH_URL
    }

    private val _cloudUrl = MutableStateFlow(DEFAULT_URL)
    val cloudUrl: StateFlow<String> = _cloudUrl

    private val _visualizationMode = MutableStateFlow(DepthVisualizationMode.RELATIVE)
    val visualizationMode: StateFlow<DepthVisualizationMode> = _visualizationMode

    private val _colorMap = MutableStateFlow(DepthColorMap.THERMAL)
    val colorMap: StateFlow<DepthColorMap> = _colorMap

    private val _metersPerUnit = MutableStateFlow(5f)
    val metersPerUnit: StateFlow<Float> = _metersPerUnit

    private var backend: CloudDepthBackend? = null

    fun setCloudUrl(url: String) {
        _cloudUrl.value = url
        backend = null
    }

    fun setVisualizationMode(mode: DepthVisualizationMode) {
        _visualizationMode.value = mode
    }

    fun setColorMap(map: DepthColorMap) {
        _colorMap.value = map
    }
    /* Calibra o factor metros por unidade — aponta para um objecto a
     * `knownDistanceMeters` no centro do frame e chama isto.*/
    fun calibrate(result: DepthResult, knownDistanceMeters: Float) {
        val center = result.sampleCenter()
        if (center > 0f) {
            _metersPerUnit.value = knownDistanceMeters / center
            Log.d(TAG, "Calibrated: center=$center → ${_metersPerUnit.value} m/unit")
        }
    }

    fun toMeters(normalizedDepth: Float): Float = normalizedDepth * _metersPerUnit.value

    private fun getOrCreateBackend(): CloudDepthBackend {
        backend?.let { return it }
        return CloudDepthBackend(serverUrl = _cloudUrl.value, csvWriter = csvWriter).also { backend = it }
    }

    /** Faz fetch ao servidor cloud. Devolve `null` em caso de erro. */
    suspend fun estimateDepth(frame: Bitmap): DepthResult? {
        return try {
            getOrCreateBackend().estimateDepth(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Depth estimation failed: ${e.message}")
            null
        }
    }
}
