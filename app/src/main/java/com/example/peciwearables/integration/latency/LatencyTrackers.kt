package com.example.peciwearables.integration.latency

import com.example.peciwearables.integration.LatencySample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun nowStamp(): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())


class PhotoLatencyTracker(
    private val routeLabel: () -> String,
    private val transportEstimateMs: () -> Long,
    private val benchLog: (String) -> Unit,
    private val onSample: (LatencySample) -> Unit,
    private val appendLog: (String) -> Unit,
) {
    private data class Active(
        val traceId: String,
        val route: String,
        val commandSentAtMs: Long,
        val commandSentAtNs: Long,
        val transportEstimateMsAtSend: Long,
        var firstPayloadAtMs: Long? = null,
        var firstPayloadAtNs: Long? = null,
        var lastPayloadAtMs: Long? = null,
    )

    private var counter = 0L
    private var active: Active? = null

    fun start() {
        val a = Active(
            traceId = "PHOTO-${++counter}",
            route = routeLabel(),
            commandSentAtMs = System.currentTimeMillis(),
            commandSentAtNs = System.nanoTime(),
            transportEstimateMsAtSend = transportEstimateMs(),
        )
        active = a
        benchLog("PHOTO|START|id=${a.traceId}|route=${a.route}|cmd_ms=${a.commandSentAtMs}|cmd_ns=${a.commandSentAtNs}|transport_est_ms=${a.transportEstimateMsAtSend}")
    }

    fun notePayload() {
        val a = active ?: return
        val now = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        if (a.firstPayloadAtMs == null) {
            a.firstPayloadAtMs = now
            a.firstPayloadAtNs = nowNs
            benchLog("PHOTO|FIRST_PAYLOAD|id=${a.traceId}|route=${a.route}|first_ms=$now|first_ns=$nowNs")
        }
        a.lastPayloadAtMs = now
    }

    fun finalize() {
        val a = active ?: return
        val now = System.currentTimeMillis()
        val firstPayloadAt = a.firstPayloadAtMs ?: now
        val lastPayloadAt = a.lastPayloadAtMs ?: firstPayloadAt
        val totalMs = (now - a.commandSentAtMs).coerceAtLeast(0L)
        val rawPhone = (now - lastPayloadAt).coerceAtLeast(0L)
        val phoneMs = rawPhone.coerceAtMost(totalMs)
        val transportRaw = if (a.route == "Wi-Fi") transportEstimateMs() else a.transportEstimateMsAtSend
        val transportMs = transportRaw.coerceAtMost((totalMs - phoneMs).coerceAtLeast(0L))
        val glassesMs = (totalMs - phoneMs - transportMs).coerceAtLeast(0L)
        val sample = LatencySample(
            route = a.route, totalMs = totalMs, phoneProcessingMs = phoneMs,
            transportEstimateMs = transportMs, glassesProcessingEstimateMs = glassesMs,
            timestamp = nowStamp(),
        )
        onSample(sample)
        val firstNs = a.firstPayloadAtNs ?: a.commandSentAtNs
        benchLog(
            "PHOTO|DONE|id=${a.traceId}|route=${sample.route}|total_ms=${sample.totalMs}|phone_ms=${sample.phoneProcessingMs}|transport_ms=${sample.transportEstimateMs}|omi_ms=${sample.glassesProcessingEstimateMs}|cmd_ms=${a.commandSentAtMs}|cmd_ns=${a.commandSentAtNs}|first_payload_ns=$firstNs|done_ms=$now|done_ns=${System.nanoTime()}"
        )
        appendLog("⏱ FOTO ${sample.route}: total=${sample.totalMs}ms telefone=${sample.phoneProcessingMs}ms transporte≈${sample.transportEstimateMs}ms óculos≈${sample.glassesProcessingEstimateMs}ms")
        active = null
    }
}

/** State machine para medir a latência do microfone (comando → primeiro pacote decodificado). */
class MicrophoneLatencyTracker(
    private val routeLabel: () -> String,
    private val transportEstimateMs: () -> Long,
    private val benchLog: (String) -> Unit,
    private val onSample: (LatencySample) -> Unit,
    private val appendLog: (String) -> Unit,
) {
    private data class Active(
        val traceId: String,
        val route: String,
        val commandSentAtMs: Long,
        val commandSentAtNs: Long,
        val transportEstimateMsAtSend: Long,
        var firstPacketAtMs: Long? = null,
        var firstPacketAtNs: Long? = null,
        var firstDecodeDoneAtMs: Long? = null,
    )

    private var counter = 0L
    private var active: Active? = null

    fun start() {
        val a = Active(
            traceId = "MIC-${++counter}",
            route = routeLabel(),
            commandSentAtMs = System.currentTimeMillis(),
            commandSentAtNs = System.nanoTime(),
            transportEstimateMsAtSend = transportEstimateMs(),
        )
        active = a
        benchLog("MIC|START|id=${a.traceId}|route=${a.route}|cmd_ms=${a.commandSentAtMs}|cmd_ns=${a.commandSentAtNs}|transport_est_ms=${a.transportEstimateMsAtSend}")
    }

    fun notePacket() {
        val a = active ?: return
        if (a.firstPacketAtMs == null) {
            a.firstPacketAtMs = System.currentTimeMillis()
            a.firstPacketAtNs = System.nanoTime()
            benchLog("MIC|FIRST_PACKET|id=${a.traceId}|route=${a.route}|first_ms=${a.firstPacketAtMs}|first_ns=${a.firstPacketAtNs}")
        }
    }

    fun finalizeIfNeeded() {
        val a = active ?: return
        if (a.firstPacketAtMs == null || a.firstDecodeDoneAtMs != null) return
        val now = System.currentTimeMillis()
        a.firstDecodeDoneAtMs = now
        val firstPacketAt = a.firstPacketAtMs ?: now
        val phoneMs = (now - firstPacketAt).coerceAtLeast(0L)
        val totalMs = (firstPacketAt - a.commandSentAtMs).coerceAtLeast(0L)
        val transportRaw = if (a.route == "Wi-Fi") transportEstimateMs() else a.transportEstimateMsAtSend
        val transportMs = transportRaw.coerceAtMost(totalMs)
        val glassesMs = (totalMs - transportMs).coerceAtLeast(0L)
        val sample = LatencySample(
            route = a.route, totalMs = totalMs, phoneProcessingMs = phoneMs,
            transportEstimateMs = transportMs, glassesProcessingEstimateMs = glassesMs,
            timestamp = nowStamp(),
        )
        onSample(sample)
        val firstNs = a.firstPacketAtNs ?: a.commandSentAtNs
        benchLog(
            "MIC|DONE|id=${a.traceId}|route=${sample.route}|total_ms=${sample.totalMs}|phone_ms=${sample.phoneProcessingMs}|transport_ms=${sample.transportEstimateMs}|omi_ms=${sample.glassesProcessingEstimateMs}|cmd_ms=${a.commandSentAtMs}|cmd_ns=${a.commandSentAtNs}|first_packet_ns=$firstNs|done_ms=$now|done_ns=${System.nanoTime()}"
        )
        appendLog("⏱ MICRO ${sample.route}: total=${sample.totalMs}ms telefone=${sample.phoneProcessingMs}ms transporte≈${sample.transportEstimateMs}ms óculos≈${sample.glassesProcessingEstimateMs}ms")
        active = null
    }
}

// ── YOLO latency sample ───────────────────────────────────────────────────────
data class YoloLatencySample(
    val traceId: String,
    val frameSizeBytes: Int,
    val httpRttMs: Double,
    val serverProcessingMs: Double,
    val networkMs: Double,
    val detectionCount: Int,
    val hubTimestamp: Long,
)

// ── Depth latency sample ──────────────────────────────────────────────────────
data class DepthLatencySample(
    val traceId: String,
    val frameSizeBytes: Int,
    val httpRttMs: Double,
    val serverProcessingMs: Double,
    val networkMs: Double,
)

// ── KWS latency sample ────────────────────────────────────────────────────────
data class KwsLatencySample(
    val traceId: String,
    val keyword: String,
    val rttMs: Long,
)

// ── Watch alert latency sample ────────────────────────────────────────────────
data class WatchAlertLatencySample(
    val traceId: String,
    val alertType: Int,
    val rttMs: Long,
)
