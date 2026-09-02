package com.example.peciwearables.integration.stt.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperLiveSttSessionTest {
    private val sentFrames = mutableListOf<ByteArray>()

    private fun makeSession(chunkSamples: Int = 4): WhisperLiveSttSession {
        sentFrames.clear()
        return WhisperLiveSttSession(
            sendAudio = { frame -> sentFrames += frame },
            chunkSamples = chunkSamples,
        )
    }

    @Test
    fun `does not send until chunk is full`() {
        val session = makeSession(chunkSamples = 4)
        session.feedAudio(ShortArray(3) { 1000 })
        assertTrue(sentFrames.isEmpty())
    }

    @Test
    fun `sends exactly one frame when chunk is filled`() {
        val session = makeSession(chunkSamples = 4)
        session.feedAudio(ShortArray(4) { 1000 })
        assertEquals(1, sentFrames.size)
    }

    @Test
    fun `sent frame has correct byte size`() {
        val session = makeSession(chunkSamples = 4)
        session.feedAudio(ShortArray(4) { 0 })
        assertEquals(16, sentFrames[0].size)
    }

    @Test
    fun `converts PCM16 to float32 correctly`() {
        val session = makeSession(chunkSamples = 1)
        session.feedAudio(shortArrayOf(16384))
        val value = ByteBuffer.wrap(sentFrames[0]).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(0.5f, value, 0.0001f)
    }

    @Test
    fun `negative PCM16 converts to negative float32`() {
        val session = makeSession(chunkSamples = 1)
        session.feedAudio(shortArrayOf(-16384))
        val value = ByteBuffer.wrap(sentFrames[0]).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(-0.5f, value, 0.0001f)
    }

    @Test
    fun `flush sends remaining samples below chunk threshold`() {
        val session = makeSession(chunkSamples = 4)
        session.feedAudio(ShortArray(3) { 1000 })
        session.flush()
        assertEquals(1, sentFrames.size)
        assertEquals(12, sentFrames[0].size)
    }

    @Test
    fun `flush on empty buffer sends nothing`() {
        val session = makeSession(chunkSamples = 4)
        session.flush()
        assertTrue(sentFrames.isEmpty())
    }

    @Test
    fun `sends two frames when double chunk size fed`() {
        val session = makeSession(chunkSamples = 4)
        session.feedAudio(ShortArray(8) { 500 })
        assertEquals(2, sentFrames.size)
    }
}
