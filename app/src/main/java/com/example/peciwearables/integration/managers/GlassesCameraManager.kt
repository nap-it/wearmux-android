package com.example.peciwearables.integration.managers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.peciwearables.integration.image.BleCameraPipeline
import com.example.peciwearables.integration.image.camera.CameraMetrics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GlassesCameraManager(
    private val cameraPipeline: BleCameraPipeline,
    private val onBitmapReady: (Bitmap) -> Unit = {},
    private val onJpegReady: (ByteArray) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val pipelineDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1),
    /** Métricas opcionais (Fase 3). Pode ser null em testes. */
    private val metrics: CameraMetrics? = null,
) {

    companion object {
        private const val TAG = "GlassesCameraManager"
        private const val MAX_PROBE_BUFFER_BYTES = 768 * 1024
        private const val OPS_CHANNEL_CAPACITY = 256
    }

    private val pipelineScope = CoroutineScope(SupervisorJob() + pipelineDispatcher)
    private val ops = Channel<Op>(
        capacity = OPS_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closed = AtomicBoolean(false)
    private val droppedOps = AtomicLong(0)

    /** Total operations dropped due to backpressure (capacity exceeded). */
    val droppedOpsTotal: Long get() = droppedOps.get()

    // ── Per-worker state (only touched on pipelineDispatcher) ──────────────────
    private val jpegProbeBuffer = java.io.ByteArrayOutputStream()
    private var jpegProbeBytesSinceReset = 0

    init {
        pipelineScope.launch {
            for (op in ops) {
                try {
                    handle(op)
                } catch (t: Throwable) {
                    // Defense in depth: never let a single op crash the worker.
                    Log.w(TAG, "Op ${op::class.simpleName} threw: ${t.message}", t)
                    onLog("Op ${op::class.simpleName} threw: ${t.message}")
                }
            }
        }
    }

    // ── Public API (callable from any thread; non-blocking) ───────────────────

    fun onCameraData(subType: Int, data: ByteArray) {
        post(Op.CameraData(subType, data))
    }

    fun onCameraStatusChanged(status: Int) {
        post(Op.CameraStatus(status))
    }

    fun resetState(reason: String) {
        post(Op.Reset(reason))
    }

    fun onCameraImageFileReceived(blob: ByteArray) {
        post(Op.Blob(blob))
    }

    fun statusName(status: Int): String = when (status) {
        0 -> "IDLE"
        1 -> "FOCUSING"
        2 -> "CAPTURING"
        3 -> "SLEEPING"
        else -> "UNKNOWN($status)"
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the channel makes the worker loop exit after draining whatever is
        // already buffered. We then cancel the scope to free the dispatcher.
        ops.close()
        pipelineScope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun post(op: Op) {
        if (closed.get()) return
        val result = ops.trySend(op)
        if (result.isFailure) {
            if (!closed.get()) {
                droppedOps.incrementAndGet()
                metrics?.recordOpsDropped()
            }
        }
    }

    private fun handle(op: Op) {
        when (op) {
            is Op.CameraData -> handleCameraData(op.subType, op.data)
            is Op.CameraStatus -> handleCameraStatus(op.status)
            is Op.Reset -> handleReset(op.reason)
            is Op.Blob -> parseCameraImageBlob(op.blob)
        }
    }

    private fun handleCameraData(subType: Int, data: ByteArray) {
        if (subType == BleCameraPipeline.SUB_IMAGE && data.isNotEmpty()) {
            jpegProbeBuffer.write(data)
            jpegProbeBytesSinceReset += data.size
            if (jpegProbeBytesSinceReset > MAX_PROBE_BUFFER_BYTES) {
                onLog("JPEG probe reset: ${jpegProbeBytesSinceReset / 1024}KB sem frame completo")
                jpegProbeBuffer.reset()
                jpegProbeBytesSinceReset = 0
                return
            }
            tryDecodeFromJpegMarkers(trigger = "SUB_IMAGE")
        }

        if (subType == BleCameraPipeline.SUB_IMAGE_SIZE && data.size >= 4) {
            val size = readUint32LE(data)
            onLog("Image expected: ${size / 1024}KB")
            if (size > 0 && jpegProbeBytesSinceReset > size * 2) {
                jpegProbeBuffer.reset()
                jpegProbeBytesSinceReset = 0
            }
        }
        if (subType == BleCameraPipeline.SUB_FOOTER) {
            onLog("Footer received - assembling image")
        }
        cameraPipeline.onCameraData(subType, data)
    }

    private fun handleCameraStatus(status: Int) {
        cameraPipeline.onCameraStatusChanged(status)
        if (status == 0) {
            tryDecodeFromJpegMarkers(trigger = "STATUS_IDLE", force = true)
            jpegProbeBuffer.reset()
            jpegProbeBytesSinceReset = 0
        }
    }

    private fun handleReset(reason: String) {
        jpegProbeBuffer.reset()
        jpegProbeBytesSinceReset = 0
        cameraPipeline.resetState(reason)
    }

    private fun parseCameraImageBlob(blob: ByteArray) {
        onLog("Blob received: ${blob.size}B - detecting format")

        var offset = 0
        var count = 0
        var sawImage = false
        var sawHeaderOrFooter = false

        // Attempt 1: TLV camera sub-messages.
        while (offset + 3 <= blob.size) {
            val subType = blob[offset].toInt() and 0xFF
            val length = (blob[offset + 1].toInt() and 0xFF) or
                ((blob[offset + 2].toInt() and 0xFF) shl 8)
            val dataStart = offset + 3
            val dataEnd = dataStart + length

            if (dataEnd > blob.size) {
                Log.w(
                    TAG,
                    "parseCameraImageBlob: truncated sub-msg offset=$offset subType=$subType length=$length blobSize=${blob.size}"
                )
                break
            }
            if (subType > 5) {
                Log.w(TAG, "parseCameraImageBlob: subType=$subType out-of-range, aborting TLV parse")
                count = 0
                break
            }

            val payload = blob.copyOfRange(dataStart, dataEnd)
            when (subType) {
                BleCameraPipeline.SUB_HEADER, BleCameraPipeline.SUB_FOOTER_SIZE, BleCameraPipeline.SUB_FOOTER -> {
                    sawHeaderOrFooter = true
                }
                BleCameraPipeline.SUB_IMAGE -> sawImage = true
            }
            cameraPipeline.onCameraData(subType, payload)
            offset = dataEnd
            count++
        }

        if (count > 0) {
            if (sawImage && !sawHeaderOrFooter) {
                onLog("TLV without header/footer - using cached envelope")
                cameraPipeline.forceImageOnlyWithCachedEnvelope()
            }
            onLog("TLV parsed: $count sub-messages")
            return
        }

        // Attempt 2: direct JPEG.
        onLog("No TLV found - trying direct JPEG decode (${blob.size}B)")
        val bitmap = BitmapFactory.decodeByteArray(blob, 0, blob.size)
        if (bitmap != null) {
            onLog("Direct JPEG decoded: ${bitmap.width}x${bitmap.height}px")
            onJpegReady(blob)
            onBitmapReady(bitmap)
        } else {
            val preview = blob.take(16).joinToString(" ") { "0x%02X".format(it) }
            onLog("JPEG decode failed (${blob.size}B), preview=$preview")
        }
    }

    private fun tryDecodeFromJpegMarkers(trigger: String, force: Boolean = false) {
        val bytes = jpegProbeBuffer.toByteArray()
        if (bytes.size < 32) return

        val soi = findMarker(bytes, 0xFF.toByte(), 0xD8.toByte(), 0)
        if (soi < 0) return
        val eoi = findMarker(bytes, 0xFF.toByte(), 0xD9.toByte(), soi + 2)

        if (eoi < 0) {
            if (force) {
                onLog("JPEG marker fallback: SOI sem EOI ($trigger)")
            }
            return
        }

        val jpeg = bytes.copyOfRange(soi, eoi + 2)
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (bitmap != null) {
            onLog("JPEG marker fallback OK: ${bitmap.width}x${bitmap.height}px ($trigger)")
            onJpegReady(jpeg)
            onBitmapReady(bitmap)
            jpegProbeBuffer.reset()
            jpegProbeBytesSinceReset = 0
        } else if (force) {
            onLog("JPEG marker fallback falhou decode ($trigger)")
        }
    }

    private fun findMarker(data: ByteArray, b0: Byte, b1: Byte, start: Int): Int {
        var i = start.coerceAtLeast(0)
        while (i + 1 < data.size) {
            if (data[i] == b0 && data[i + 1] == b1) return i
            i++
        }
        return -1
    }

    private fun readUint32LE(data: ByteArray): Int {
        if (data.size < 4) return -1
        return (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
    }

    /** Serialized work items processed by the single-thread worker. */
    private sealed interface Op {
        data class CameraData(val subType: Int, val data: ByteArray) : Op
        data class CameraStatus(val status: Int) : Op
        data class Reset(val reason: String) : Op
        data class Blob(val blob: ByteArray) : Op
    }
}
