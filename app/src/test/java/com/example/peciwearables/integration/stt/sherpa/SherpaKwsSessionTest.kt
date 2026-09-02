package com.example.peciwearables.integration.stt.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SherpaKwsSessionTest {

    @Test
    fun `converts ShortArray to Float32 LE bytes correctly`() {
        val sent = mutableListOf<ByteArray>()
        val session = SherpaKwsSession(
            sendAudio = { sent.add(it) },
            chunkSamples = 4,
        )

        // Feed exactly one chunk worth of samples
        session.feedAudio(shortArrayOf(0, 16384, -16384, 32767))

        assertEquals(1, sent.size)
        val bytes = sent[0]
        assertEquals(16, bytes.size)  // 4 samples × 4 bytes each

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f,       buf.float, 0.0001f)
        assertEquals(0.5f,     buf.float, 0.0001f)  // 16384 / 32768
        assertEquals(-0.5f,    buf.float, 0.0001f)  // -16384 / 32768
        assertEquals(32767f / 32768f, buf.float, 0.0001f)
    }

    @Test
    fun `accumulates samples and sends only full chunks`() {
        val sent = mutableListOf<ByteArray>()
        val session = SherpaKwsSession(
            sendAudio = { sent.add(it) },
            chunkSamples = 4,
        )

        session.feedAudio(shortArrayOf(1, 2, 3))   // partial — no send
        assertEquals(0, sent.size)

        session.feedAudio(shortArrayOf(4, 5))  // total 5 = 1 full chunk + 1 leftover
        assertEquals(1, sent.size)  // only the first complete chunk

        session.flush()  // sends remaining 1 sample
        assertEquals(2, sent.size)
    }

    @Test
    fun `flush sends remaining samples`() {
        val sent = mutableListOf<ByteArray>()
        val session = SherpaKwsSession(
            sendAudio = { sent.add(it) },
            chunkSamples = 100,
        )

        session.feedAudio(shortArrayOf(1, 2, 3))
        assertEquals(0, sent.size)

        session.flush()
        assertEquals(1, sent.size)
        assertEquals(12, sent[0].size)  // 3 × 4 bytes
    }

    @Test
    fun `flush on empty accumulator sends nothing`() {
        val sent = mutableListOf<ByteArray>()
        val session = SherpaKwsSession(sendAudio = { sent.add(it) }, chunkSamples = 4)
        session.flush()
        assertTrue(sent.isEmpty())
    }
}
