package com.example.peciwearables.integration.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import com.example.peciwearables.integration.image.camera.CameraMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch


@Suppress("KDocUnresolvedReference")
open class BleCameraPipeline(
    /** Métricas opcionais agregadas (Fase 3). Pode ser null em testes. */
    private val metrics: CameraMetrics? = null,
) {

    companion object {
        private const val TAG = "BleCameraPipeline"
        private const val OUTPUT_ROTATION_DEGREES = 180f
        private const val MAX_HEADER_OR_FOOTER_SIZE_BYTES = 4 * 1024
        private const val MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024

        const val SUB_HEADER_SIZE = 0
        const val SUB_HEADER = 1
        const val SUB_IMAGE_SIZE = 2
        const val SUB_IMAGE = 3
        const val SUB_FOOTER_SIZE = 4
        const val SUB_FOOTER = 5
    }

    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rotationMatrix = Matrix().apply { postRotate(OUTPUT_ROTATION_DEGREES) }
    private val decodeOptions = BitmapFactory.Options().apply { inMutable = true }

    private val _latestBitmap = MutableStateFlow<Bitmap?>(null)
    val latestBitmap: StateFlow<Bitmap?> = _latestBitmap

    var onJpegReady: ((ByteArray) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val headerBuffer = java.io.ByteArrayOutputStream()
    private val imageBuffer = java.io.ByteArrayOutputStream()
    private val footerBuffer = java.io.ByteArrayOutputStream()

    private var cachedHeader: ByteArray? = null
    private var cachedFooter: ByteArray? = null

    private var expectedHeaderSize = -1
    private var expectedImageSize = -1
    private var expectedFooterSize = -1

    private var imagesAssembled = 0L
    private var assemblyErrors = 0L
    private var duplicateAssembleSuppressed = 0L
    private var overshootTruncations = 0L


    private var didBuildImage: Boolean = false

   
    open fun forceImageOnlyWithCachedEnvelope() {
        if (expectedImageSize < 0) return
        if (didBuildImage) return

        if (expectedHeaderSize < 0) {
            val header = cachedHeader
            if (header != null && header.isNotEmpty()) {
                expectedHeaderSize = header.size
                headerBuffer.reset()
                headerBuffer.write(header)
                log("reused cached header (${header.size}B)")
            } else {
                expectedHeaderSize = 0
                log("no cached header available; assuming image-only payload")
            }
        }

        if (expectedFooterSize < 0) {
            val footer = cachedFooter
            if (footer != null && footer.isNotEmpty()) {
                expectedFooterSize = footer.size
                footerBuffer.reset()
                footerBuffer.write(footer)
                log("reused cached footer (${footer.size}B)")
            } else {
                expectedFooterSize = 0
                log("no cached footer available; assuming image-only payload")
            }
        }

        checkAndAssemble("IMAGE_ONLY_HINT")
    }

    open fun onCameraData(subType: Int, data: ByteArray) {
        when (subType) {
            SUB_HEADER_SIZE -> {
                val size = readSizeForHeaderOrFooter(data)
                if (!isValidHeaderOrFooterSize(size)) {
                    warn("Invalid headerSize=$size (${data.size}B) - resetting assembly")
                    resetAssembly()
                    return
                }
                expectedHeaderSize = size
                headerBuffer.reset()
                log("headerSize=$expectedHeaderSize")
                checkAndAssemble("HEADER_SIZE")
            }

            SUB_HEADER -> {
                headerBuffer.write(data)
                checkAndAssemble("HEADER")
            }

            SUB_IMAGE_SIZE -> {
                val size = readUint32LE(data)
                if (!isValidImageSize(size)) {
                    warn("Invalid imageSize=$size (${data.size}B) - resetting assembly")
                    resetAssembly()
                    return
                }
                expectedImageSize = size
                imageBuffer.reset()
                // New frame starting: clear the build-image guard so the next assemble can fire.
                // Mirrors SDK CameraManager.ts:303-304.
                didBuildImage = false
                log("imageSize=$expectedImageSize")
            }

            SUB_IMAGE -> {
                imageBuffer.write(data)
                checkAndAssemble("IMAGE")
            }

            SUB_FOOTER_SIZE -> {
                val size = readSizeForHeaderOrFooter(data)
                if (!isValidHeaderOrFooterSize(size)) {
                    warn("Invalid footerSize=$size (${data.size}B) - resetting assembly")
                    resetAssembly()
                    return
                }
                expectedFooterSize = size
                footerBuffer.reset()
                log("footerSize=$expectedFooterSize")
                checkAndAssemble("FOOTER_SIZE")
            }

            SUB_FOOTER -> {
                footerBuffer.write(data)
                checkAndAssemble("FOOTER")
            }

            else -> {
                warn("Unknown camera subType=$subType - resetting assembly")
                resetAssembly()
            }
        }
    }

    open fun onCameraStatusChanged(status: Int) {
        Log.d(TAG, "Camera status changed: $status")
        if (status == 0 || status == 3) {
            if (!didBuildImage && expectedImageSize > 0 && imageBuffer.size() > 0) {
                checkAndAssemble("STATUS_$status")
            } else if (didBuildImage) {
                duplicateAssembleSuppressed++
                metrics?.recordDuplicateAssembleSuppressed()
                // No work to do — frame already published; reset state for the next capture.
                resetAssembly()
            } else if (expectedImageSize < 0) {
                resetAssembly()
            }
        }
    }

    open fun resetState(reason: String) {
        resetAssembly()
        log("assembly reset ($reason)")
    }

    private fun checkAndAssemble(trigger: String) {
        if (expectedImageSize < 0) return
        // Guard against duplicate emission (B1+B11). Mirrors SDK CameraManager.ts:200-206.
        if (didBuildImage) {
            duplicateAssembleSuppressed++
            metrics?.recordDuplicateAssembleSuppressed()
            return
        }

        if (expectedHeaderSize < 0 && imageBuffer.size() > 0) {
            val header = cachedHeader
            if (header != null && header.isNotEmpty()) {
                expectedHeaderSize = header.size
                if (headerBuffer.size() == 0) headerBuffer.write(header)
            } else {
                expectedHeaderSize = 0
            }
        }

        if (expectedFooterSize < 0 && imageBuffer.size() > 0) {
            val footer = cachedFooter
            if (footer != null && footer.isNotEmpty()) {
                expectedFooterSize = footer.size
                if (footerBuffer.size() == 0) footerBuffer.write(footer)
            } else {
                expectedFooterSize = 0
            }
        }

        val headerDone = when {
            expectedHeaderSize == 0 -> true
            expectedHeaderSize > 0 -> headerBuffer.size() >= expectedHeaderSize
            else -> false
        }
        val imageDone = expectedImageSize > 0 && imageBuffer.size() >= expectedImageSize
        val footerDone = when {
            expectedFooterSize == 0 -> true
            expectedFooterSize > 0 -> footerBuffer.size() >= expectedFooterSize
            else -> false
        }

        if (headerDone && imageDone && footerDone) {
            log(
                "image ready ($trigger): " +
                    "header=${headerBuffer.size()}B image=${imageBuffer.size()}B footer=${footerBuffer.size()}B"
            )
            tryAssemble()
        }
    }

    private fun tryAssemble() {
        if (expectedImageSize <= 0) {
            assemblyErrors++
            metrics?.recordAssemblyError("expectedImageSize<=0")
            warn("tryAssemble aborted: expectedImageSize=$expectedImageSize (likely concurrent reset)")
            return
        }
        if (expectedHeaderSize < 0 || expectedFooterSize < 0) {
            assemblyErrors++
            metrics?.recordAssemblyError("header/footer size not resolved")
            warn(
                "tryAssemble aborted: header/footer size not resolved " +
                    "(header=$expectedHeaderSize, footer=$expectedFooterSize)"
            )
            return
        }

        val assembleStartMs = System.currentTimeMillis()

        // Cache header/footer for firmware variants that omit them on subsequent frames.
        // Truncate to expected size (defensive: BLE could deliver one byte extra).
        val headerBytes = if (expectedHeaderSize > 0) {
            val raw = headerBuffer.toByteArray()
            if (raw.size > expectedHeaderSize) {
                overshootTruncations++
                metrics?.recordOvershootTruncation(raw.size - expectedHeaderSize)
                warn("header overshoot: got ${raw.size}B, expected ${expectedHeaderSize}B - truncating")
                raw.copyOf(expectedHeaderSize)
            } else raw
        } else ByteArray(0)
        if (headerBytes.isNotEmpty()) cachedHeader = headerBytes

        val imageBytes = run {
            val raw = imageBuffer.toByteArray()
            if (raw.size > expectedImageSize) {
                overshootTruncations++
                metrics?.recordOvershootTruncation(raw.size - expectedImageSize)
                warn("image overshoot: got ${raw.size}B, expected ${expectedImageSize}B - truncating")
                raw.copyOf(expectedImageSize)
            } else raw
        }

        val footerBytes = if (expectedFooterSize > 0) {
            val raw = footerBuffer.toByteArray()
            if (raw.size > expectedFooterSize) {
                overshootTruncations++
                metrics?.recordOvershootTruncation(raw.size - expectedFooterSize)
                warn("footer overshoot: got ${raw.size}B, expected ${expectedFooterSize}B - truncating")
                raw.copyOf(expectedFooterSize)
            } else raw
        } else ByteArray(0)
        if (footerBytes.isNotEmpty()) cachedFooter = footerBytes

        val totalSize = headerBytes.size + imageBytes.size + footerBytes.size
        val jpegStream = java.io.ByteArrayOutputStream(totalSize)
        jpegStream.write(headerBytes)
        jpegStream.write(imageBytes)
        jpegStream.write(footerBytes)
        val jpeg = jpegStream.toByteArray()

        // Mark BEFORE reset so any concurrent STATUS callback sees the guard.
        didBuildImage = true
        resetAssembly(preserveDidBuildImage = true)

        val assembleMs = System.currentTimeMillis() - assembleStartMs
        metrics?.recordFrameAssembled(sizeBytes = jpeg.size, assembleMs = assembleMs)

        onJpegReady?.invoke(jpeg)

        decodeScope.launch {
            val decodeStartMs = System.currentTimeMillis()
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions)
                if (bitmap != null) {
                    val rotated = if (OUTPUT_ROTATION_DEGREES != 0f) {
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
                    } else {
                        bitmap
                    }
                    if (rotated !== bitmap) {
                        bitmap.recycle()
                    }
                    _latestBitmap.value = rotated
                    imagesAssembled++
                    metrics?.recordFrameDecoded(decodeMs = System.currentTimeMillis() - decodeStartMs)
                } else {
                    assemblyErrors++
                    val preview = jpeg.take(16).joinToString(" ") { "0x%02X".format(it) }
                    metrics?.recordDecodeFailure("null bitmap (${jpeg.size}B)")
                    warn("Decode failed (${jpeg.size}B) preview=$preview")
                }
            } catch (e: Exception) {
                assemblyErrors++
                metrics?.recordDecodeFailure(e.message)
                warn("Assemble error: ${e.message}")
            }
        }
    }

    private fun resetAssembly(preserveDidBuildImage: Boolean = false) {
        headerBuffer.reset()
        imageBuffer.reset()
        footerBuffer.reset()
        expectedHeaderSize = -1
        expectedImageSize = -1
        expectedFooterSize = -1
        if (!preserveDidBuildImage) {
            didBuildImage = false
        }
    }

    private fun readUint32LE(data: ByteArray): Int {
        if (data.size < 4) return -1
        return (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
    }

    private fun isValidHeaderOrFooterSize(size: Int): Boolean =
        size in 0..MAX_HEADER_OR_FOOTER_SIZE_BYTES

    private fun isValidImageSize(size: Int): Boolean =
        size in 1..MAX_IMAGE_SIZE_BYTES

    /**
     * Some firmware variants send header/footer size as uint16 LE; others as uint32 LE.
     */
    private fun readSizeForHeaderOrFooter(data: ByteArray): Int {
        return when {
            data.size >= 4 -> readUint32LE(data)
            data.size >= 2 -> (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            else -> -1
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun warn(msg: String) {
        Log.w(TAG, msg)
        onLog?.invoke("WARN: $msg")
    }

    fun close() {
        decodeScope.cancel()
    }

    fun getStats(): String =
        "BLECamera: assembled=$imagesAssembled, errors=$assemblyErrors, " +
            "dupSuppressed=$duplicateAssembleSuppressed, truncations=$overshootTruncations"

    /** For tests / metrics integration. */
    fun statsSnapshot(): Stats = Stats(
        imagesAssembled = imagesAssembled,
        assemblyErrors = assemblyErrors,
        duplicateAssembleSuppressed = duplicateAssembleSuppressed,
        overshootTruncations = overshootTruncations,
    )

    data class Stats(
        val imagesAssembled: Long,
        val assemblyErrors: Long,
        val duplicateAssembleSuppressed: Long,
        val overshootTruncations: Long,
    )
}
