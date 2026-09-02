package com.example.peciwearables.integration.safety

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit


class CloudZoneSync(
    private val scope: CoroutineScope,
    private val baseUrlProvider: () -> String,
    private val zonesProvider: () -> List<CrossingZone>,
    private val resyncIntervalMs: Long = 30_000L,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(1_500, TimeUnit.MILLISECONDS)
        .readTimeout(1_500, TimeUnit.MILLISECONDS)
        .writeTimeout(1_500, TimeUnit.MILLISECONDS)
        .build()

    private var resyncJob: Job? = null

    fun startPeriodicResync() {
        stop()
        resyncJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(resyncIntervalMs)
                runCatching { resync() }.onFailure { Log.d(TAG, "resync falhou: ${it.message}") }
            }
        }
    }

    fun stop() {
        resyncJob?.cancel()
        resyncJob = null
    }

    fun add(zone: CrossingZone) {
        scope.launch(Dispatchers.IO) { sendAdd(zone) }
    }

    fun remove(zoneId: String) {
        scope.launch(Dispatchers.IO) { sendRemove(zoneId) }
    }

    private fun resync() {
        zonesProvider().forEach { sendAdd(it) }
    }

    private fun sendAdd(zone: CrossingZone) {
        val base = baseUrlProvider().trim('/')
        if (base.isBlank()) return
        val body = JSONObject().apply {
            put("id", zone.id)
            put("name", zone.name)
            put("lat", zone.latitude)
            put("lon", zone.longitude)
            put("radius_meters", zone.radiusMeters)
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url("$base/zone").post(body).build()
        runCatching { client.newCall(req).execute().use { /* ignore */ } }
            .onFailure { Log.d(TAG, "POST /zone falhou: ${it.message}") }
    }

    private fun sendRemove(zoneId: String) {
        val base = baseUrlProvider().trim('/')
        if (base.isBlank()) return
        val req = Request.Builder().url("$base/zone/$zoneId").delete().build()
        runCatching { client.newCall(req).execute().use { /* ignore */ } }
            .onFailure { Log.d(TAG, "DELETE /zone falhou: ${it.message}") }
    }

    private companion object {
        const val TAG = "CloudZoneSync"
        val JSON = "application/json".toMediaType()
    }
}
