package com.example.peciwearables.integration.image.camera

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong


class CameraMetrics(
    private val transportLabelProvider: () -> String = { "unknown" },
    private val targetFpsProvider: () -> Int = { 0 },
    private val logEverySecond: Boolean = true,
) {

    companion object {
        private const val TAG = "CAMERA_METRICS"
        private const val AGGREGATE_INTERVAL_MS = 1000L
        private const val FPS_WINDOW_MS = 2000L

        /** Marker `RecentFrame` para a janela móvel de FPS. */
        private data class RecentFrame(val timestampMs: Long, val sizeBytes: Int)
    }

    // ── Contadores cumulativos (AtomicLong, thread-safe) ─────────────────────

    private val framesReceived = AtomicLong(0)        // chegaram bytes para um frame
    private val framesAssembled = AtomicLong(0)       // assembler completou o JPEG
    private val framesDecoded = AtomicLong(0)         // decode → Bitmap OK
    private val framesDropped = AtomicLong(0)         // descartados em backpressure / canal cheio
    private val decodeFailures = AtomicLong(0)        // BitmapFactory devolveu null ou exceção
    private val assemblyErrors = AtomicLong(0)        // header/footer/size inválido, overshoot
    private val duplicateAssembleSuppressed = AtomicLong(0)
    private val overshootTruncations = AtomicLong(0)  // bytes a mais truncados no assembler
    private val crcFailures = AtomicLong(0)
    private val transferTimeouts = AtomicLong(0)
    private val transferStatusAborts = AtomicLong(0)
    private val opsDroppedBackpressure = AtomicLong(0)  // GlassesCameraManager channel overflow

    // Acumuladores para médias (snapshot consome e zera no fim de cada janela)
    private val cumulativeAssembleMs = AtomicLong(0)
    private val cumulativeDecodeMs = AtomicLong(0)
    private val cumulativeFrameSizeBytes = AtomicLong(0)

    private val lastErrorMessage = AtomicLong(0)  // não usado para texto — placeholder
    @Volatile private var lastErrorText: String? = null
    @Volatile private var lastErrorTimeMs: Long? = null

    // Janela móvel para FPS — sincronizada (acesso pelo aggregator e recordFrame*)
    private val recentFramesLock = Any()
    private val recentFrames = ArrayDeque<RecentFrame>()

    // ── Snapshot publicado ────────────────────────────────────────────────────

    private val _snapshot = MutableStateFlow(CameraMetricsSnapshot.EMPTY)
    val snapshot: StateFlow<CameraMetricsSnapshot> = _snapshot.asStateFlow()

    private var aggregatorJob: Job? = null
    private var lastEmittedAtMs: Long = 0L
    private var lastEmittedAssembled: Long = 0L
    private var lastEmittedDecoded: Long = 0L

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Arranca o job aggregator de 1 Hz. Idempotente. */
    fun start(scope: CoroutineScope) {
        if (aggregatorJob?.isActive == true) return
        aggregatorJob = scope.launch {
            while (isActive) {
                delay(AGGREGATE_INTERVAL_MS)
                publishSnapshot(System.currentTimeMillis())
            }
        }
    }

    /** Pára o job aggregator. Os contadores não são zerados. */
    fun stop() {
        aggregatorJob?.cancel()
        aggregatorJob = null
    }

    /**
     * Reseta TODOS os contadores. Tipicamente chamado em stop de stream para que
     * a próxima sessão comece com um snapshot limpo.
     */
    fun reset() {
        framesReceived.set(0)
        framesAssembled.set(0)
        framesDecoded.set(0)
        framesDropped.set(0)
        decodeFailures.set(0)
        assemblyErrors.set(0)
        duplicateAssembleSuppressed.set(0)
        overshootTruncations.set(0)
        crcFailures.set(0)
        transferTimeouts.set(0)
        transferStatusAborts.set(0)
        opsDroppedBackpressure.set(0)
        cumulativeAssembleMs.set(0)
        cumulativeDecodeMs.set(0)
        cumulativeFrameSizeBytes.set(0)
        synchronized(recentFramesLock) { recentFrames.clear() }
        lastErrorText = null
        lastErrorTimeMs = null
        lastEmittedAssembled = 0
        lastEmittedDecoded = 0
        _snapshot.value = CameraMetricsSnapshot.EMPTY
    }

    // ── Record API (chamável de qualquer thread) ─────────────────────────────

    fun recordFrameReceived() { framesReceived.incrementAndGet() }

    fun recordFrameAssembled(sizeBytes: Int, assembleMs: Long = 0L) {
        framesAssembled.incrementAndGet()
        cumulativeAssembleMs.addAndGet(assembleMs)
        cumulativeFrameSizeBytes.addAndGet(sizeBytes.toLong())
        val now = System.currentTimeMillis()
        synchronized(recentFramesLock) {
            recentFrames.addLast(RecentFrame(now, sizeBytes))
            // Trim window
            while (recentFrames.isNotEmpty() && now - recentFrames.first().timestampMs > FPS_WINDOW_MS) {
                recentFrames.removeFirst()
            }
        }
    }

    fun recordFrameDecoded(decodeMs: Long = 0L) {
        framesDecoded.incrementAndGet()
        cumulativeDecodeMs.addAndGet(decodeMs)
    }

    fun recordFrameDropped(reason: String? = null) {
        framesDropped.incrementAndGet()
        if (reason != null) noteError("drop: $reason")
    }

    fun recordDecodeFailure(message: String? = null) {
        decodeFailures.incrementAndGet()
        if (message != null) noteError("decode: $message")
    }

    fun recordAssemblyError(message: String? = null) {
        assemblyErrors.incrementAndGet()
        if (message != null) noteError("assembly: $message")
    }

    fun recordDuplicateAssembleSuppressed() {
        duplicateAssembleSuppressed.incrementAndGet()
    }

    fun recordOvershootTruncation(bytes: Int) {
        overshootTruncations.incrementAndGet()
        if (bytes > 0) noteError("overshoot: ${bytes}B")
    }

    fun recordCrcFailure(message: String? = null) {
        crcFailures.incrementAndGet()
        if (message != null) noteError("crc: $message")
    }

    fun recordTransferTimeout() {
        transferTimeouts.incrementAndGet()
        noteError("transfer timeout")
    }

    fun recordTransferStatusAbort() {
        transferStatusAborts.incrementAndGet()
    }

    fun recordOpsDropped(count: Long = 1L) {
        opsDroppedBackpressure.addAndGet(count)
    }

    fun noteError(message: String) {
        lastErrorText = message
        lastErrorTimeMs = System.currentTimeMillis()
    }

    // ── Snapshot publication ─────────────────────────────────────────────────

    private fun publishSnapshot(nowMs: Long) {
        val assembled = framesAssembled.get()
        val decoded = framesDecoded.get()

        // FPS = frames assembled na janela móvel.
        val framesInWindow: Int
        val avgFrameSize: Int
        synchronized(recentFramesLock) {
            while (recentFrames.isNotEmpty() && nowMs - recentFrames.first().timestampMs > FPS_WINDOW_MS) {
                recentFrames.removeFirst()
            }
            framesInWindow = recentFrames.size
            avgFrameSize = if (recentFrames.isEmpty()) 0
            else (recentFrames.sumOf { it.sizeBytes.toLong() } / recentFrames.size).toInt()
        }
        val windowSeconds = (FPS_WINDOW_MS / 1000f).coerceAtLeast(0.5f)
        val actualFps = framesInWindow / windowSeconds

        // Médias acumuladas globais
        val avgAssembleMs = if (assembled > 0) cumulativeAssembleMs.get().toFloat() / assembled else 0f
        val avgDecodeMs = if (decoded > 0) cumulativeDecodeMs.get().toFloat() / decoded else 0f

        val snap = CameraMetricsSnapshot(
            transport = transportLabelProvider(),
            targetFps = targetFpsProvider(),
            actualFps = actualFps,
            framesReceived = framesReceived.get(),
            framesAssembled = assembled,
            framesDecoded = decoded,
            framesDropped = framesDropped.get(),
            decodeFailures = decodeFailures.get(),
            assemblyErrors = assemblyErrors.get(),
            duplicateAssembleSuppressed = duplicateAssembleSuppressed.get(),
            overshootTruncations = overshootTruncations.get(),
            crcFailures = crcFailures.get(),
            transferTimeouts = transferTimeouts.get(),
            transferStatusAborts = transferStatusAborts.get(),
            opsDroppedBackpressure = opsDroppedBackpressure.get(),
            avgFrameSizeBytes = avgFrameSize,
            avgAssembleMs = avgAssembleMs,
            avgDecodeMs = avgDecodeMs,
            lastErrorMessage = lastErrorText,
            lastErrorTimeMs = lastErrorTimeMs,
            timestampMs = nowMs,
        )

        _snapshot.value = snap
        lastEmittedAtMs = nowMs

        if (logEverySecond && (assembled != lastEmittedAssembled || decoded != lastEmittedDecoded ||
                    framesInWindow > 0 || snap.hasErrorsOrDrops())) {
            Log.i(TAG, snap.toShortLine())
        }
        lastEmittedAssembled = assembled
        lastEmittedDecoded = decoded
    }

    /** Para testes deterministas: força um snapshot imediato sem esperar pelo job. */
    fun publishNow() = publishSnapshot(System.currentTimeMillis())
}


data class CameraMetricsSnapshot(
    val transport: String,                 // "BLE", "UDP", "NONE", ...
    val targetFps: Int,                    // 0 se desconhecido / não streaming
    val actualFps: Float,
    val framesReceived: Long,
    val framesAssembled: Long,
    val framesDecoded: Long,
    val framesDropped: Long,
    val decodeFailures: Long,
    val assemblyErrors: Long,
    val duplicateAssembleSuppressed: Long,
    val overshootTruncations: Long,
    val crcFailures: Long,
    val transferTimeouts: Long,
    val transferStatusAborts: Long,
    val opsDroppedBackpressure: Long,
    val avgFrameSizeBytes: Int,
    val avgAssembleMs: Float,
    val avgDecodeMs: Float,
    val lastErrorMessage: String?,
    val lastErrorTimeMs: Long?,
    val timestampMs: Long,
) {
    fun hasErrorsOrDrops(): Boolean =
        framesDropped > 0 || decodeFailures > 0 || assemblyErrors > 0 ||
            crcFailures > 0 || transferTimeouts > 0 || overshootTruncations > 0 ||
            opsDroppedBackpressure > 0

    /** Linha compacta para logcat 1×/s. */
    fun toShortLine(): String = buildString {
        append("t=$transport ")
        if (targetFps > 0) append("fps=${"%.1f".format(actualFps)}/$targetFps ")
        else append("fps=${"%.1f".format(actualFps)} ")
        append("asm=$framesAssembled dec=$framesDecoded ")
        if (framesDropped > 0) append("drop=$framesDropped ")
        if (decodeFailures > 0) append("decFail=$decodeFailures ")
        if (assemblyErrors > 0) append("asmErr=$assemblyErrors ")
        if (duplicateAssembleSuppressed > 0) append("dupSup=$duplicateAssembleSuppressed ")
        if (overshootTruncations > 0) append("ovsh=$overshootTruncations ")
        if (crcFailures > 0) append("crc=$crcFailures ")
        if (transferTimeouts > 0) append("toTx=$transferTimeouts ")
        if (transferStatusAborts > 0) append("staAb=$transferStatusAborts ")
        if (opsDroppedBackpressure > 0) append("opsDrp=$opsDroppedBackpressure ")
        if (avgFrameSizeBytes > 0) append("avgKB=${avgFrameSizeBytes / 1024} ")
        if (avgDecodeMs > 0f) append("decMs=${"%.0f".format(avgDecodeMs)} ")
        if (lastErrorMessage != null) append("lastErr=\"${lastErrorMessage.take(40)}\"")
    }.trim()

    companion object {
        val EMPTY = CameraMetricsSnapshot(
            transport = "NONE",
            targetFps = 0,
            actualFps = 0f,
            framesReceived = 0,
            framesAssembled = 0,
            framesDecoded = 0,
            framesDropped = 0,
            decodeFailures = 0,
            assemblyErrors = 0,
            duplicateAssembleSuppressed = 0,
            overshootTruncations = 0,
            crcFailures = 0,
            transferTimeouts = 0,
            transferStatusAborts = 0,
            opsDroppedBackpressure = 0,
            avgFrameSizeBytes = 0,
            avgAssembleMs = 0f,
            avgDecodeMs = 0f,
            lastErrorMessage = null,
            lastErrorTimeMs = null,
            timestampMs = 0L,
        )
    }
}
