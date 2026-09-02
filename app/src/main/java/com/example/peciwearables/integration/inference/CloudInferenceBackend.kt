package com.example.peciwearables.integration.inference

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.Detection
import com.example.peciwearables.integration.CloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CloudInferenceBackend(
    private val serverUrl: String = CloudConfig.DETECT_URL,
    private val timeoutMs: Long = 10000,
    private val csvWriter: LatencyCsvWriter? = null,
) : InferenceBackend {

    companion object {
        private const val TAG = "CloudInference"
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val yoloCounter = AtomicInteger(0)
    }

    // Cliente partilhado por instância — connection pool mantém a TCP connection aberta entre frames.
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun runDetection(jpeg: ByteArray): List<Detection> =
        withContext(Dispatchers.IO) {
            try {
                val traceId = "YOLO-${yoloCounter.incrementAndGet()}"
                val hubTimestamp = System.currentTimeMillis()
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "frame.jpg", jpeg.toRequestBody(JPEG_MEDIA_TYPE))
                    .addFormDataPart("hub_timestamp", hubTimestamp.toString())
                    .build()
                val request = Request.Builder().url(serverUrl).post(body).build()
                val sendNs = System.nanoTime()
                val responseText = client.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    resp.body!!.string()
                }
                val httpRttMs = (System.nanoTime() - sendNs) / 1_000_000.0
                val serverProcessingMs = try {
                    JSONObject(responseText).optDouble("latency_ms", 0.0)
                } catch (_: Exception) { 0.0 }
                val detections = parseResponse(responseText)
                Log.d(TAG, "BENCH|YOLO|DONE|id=$traceId" +
                    "|http_rtt_ms=${"%.1f".format(httpRttMs)}" +
                    "|server_ms=${"%.1f".format(serverProcessingMs)}" +
                    "|network_ms=${"%.1f".format(httpRttMs - serverProcessingMs)}" +
                    "|detections=${detections.size}" +
                    "|size_bytes=${jpeg.size}")
                csvWriter?.append(
                    filename = "yolo_latency.csv",
                    header   = "trace_id,hub_timestamp,http_rtt_ms,server_ms,network_ms,detections,size_bytes",
                    row      = "$traceId,$hubTimestamp,${"%.1f".format(httpRttMs)},${"%.1f".format(serverProcessingMs)},${"%.1f".format(httpRttMs - serverProcessingMs)},${detections.size},${jpeg.size}"
                )
                detections
            } catch (e: Exception) {
                Log.e(TAG, "Cloud inference failed: ${e.message}")
                emptyList()
            }
        }

    override suspend fun runDetection(frame: Bitmap): List<Detection> {
        val baos = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        return runDetection(baos.toByteArray())
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }

    private fun parseResponse(json: String): List<Detection> {
        val arr = JSONObject(json).getJSONArray("detections")
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Detection(
                label = d.getString("label"),
                confidence = d.getDouble("confidence").toFloat(),
                left = d.getDouble("left").toFloat(),
                top = d.getDouble("top").toFloat(),
                right = d.getDouble("right").toFloat(),
                bottom = d.getDouble("bottom").toFloat()
            )
        }
    }
}
