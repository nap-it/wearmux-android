package com.example.peciwearables.integration.latency

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

class CloudLatencyReporter(
    private val endpointUrl: String = DEFAULT_ENDPOINT,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build(),
) {

    data class RawImuReport(
        val traceId: String,
        val source: String,
        val receivedAtMs: Long,
        val cloudRttMs: Double,
        val endToEndMs: Double,
        val route: String,
        val wristbandTimestampRaw16: Int? = null,
        val wristbandTimestampEstimatedMs: Long? = null,
        val ax: Int,
        val ay: Int,
        val az: Int,
        val gx: Int,
        val gy: Int,
        val gz: Int,
    )

    companion object {
        private const val DEFAULT_ENDPOINT = "http://192.0.2.1:8080/latency"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    fun sendRawImu(report: RawImuReport): Boolean {
        val payload = buildRawImuJson(report)
        val request = Request.Builder()
            .url(endpointUrl)
            .post(payload.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    private fun buildRawImuJson(report: RawImuReport): String {
        val wristbandRaw16 = report.wristbandTimestampRaw16?.toString() ?: "null"
        val wristbandEstMs = report.wristbandTimestampEstimatedMs?.toString() ?: "null"
        return "{" +
            "\"trace_id\":\"${escape(report.traceId)}\"," +
            "\"type\":\"imu_raw\"," +
            "\"source\":\"${escape(report.source)}\"," +
            "\"route\":\"${escape(report.route)}\"," +
            "\"received_at_ms\":${report.receivedAtMs}," +
            "\"wristband_timestamp_raw_16b\":${wristbandRaw16}," +
            "\"wristband_timestamp_est_ms\":${wristbandEstMs}," +
            "\"cloud_rtt_ms\":${"%.3f".format(Locale.US, report.cloudRttMs)}," +
            "\"end_to_end_ms\":${"%.3f".format(Locale.US, report.endToEndMs)}," +
            "\"imu\":{" +
            "\"ax\":${report.ax}," +
            "\"ay\":${report.ay}," +
            "\"az\":${report.az}," +
            "\"gx\":${report.gx}," +
            "\"gy\":${report.gy}," +
            "\"gz\":${report.gz}" +
            "}" +
            "}"
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
