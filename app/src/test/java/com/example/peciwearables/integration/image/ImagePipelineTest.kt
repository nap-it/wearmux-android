package com.example.peciwearables.integration.image

import com.example.peciwearables.integration.protocol.ImageChunk
import com.example.peciwearables.integration.protocol.PacketHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ImagePipelineTest {

    @Test
    fun `reassembles chunks in chunk index order`() {
        val pipeline = ImagePipeline()
        val emittedJpegs = mutableListOf<ByteArray>()
        val jpegBytes = ByteArray(1024) { (it % 251).toByte() }

        pipeline.onJpegReady = { emittedJpegs.add(it) }

        chunksFor(frameId = 7L, bytes = jpegBytes, chunkSize = 200)
            .asReversed()
            .forEachIndexed { seq, chunk ->
                pipeline.onImageChunk(header(seq.toLong()), chunk)
            }

        assertEquals(1, emittedJpegs.size)
        assertArrayEquals(jpegBytes, emittedJpegs.single())
        assertTrue(pipeline.getStats().contains("pending=0"))
    }

    @Test
    fun `expires incomplete frames and reports missing chunks`() {
        var now = 1_000L
        val pipeline = ImagePipeline(clockMs = { now })
        val missingReports = mutableListOf<Pair<Long, List<Int>>>()
        val frame42 = chunksFor(frameId = 42L, bytes = ByteArray(9) { it.toByte() }, chunkSize = 3)
        val frame43 = chunksFor(frameId = 43L, bytes = byteArrayOf(9, 8, 7), chunkSize = 3)

        pipeline.onMissingChunks = { frameId, missing -> missingReports.add(frameId to missing) }
        pipeline.onImageChunk(header(seq = 1), frame42[0])

        now += 3_001L
        pipeline.onImageChunk(header(seq = 2), frame43[0])

        assertEquals(listOf(42L to listOf(1, 2)), missingReports)
        assertTrue(pipeline.getStats().contains("expired=1"))
        assertTrue(pipeline.getStats().contains("pending=0"))
    }

    @Test
    fun `accepts concurrent chunks without corrupting pending frames`() {
        val pipeline = ImagePipeline()
        val emittedJpegs = Collections.synchronizedList(mutableListOf<ByteArray>())
        val errors = ConcurrentLinkedQueue<Throwable>()
        val jpegBytes = ByteArray(4096) { (it % 199).toByte() }
        val chunks = chunksFor(frameId = 99L, bytes = jpegBytes, chunkSize = 64).shuffled(Random(1234))
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(chunks.size)

        pipeline.onJpegReady = { emittedJpegs.add(it) }

        chunks.forEachIndexed { seq, chunk ->
            executor.execute {
                try {
                    start.await()
                    pipeline.onImageChunk(header(seq.toLong()), chunk)
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("Timed out waiting for image chunks", done.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        if (errors.isNotEmpty()) {
            fail("Unexpected concurrent error: ${errors.first()}")
        }
        assertEquals(1, emittedJpegs.size)
        assertArrayEquals(jpegBytes, emittedJpegs.single())
        assertTrue(pipeline.getStats().contains("pending=0"))
    }

    private fun header(seq: Long = 0L): PacketHeader {
        return PacketHeader(
            version = 1,
            msgType = PacketHeader.MSG_TYPE_IMAGE,
            flags = 0,
            deviceId = 1uL,
            streamId = 2,
            seq = seq,
            timestampUs = 0L,
            payloadLen = 0,
        )
    }

    private fun chunksFor(frameId: Long, bytes: ByteArray, chunkSize: Int): List<ImageChunk> {
        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        return (0 until chunkCount).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, bytes.size)
            ImageChunk(
                imgFormat = ImageChunk.FORMAT_JPEG,
                width = 640,
                height = 480,
                frameId = frameId,
                chunkIdx = index,
                chunkCount = chunkCount,
                totalBytes = bytes.size,
                chunkBytes = bytes.copyOfRange(start, end),
            )
        }
    }
}
