package com.example.peciwearables.integration.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.peciwearables.integration.protocol.ImageChunk
import com.example.peciwearables.integration.protocol.PacketHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Image pipeline: JPEG chunk reassembly and decode.
 */
class ImagePipeline(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        private const val TAG = "ImagePipeline"
        private const val FRAME_TIMEOUT_MS = 3000L
        private const val MAX_PENDING_FRAMES = 3
    }

    private data class PendingFrame(
        val frameId: Long,
        val totalBytes: Int,
        val chunkCount: Int,
        val chunks: MutableMap<Int, ByteArray>,
        val startTimeMs: Long,
        var width: Int = 0,
        var height: Int = 0
    ) {
        val isComplete: Boolean get() = chunks.size == chunkCount

        fun missingChunks(): List<Int> {
            return (0 until chunkCount).filter { it !in chunks }
        }

        fun reassemble(): ByteArray {
            val result = ByteArray(totalBytes)
            var offset = 0
            for (i in 0 until chunkCount) {
                val chunk = chunks[i] ?: continue
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        }
    }

    private data class CompletedFrame(
        val frameId: Long,
        val jpegBytes: ByteArray,
    )

    private data class ExpiredFrame(
        val frameId: Long,
        val missingChunks: List<Int>,
    )

    private val pendingFramesLock = Any()
    private val pendingFrames = mutableMapOf<Long, PendingFrame>()

    private val _latestBitmap = MutableStateFlow<Bitmap?>(null)
    val latestBitmap: StateFlow<Bitmap?> = _latestBitmap

    var onJpegReady: ((ByteArray) -> Unit)? = null

    var onMissingChunks: ((frameId: Long, missingChunks: List<Int>) -> Unit)? = null

    private var framesCompleted = 0L
    private var framesExpired = 0L

    @Suppress("UNUSED_PARAMETER")
    fun onImageChunk(header: PacketHeader, chunk: ImageChunk) {
        val expiredFrames: List<ExpiredFrame>
        val completedFrame: CompletedFrame?

        synchronized(pendingFramesLock) {
            expiredFrames = cleanupExpiredFramesLocked(clockMs())
            completedFrame = addChunkLocked(chunk)
        }

        expiredFrames.forEach { expired ->
            if (expired.missingChunks.isNotEmpty()) {
                Log.w(TAG, "Frame ${expired.frameId} expired with ${expired.missingChunks.size} missing chunks")
                onMissingChunks?.invoke(expired.frameId, expired.missingChunks)
            }
        }
        completedFrame?.let { decodeFrame(it) }
    }

    private fun decodeFrame(frame: CompletedFrame) {
        try {
            onJpegReady?.invoke(frame.jpegBytes)
            val bitmap = BitmapFactory.decodeByteArray(frame.jpegBytes, 0, frame.jpegBytes.size)

            if (bitmap != null) {
                _latestBitmap.value = bitmap
                synchronized(pendingFramesLock) {
                    framesCompleted++
                }
                Log.d(TAG, "Frame ${frame.frameId} decoded: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.w(TAG, "Failed to decode frame ${frame.frameId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error for frame ${frame.frameId}: ${e.message}")
        }
    }

    private fun cleanupExpiredFramesLocked(now: Long): List<ExpiredFrame> {
        val expired = pendingFrames.values.filter { frame ->
            now - frame.startTimeMs > FRAME_TIMEOUT_MS
        }

        return expired.map { frame ->
            pendingFrames.remove(frame.frameId)
            framesExpired++
            ExpiredFrame(frame.frameId, frame.missingChunks())
        }
    }

    private fun addChunkLocked(chunk: ImageChunk): CompletedFrame? {
        val pending = pendingFrames.getOrPut(chunk.frameId) {
            if (pendingFrames.size >= MAX_PENDING_FRAMES) {
                val oldest = pendingFrames.keys.min()
                pendingFrames.remove(oldest)
                framesExpired++
            }

            PendingFrame(
                frameId = chunk.frameId,
                totalBytes = chunk.totalBytes,
                chunkCount = chunk.chunkCount,
                chunks = mutableMapOf(),
                startTimeMs = clockMs(),
                width = chunk.width,
                height = chunk.height
            )
        }

        pending.chunks[chunk.chunkIdx] = chunk.chunkBytes

        if (!pending.isComplete) return null
        pendingFrames.remove(chunk.frameId)
        return CompletedFrame(
            frameId = pending.frameId,
            jpegBytes = pending.reassemble(),
        )
    }

    fun getStats(): String {
        return synchronized(pendingFramesLock) {
            "Image: completed=$framesCompleted, expired=$framesExpired, " +
                    "pending=${pendingFrames.size}"
        }
    }
}
