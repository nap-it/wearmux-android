package com.example.peciwearables.integration.telemetry

import android.util.Log
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


class TelemetryReporter(
    private val scope: CoroutineScope,
    @Volatile var baseUrl: String = CloudConfig.DEFAULT_BASE_URL,
    private val intervalMs: Long = 1000L,
    private val csvWriter: LatencyCsvWriter? = null,
) {

    private val telemetrySeq = AtomicInteger(0)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4_000, TimeUnit.MILLISECONDS)
        .readTimeout(4_000, TimeUnit.MILLISECONDS)
        .writeTimeout(4_000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var job: Job? = null

    var onDecisions: ((List<Map<String, Any>>) -> Unit)? = null

    data class Zone(
        val name: String,
        val lat: Double,
        val lon: Double,
        val radius: Float,
        val manual: Boolean,
    )

    data class ImuVector(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
        val mx: Float? = null, val my: Float? = null, val mz: Float? = null,
    )

    data class HeadPose(
        val quaternion: FloatArray? = null,
        val rollDeg: Float? = null,
        val pitchDeg: Float? = null,
        val yawDeg: Float? = null,
        val source: String = "glasses_bno085",
        val imu: ImuVector? = null,
    )

    data class Payload(
        val lat: Double?,
        val lon: Double?,
        val speedMps: Float? = null,
        val bearingDeg: Float? = null,
        val glassesOnline: Boolean = false,
        val watchOnline: Boolean = false,
        val wristbandOnline: Boolean = false,
        val zones: List<Zone> = emptyList(),
        val cyclistMode: String? = null,
        val motionState: String? = null,
        val accelMean: Float? = null,
        val glassesImu: ImuVector? = null,
        /**
         * Proveniência do par (lat, lon) — `"gps"`, `"pdr"`, `"pdr_anchored"`
         * ou `"gps_zone_confirm"`. O dashboard pode pintar o marker de
         * cor diferente conforme a fonte.
         */
        val positionSource: String? = null,
        /** Quantidade de fontes IMU a contribuir para a fusão (0..3). */
        val fusedImuCount: Int? = null,
        /** Janela móvel do yaw (graus) — UC1.1 olhar para os dois lados. */
        val yawSpreadDeg: Float? = null,
    )

    /**
     * Arranca o ciclo. `snapshot` é chamado a cada `intervalMs` (default 1 s).
     * Devolve `null` para saltar este ciclo (ex. ainda sem GPS).
     */
    fun start(snapshot: suspend () -> Payload?) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val payload = snapshot() ?: run { delay(intervalMs); continue }
                    send(payload)
                } catch (t: Throwable) {
                    Log.d(TAG, "telemetry tick falhou: ${t.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun send(p: Payload) {
        val json = JSONObject().apply {
            p.lat?.let { put("lat", it) }
            p.lon?.let { put("lon", it) }
            p.speedMps?.let { put("speed_mps", it) }
            p.bearingDeg?.let { put("bearing_deg", it) }
            put("omi_online", p.glassesOnline)
            put("watch_online", p.watchOnline)
            put("sole_online", p.wristbandOnline)
            p.cyclistMode?.let { put("cyclist_mode", it) }
            p.positionSource?.let { put("position_source", it) }
            p.fusedImuCount?.let { put("fused_imu_count", it) }
            put("hub_timestamp", System.currentTimeMillis())
            p.yawSpreadDeg?.let { put("yaw_spread_deg", it) }
            if (p.motionState != null) {
                put("motion_state", JSONObject().apply {
                    put("state", p.motionState)
                    put("confidence", 0.85)
                    put("accel_mean", p.accelMean ?: 0.0)
                })
            }
            if (p.zones.isNotEmpty()) {
                val arr = JSONArray()
                p.zones.forEach { z ->
                    arr.put(JSONObject().apply {
                        put("name", z.name)
                        put("lat", z.lat)
                        put("lon", z.lon)
                        put("radius", z.radius)
                        put("manual", z.manual)
                    })
                }
                put("zones", arr)
            }
            p.glassesImu?.let { put("glasses_imu", imuJson(it)) }
        }
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder()
            .url("$baseUrl/telemetry")
            .post(body)
            .build()
        val sendMs = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.d(TAG, "telemetry HTTP ${resp.code}")
                return
            }
            val responseBody = resp.body?.string() ?: return
            try {
                val jsonResp = org.json.JSONObject(responseBody)
                val decisionsArr = jsonResp.optJSONArray("decisions") ?: return
                if (decisionsArr.length() == 0) {
                    val rttEmpty = System.currentTimeMillis() - sendMs
                    val hubTs = System.currentTimeMillis()
                    Log.d(TAG, "BENCH|TELEMETRY|RTT|rtt_ms=$rttEmpty|decisions=0")
                    csvWriter?.append(
                        filename = "decisions_latency.csv",
                        header   = "seq,hub_timestamp,rtt_ms,decisions_count",
                        row      = "${telemetrySeq.incrementAndGet()},$hubTs,$rttEmpty,0"
                    )
                    return
                }
                val decisions = (0 until decisionsArr.length()).map { i ->
                    val obj = decisionsArr.getJSONObject(i)
                    obj.keys().asSequence().associateWith { k -> obj.get(k) as Any }
                }
                val rttMs = System.currentTimeMillis() - sendMs
                val hubTs = System.currentTimeMillis()
                Log.d(TAG, "BENCH|TELEMETRY|RTT|rtt_ms=$rttMs|decisions=${decisions.size}")
                csvWriter?.append(
                    filename = "decisions_latency.csv",
                    header   = "seq,hub_timestamp,rtt_ms,decisions_count",
                    row      = "${telemetrySeq.incrementAndGet()},$hubTs,$rttMs,${decisions.size}"
                )
                onDecisions?.invoke(decisions)
            } catch (e: Exception) {
                Log.d(TAG, "telemetry response parse: ${e.message}")
            }
        }
    }

    private fun imuJson(v: ImuVector): JSONObject =
        JSONObject().apply {
            put("ax", v.ax)
            put("ay", v.ay)
            put("az", v.az)
            put("gx", v.gx)
            put("gy", v.gy)
            put("gz", v.gz)
            v.mx?.let { put("mx", it) }
            v.my?.let { put("my", it) }
            v.mz?.let { put("mz", it) }
        }

    companion object {
        private const val TAG = "TelemetryReporter"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
