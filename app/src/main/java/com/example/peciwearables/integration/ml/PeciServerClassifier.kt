package com.example.peciwearables.integration.ml

import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.protocol.ImuSample
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PeciServerClassifier(
    private var endpointUrl: String = DEFAULT_ENDPOINT,
    private val windowSize: Int = 20,
    private val stepSize: Int = 10,
    private val confidenceThreshold: Float = 0.50f,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val DEFAULT_ENDPOINT = CloudConfig.INFER_URL
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    data class InferenceResult(
        val label: String,
        val score: Float,
        val values: FloatArray = floatArrayOf(),
    )

    private val buffer = ArrayDeque<FloatArray>(windowSize)
    private var samplesSinceInference = 0

    @Synchronized
    fun setEndpointUrl(newUrl: String) {
        endpointUrl = newUrl
    }

    @Synchronized
    fun getEndpointUrl(): String = endpointUrl

    @Synchronized
    fun reset() {
        buffer.clear()
        samplesSinceInference = 0
    }

    @Synchronized
    fun addSample(sample: ImuSample): FloatArray? {
        val fused = toFusedAxes(sample)
        if (buffer.size == windowSize) buffer.removeFirst()
        buffer.addLast(fused)

        if (buffer.size < windowSize) return null

        samplesSinceInference += 1
        if (samplesSinceInference < stepSize) return null
        samplesSinceInference = 0

        val out = FloatArray(windowSize * 6)
        var pos = 0
        for (row in buffer) {
            for (i in 0 until 6) out[pos++] = row[i]
        }
        return out
    }

    fun classify(features: FloatArray): InferenceResult? {
        return runCatching {
            val payload = JSONObject().apply {
                put("features", JSONArray().also { arr -> features.forEach { arr.put(it.toDouble()) } })
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching null
                parseResponse(body)
            }
        }.getOrNull()
    }

    private fun parseResponse(body: String): InferenceResult? {
        return runCatching {
            val json = JSONObject(body)

            val directLabel = json.optString("label", "")
            val directScore = json.optDouble("score", Double.NaN)
            if (directLabel.isNotBlank()) {
                val score = if (directScore.isNaN()) 0f else directScore.toFloat()
                if (score >= confidenceThreshold || score == 0f) {
                    return@runCatching InferenceResult(directLabel, score)
                }
            }

            val results = json.optJSONArray("results")
            if (results != null && results.length() > 0) {
                var bestLabel = ""
                var bestScore = Float.NEGATIVE_INFINITY
                val allValues = FloatArray(results.length())
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val label = item.optString("label", "class_$i")
                    val value = item.optDouble("value", 0.0).toFloat()
                    allValues[i] = value
                    if (value > bestScore) {
                        bestScore = value
                        bestLabel = label
                    }
                }
                if (bestLabel.isNotBlank() && bestScore >= confidenceThreshold) {
                    return@runCatching InferenceResult(bestLabel, bestScore, allValues)
                }
            }

            null
        }.getOrNull()
    }

    private fun toFusedAxes(sample: ImuSample): FloatArray {
        val accX = sample.ax / 1000f
        val accY = sample.ay / 1000f
        val accZ = sample.az / 1000f

        val mx = sample.mx / 10f
        val my = sample.my / 10f
        val mz = sample.mz / 10f

        val rollRad = atan2(accY.toDouble(), accZ.toDouble()).toFloat()
        val pitchRad = atan2((-accX).toDouble(), sqrt((accY * accY + accZ * accZ).toDouble())).toFloat()

        val mxComp = mx * cos(pitchRad) + mz * sin(pitchRad)
        val myComp = mx * sin(rollRad) * sin(pitchRad) + my * cos(rollRad) - mz * sin(rollRad) * cos(pitchRad)

        var headingDeg = Math.toDegrees(atan2((-myComp).toDouble(), mxComp.toDouble())).toFloat()
        if (headingDeg < 0f) headingDeg += 360f

        val pitchDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()
        val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

        return floatArrayOf(accX, accY, accZ, headingDeg, pitchDeg, rollDeg)
    }
}
