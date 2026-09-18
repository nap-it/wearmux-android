package com.example.peciwearables.integration.api

import android.util.Log
import com.example.peciwearables.integration.observation.Timestamper
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Reencaminha cada keyword detectado pelo Sherpa KWS local para o unified_server
 * em `POST /inputs/audio_stt` — sem isto o fusion engine na cloud nunca sabe que
 * o utilizador disse "go"/"crossing" e UC1.3 fica em silêncio.
 */
class CloudKwsForwarder(
    private val scope: CoroutineScope,
    private val baseUrlProvider: () -> String,
    private val timestamper: Timestamper = Timestamper.SYSTEM,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .writeTimeout(1_500, TimeUnit.MILLISECONDS)
        .build()

    /** [observedAtMs]: instante em que o keyword foi detectado (saída da aquisição). */
    fun submit(keyword: String, observedAtMs: Long? = null) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val hubTs = timestamper.hubTimestampFor(observedAtMs)
        scope.launch(Dispatchers.IO) { send(k, hubTs) }
    }

    private fun send(keyword: String, hubTimestampMs: Long) {
        val base = baseUrlProvider().trim('/')
        if (base.isBlank()) return
        val body = JSONObject().apply {
            put("audio_segment_id", UUID.randomUUID().toString())
            put("hub_timestamp", hubTimestampMs)
            put("model", "sherpa-kws-cloud")
            put("text", keyword)
            put("language", "en")
            put("confidence", 1.0)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$base/inputs/audio_stt").post(body).build()
        runCatching { client.newCall(req).execute().use { /* ignore */ } }
            .onFailure { Log.d(TAG, "POST /inputs/audio_stt failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "CloudKwsForwarder"
        val JSON = "application/json".toMediaType()
    }
}
