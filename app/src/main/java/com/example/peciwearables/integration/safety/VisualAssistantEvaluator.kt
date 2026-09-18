package com.example.peciwearables.integration.safety

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.integration.modules.android.TextToSpeechEngine
import com.example.peciwearables.integration.observation.Timestamper
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject


class VisualAssistantEvaluator(
    private val tts: TextToSpeechEngine,
    private val timestamper: Timestamper = Timestamper.SYSTEM,
) {
    companion object {
        private const val TAG = "VisualAssistant"
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(3_000, TimeUnit.MILLISECONDS)
            .readTimeout(5_000, TimeUnit.MILLISECONDS)
            .writeTimeout(3_000, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun analyzeAndSpeak(
        image: Bitmap,
        intentContext: String = "front",
        cloudUrl: String,
        observedAtMs: Long? = null,
    ) {
        withContext(Dispatchers.IO) {
            val text = fetchFromCloud(baseUrlOf(cloudUrl), image, intentContext, observedAtMs)
            Log.i(TAG, "UC4.1 response: $text")
            tts.speak(text, flush = true)
        }
    }

    private fun fetchFromCloud(
        baseUrl: String,
        image: Bitmap,
        intentContext: String,
        observedAtMs: Long?,
    ): String {
        if (baseUrl.isBlank()) return "The cloud is not configured."
        return try {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", baos.toByteArray().toRequestBody(JPEG_MEDIA_TYPE))
                .addFormDataPart("intent", intentContext)
                .addFormDataPart("hub_timestamp", timestamper.hubTimestampFor(observedAtMs).toString())
                .build()
            val request = Request.Builder().url("$baseUrl/inputs/visual_assistant").post(body).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "The server did not respond (HTTP ${resp.code})."
                val payload = resp.body?.string() ?: return "The server returned an empty response."
                JSONObject(payload).optString("text", "").ifBlank { "I couldn't describe what is in front of you." }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cloud visual_assistant failed: ${e.message}")
            "No connection to the server for visual analysis."
        }
    }

    private fun baseUrlOf(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/detect") -> trimmed.removeSuffix("/detect")
            trimmed.endsWith("/depth") -> trimmed.removeSuffix("/depth")
            trimmed.endsWith("/transcribe") -> trimmed.removeSuffix("/transcribe")
            else -> trimmed
        }
    }
}
