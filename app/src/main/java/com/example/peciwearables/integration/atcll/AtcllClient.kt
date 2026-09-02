package com.example.peciwearables.integration.atcll

import android.util.Log
import com.example.peciwearables.integration.sensors.PhoneGpsLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit


class AtcllClient(
    private val scope: CoroutineScope,
) {
    enum class Status { OFFLINE, LIVE, ERROR }

    /** Mensagens recebidas do ATCLL (DENM/CAM). */
    sealed interface IncomingMessage {
        val timestampMs: Long

        /** Veículo em excesso de velocidade próximo (UC3.1). */
        data class SpeedingVehicle(
            val latitude: Double,
            val longitude: Double,
            val speedKmh: Float,
            val distanceMeters: Float,
            override val timestampMs: Long,
        ) : IncomingMessage

        /** Veículo de emergência a aproximar (UC3.2). */
        data class EmergencyVehicle(
            val latitude: Double,
            val longitude: Double,
            val speedKmh: Float,
            val type: String, // "ambulance" / "fire" / "police"
            val distanceMeters: Float,
            override val timestampMs: Long,
        ) : IncomingMessage
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow(Status.OFFLINE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    private val _outgoingDryRun = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 32)
    /** Mensagens que iriam ser enviadas — útil em modo OFFLINE para debug. */
    val outgoingDryRun: SharedFlow<String> = _outgoingDryRun.asSharedFlow()

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(replay = 0, extraBufferCapacity = 16)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages.asSharedFlow()

    private var pollJob: Job? = null

    fun setEndpoint(url: String?) {
        _endpoint.value = url?.takeIf { it.isNotBlank() }
        _status.value = if (_endpoint.value == null) Status.OFFLINE else Status.LIVE
        if (_endpoint.value == null) pollJob?.cancel()
        else startPolling()
    }

    /** UC2.1 — VRU Awareness Message: "estou aqui, vou atravessar". */
    fun sendVam(gps: PhoneGpsLocation, headingDeg: Float, intentToCross: Boolean) {
        val body = JSONObject()
            .put("type", "VAM")
            .put("lat", gps.latitude)
            .put("lon", gps.longitude)
            .put("speed", gps.speed)
            .put("heading", headingDeg)
            .put("intentToCross", intentToCross)
            .put("ts", System.currentTimeMillis())
        post("/vam", body)
    }

    /** UC2.2 — DENM "stop" (comando vocal explícito). */
    fun sendDenmStop(gps: PhoneGpsLocation) {
        val body = JSONObject()
            .put("type", "DENM")
            .put("subtype", "userStopRequest")
            .put("lat", gps.latitude)
            .put("lon", gps.longitude)
            .put("ts", System.currentTimeMillis())
        post("/denm", body)
    }

    /** UC2.3 — stream de dados sensoriais (objectos detectados, sons). */
    fun sendDataStream(payload: JSONObject) {
        post("/data", payload)
    }

    private fun post(path: String, body: JSONObject) {
        val payload = body.toString()
        val ep = _endpoint.value
        if (ep == null) {
            _outgoingDryRun.tryEmit("$path → $payload")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(ep.trimEnd('/') + path)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "peci-wearables/1.0")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "$path → HTTP ${resp.code}")
                        _status.value = Status.ERROR
                    } else {
                        _status.value = Status.LIVE
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$path falhou: ${e.message}")
                _status.value = Status.ERROR
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                val ep = _endpoint.value ?: break
                try {
                    val req = Request.Builder()
                        .url(ep.trimEnd('/') + "/inbox")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val text = resp.body?.string().orEmpty()
                            parseInbox(text).forEach { _incomingMessages.tryEmit(it) }
                            _status.value = Status.LIVE
                        }
                    }
                } catch (_: Exception) {
                    // Sem mexer no status — pode ser falha pontual.
                }
                delay(2_000)
            }
        }
    }

    private fun parseInbox(json: String): List<IncomingMessage> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val ts = o.optLong("ts", System.currentTimeMillis())
                when (o.optString("type")) {
                    "DENM_speeding" -> IncomingMessage.SpeedingVehicle(
                        latitude = o.optDouble("lat"),
                        longitude = o.optDouble("lon"),
                        speedKmh = o.optDouble("speedKmh", 0.0).toFloat(),
                        distanceMeters = o.optDouble("dist", 0.0).toFloat(),
                        timestampMs = ts,
                    )
                    "CAM_emergency" -> IncomingMessage.EmergencyVehicle(
                        latitude = o.optDouble("lat"),
                        longitude = o.optDouble("lon"),
                        speedKmh = o.optDouble("speedKmh", 0.0).toFloat(),
                        type = o.optString("vtype", "emergency"),
                        distanceMeters = o.optDouble("dist", 0.0).toFloat(),
                        timestampMs = ts,
                    )
                    else -> null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "AtcllClient"
    }
}
