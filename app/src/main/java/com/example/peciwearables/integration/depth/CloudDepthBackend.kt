package com.example.peciwearables.integration.depth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


class CloudDepthBackend(
    private val serverUrl: String,
    /** Lado maior do JPEG enviado — equilibra latência/qualidade. */
    private val maxSidePx: Int = 384,
    private val csvWriter: LatencyCsvWriter? = null,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun estimateDepth(frame: Bitmap): DepthResult? = withContext(Dispatchers.IO) {
        val resized = resizeIfNeeded(frame, maxSidePx)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val jpegBytes = baos.toByteArray()
        if (resized !== frame) resized.recycle()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "image",
                filename = "frame.jpg",
                body = jpegBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val req = Request.Builder()
            .url(serverUrl)
            .post(body)
            .header("User-Agent", "peci-wearables/1.0")
            .build()

        val traceId = "DEPTH-${depthCounter.incrementAndGet()}"
        val sendNs = System.nanoTime()
        try {
            httpClient.newCall(req).execute().use { resp ->
                val httpRttMs = (System.nanoTime() - sendNs) / 1_000_000.0
                if (!resp.isSuccessful) {
                    Log.w(TAG, "$serverUrl → HTTP ${resp.code}")
                    return@withContext null
                }
                val serverMs = resp.header("X-Depth-Latency-Ms")?.toDoubleOrNull() ?: 0.0
                Log.d(TAG, "BENCH|DEPTH|DONE|id=$traceId" +
                    "|http_rtt_ms=${"%.1f".format(httpRttMs)}" +
                    "|server_ms=${"%.1f".format(serverMs)}" +
                    "|network_ms=${"%.1f".format(httpRttMs - serverMs)}" +
                    "|size_bytes=${jpegBytes.size}")
                csvWriter?.append(
                    filename = "depth_latency.csv",
                    header   = "trace_id,hub_timestamp,http_rtt_ms,server_ms,network_ms,size_bytes",
                    row      = "$traceId,${System.currentTimeMillis()},${"%.1f".format(httpRttMs)},${"%.1f".format(serverMs)},${"%.1f".format(httpRttMs - serverMs)},${jpegBytes.size}"
                )
                val depthBytes = resp.body?.bytes() ?: return@withContext null
                decodeDepthJpeg(depthBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch falhou: ${e.message}")
            null
        }
    }

    private fun resizeIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width; val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest
        val nw = (w * scale).toInt()
        val nh = (h * scale).toInt()
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun decodeDepthJpeg(bytes: ByteArray): DepthResult? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()


        val values = FloatArray(w * h)
        for (i in pixels.indices) {
            val gray = pixels[i] and 0xFF  // canal B (igual em grayscale)
            values[i] = gray / 255f
        }
        return DepthResult(values, w, h)
    }

    companion object {
        private const val TAG = "CloudDepthBackend"
        private val depthCounter = AtomicInteger(0)
    }
}
