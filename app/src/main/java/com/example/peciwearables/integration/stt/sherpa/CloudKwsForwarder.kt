package com.example.peciwearables.integration.stt.sherpa

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reencaminha cada keyword detectado pelo Sherpa KWS local para o unified_server
 * em `POST /inputs/audio_stt` — sem isto o fusion engine na cloud nunca sabe que
 * o utilizador disse "go"/"crossing" e UC1.3 fica em silêncio.
 */
class CloudKwsForwarder(
    private val scope: CoroutineScope,
    private val baseUrlProvider: () -> String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .writeTimeout(1_500, TimeUnit.MILLISECONDS)
        .build()

    fun submit(keyword: String) {
        val k = keyword.trim()
        if (k.isBlank()) return
        scope.launch(Dispatchers.IO) { send(k) }
    }

    private fun send(keyword: String) {
        val base = baseUrlProvider().trim('/')
        if (base.isBlank()) return
        val body = JSONObject().apply {
            put("audio_segment_id", UUID.randomUUID().toString())
            put("hub_timestamp", System.currentTimeMillis())
            put("model", "sherpa-kws-cloud")
            put("text", keyword)
            put("language", "pt")
            put("confidence", 1.0)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$base/inputs/audio_stt").post(body).build()
        runCatching { client.newCall(req).execute().use { /* ignore */ } }
            .onFailure { Log.d(TAG, "POST /inputs/audio_stt falhou: ${it.message}") }
    }

    private companion object {
        const val TAG = "CloudKwsForwarder"
        val JSON = "application/json".toMediaType()
    }
}
