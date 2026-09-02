package com.example.peciwearables.integration.stt.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLiveSttClientParseTest {

    @Test
    fun `parseSegments returns empty list for SERVER_READY message`() {
        val json = """{"uid":"abc","message":"SERVER_READY","backend":"faster_whisper"}"""
        val result = WhisperLiveSttClient.parseSegments(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseSegments returns segments from transcription response`() {
        val json = """
            {
              "uid": "abc",
              "segments": [
                {"start": "0.000", "end": "2.340", "text": "Ola mundo", "completed": true},
                {"start": 2.340, "end": 3.000, "text": "como estas", "completed": false}
              ]
            }
        """.trimIndent()

        val result = WhisperLiveSttClient.parseSegments(json)

        assertEquals(2, result.size)
        assertEquals("Ola mundo", result[0].text)
        assertEquals(0.0f, result[0].start, 0.001f)
        assertEquals(2.34f, result[0].end, 0.001f)
        assertTrue(result[0].completed)
        assertEquals("como estas", result[1].text)
        assertEquals(2.34f, result[1].start, 0.001f)
        assertEquals(3.0f, result[1].end, 0.001f)
        assertTrue(!result[1].completed)
    }

    @Test
    fun `parseSegments returns empty list when segments key absent`() {
        val json = """{"uid":"abc","language":"pt","language_prob":0.95}"""
        val result = WhisperLiveSttClient.parseSegments(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `isServerReady returns true for SERVER_READY message`() {
        val json = """{"uid":"abc","message":"SERVER_READY","backend":"faster_whisper"}"""
        assertTrue(WhisperLiveSttClient.isServerReady(json))
    }
}
