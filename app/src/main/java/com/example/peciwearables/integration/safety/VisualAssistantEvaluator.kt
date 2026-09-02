package com.example.peciwearables.integration.safety

import android.graphics.Bitmap
import android.util.Log
import com.example.peciwearables.integration.audio.TextToSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit


class VisualAssistantEvaluator(private val tts: TextToSpeechEngine) {
    companion object {
        private const val TAG = "VisualAssistant"
        private val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(3_000, TimeUnit.MILLISECONDS)
            .readTimeout(5_000, TimeUnit.MILLISECONDS)
            .writeTimeout(3_000, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun analyzeAndSpeak(image: Bitmap, intentContext: String = "frente", cloudUrl: String) {
        withContext(Dispatchers.IO) {
            val text = fetchFromCloud(baseUrlOf(cloudUrl), image, intentContext)
            Log.i(TAG, "UC4.1 response: $text")
            tts.speak(text, flush = true)
        }
    }

    private fun fetchFromCloud(baseUrl: String, image: Bitmap, intentContext: String): String {
        if (baseUrl.isBlank()) return "A cloud não está configurada."
        return try {
            val baos = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", baos.toByteArray().toRequestBody(JPEG_MEDIA_TYPE))
                .addFormDataPart("intent", intentContext)
                .addFormDataPart("hub_timestamp", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder().url("$baseUrl/inputs/visual_assistant").post(body).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "O servidor não respondeu (HTTP ${resp.code})."
                val payload = resp.body?.string() ?: return "O servidor devolveu uma resposta vazia."
                JSONObject(payload).optString("text", "").ifBlank { "Não consegui descrever o que tens à frente." }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cloud visual_assistant falhou: ${e.message}")
            "Sem ligação ao servidor para análise visual."
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
