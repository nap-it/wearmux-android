package com.example.peciwearables.integration.latency

import com.example.peciwearables.integration.protocol.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger


class ImuMlBenchmarkRunner(
    private val scope: CoroutineScope,
    private val reporter: CloudLatencyReporter,
    private val routeLabel: () -> String,
    private val benchLog: (String) -> Unit,
    private val appendLog: (String) -> Unit,
    private val latestInferenceProvider: () -> InferenceSnapshot,
) {
    data class InferenceSnapshot(val className: String, val score: Float, val timestampMs: Long)

    private val pending = AtomicInteger(0)
    @Volatile private var busy: Boolean = false
    private var counter = 0L

    fun request() { pending.incrementAndGet() }

    fun process(
        source: String,
        sample: ImuSample,
        wristbandTimestampRaw16: Int? = null,
        wristbandTimestampEstimatedMs: Long? = null,
    ): Job? {
        if (busy) return null
        if (pending.get() <= 0) return null
        busy = true
        val receivedAtNs = System.nanoTime()
        val receivedAtMs = System.currentTimeMillis()
        return scope.launch(Dispatchers.Default) {
            try {
                val traceId = "IMU-${++counter}"
                if (!consumeRequest()) return@launch
                val appPreSendMs = (System.nanoTime() - receivedAtNs) / 1_000_000.0
                val wristbandToPhoneMs = wristbandTimestampEstimatedMs
                    ?.let { receivedAtMs - it }?.takeIf { it in 0L..120_000L }?.toDouble()
                val endToEndBeforeSendMs = wristbandToPhoneMs?.plus(appPreSendMs) ?: -1.0
                val cloudStartNs = System.nanoTime()
                val sent = try {
                    reporter.sendRawImu(
                        CloudLatencyReporter.RawImuReport(
                            traceId = traceId, source = source,
                            receivedAtMs = receivedAtMs, cloudRttMs = -1.0,
                            endToEndMs = endToEndBeforeSendMs, route = routeLabel(),
                            wristbandTimestampRaw16 = wristbandTimestampRaw16,
                            wristbandTimestampEstimatedMs = wristbandTimestampEstimatedMs,
                            ax = sample.ax.toInt(), ay = sample.ay.toInt(), az = sample.az.toInt(),
                            gx = sample.gx.toInt(), gy = sample.gy.toInt(), gz = sample.gz.toInt(),
                        )
                    )
                } catch (_: Exception) { false }
                val cloudRttMs = (System.nanoTime() - cloudStartNs) / 1_000_000.0
                val endToEndMs = (System.nanoTime() - receivedAtNs) / 1_000_000.0
                val totalWithCloudMs = if (endToEndBeforeSendMs >= 0.0) endToEndBeforeSendMs + cloudRttMs else -1.0

                withContext(Dispatchers.Main) {
                    val inference = latestInferenceProvider()
                    val rawTsLog = wristbandTimestampRaw16?.toString() ?: "-"
                    val estTsLog = wristbandTimestampEstimatedMs?.toString() ?: "-"
                    val wristToPhoneLog = wristbandToPhoneMs?.let { "%.2f".format(it) } ?: "-"
                    val e2ePayloadLog = if (endToEndBeforeSendMs >= 0.0) "%.2f".format(endToEndBeforeSendMs) else "-"
                    val totalCloudLog = if (totalWithCloudMs >= 0.0) "%.2f".format(totalWithCloudMs) else "-"
                    val totalMsForScript = if (totalWithCloudMs >= 0.0) "%.2f".format(totalWithCloudMs) else "%.2f".format(endToEndMs)
                    val phoneMsForScript = "%.2f".format(appPreSendMs)
                    val transportMsForScript = wristbandToPhoneMs?.let { "%.2f".format(it) } ?: ""
                    val cmdMsForScript = wristbandTimestampEstimatedMs?.toString() ?: ""
                    benchLog(
                        "IMU_ML|DONE|id=$traceId|source=$source|route=${routeLabel()}|total_ms=$totalMsForScript|phone_ms=$phoneMsForScript|transport_ms=$transportMsForScript|omi_ms=|cmd_ms=$cmdMsForScript|done_ms=$receivedAtMs|infer_class=${inference.className}|infer_score=${"%.3f".format(inference.score)}|infer_ts_ms=${inference.timestampMs}|ts16=$rawTsLog|ts_est_ms=$estTsLog|wrist_to_phone_ms=$wristToPhoneLog|app_pre_send_ms=${"%.2f".format(appPreSendMs)}|payload_e2e_ms=$e2ePayloadLog|total_with_cloud_ms=$totalCloudLog|cloud_rtt_ms=${"%.2f".format(cloudRttMs)}|app_total_after_cb_ms=${"%.2f".format(endToEndMs)}|cloud_ok=$sent"
                    )
                    appendLog("IMU enviado para cloud (cloud_ok=$sent) | classe=${inference.className}")
                }
            } finally { busy = false }
        }
    }

    private fun consumeRequest(): Boolean {
        while (true) {
            val current = pending.get()
            if (current <= 0) return false
            if (pending.compareAndSet(current, current - 1)) return true
        }
    }
}
