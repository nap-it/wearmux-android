package com.example.peciwearables.integration.managers

import com.example.peciwearables.integration.audio.AudioPipeline
import com.example.peciwearables.integration.protocol.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesMicrophoneManagerTest {

    @Test
    fun `codec 21 maps to mulaw and stays decodable`() {
        val manager = GlassesMicrophoneManager(AudioPipeline())

        manager.updateCodec(21)
        val pcm = manager.decodeToPcm(byteArrayOf(1, 2, 3))

        assertEquals(AudioFrame.CODEC_MULAW, manager.currentCodecId)
        assertTrue(manager.canDecodeToPcmForStt())
        assertEquals(3, pcm.size)
    }

    @Test
    fun `codec 2 stays decodable as mulaw`() {
        val manager = GlassesMicrophoneManager(AudioPipeline())

        manager.updateCodec(2)
        val pcm = manager.decodeToPcm(byteArrayOf(0x7F, 0x00, 0x55))

        assertTrue(manager.canDecodeToPcmForStt())
        assertEquals(3, pcm.size)
    }

    @Test
    fun `codec 3 stays decodable as pcm16`() {
        val manager = GlassesMicrophoneManager(AudioPipeline())

        manager.updateCodec(3)
        val pcm = manager.decodeToPcm(byteArrayOf(0x34, 0x12, 0x78, 0x56))

        assertTrue(manager.canDecodeToPcmForStt())
        assertEquals(2, pcm.size)
        assertEquals(0x1234.toShort(), pcm[0])
        assertEquals(0x5678.toShort(), pcm[1])
    }

    // --- codec-gate tests ---

    @Test
    fun `onMicrophoneData drops frame before codec is known`() {
        // Arrange
        val pipeline = AudioPipeline()
        val manager = GlassesMicrophoneManager(pipeline)

        // Act
        manager.onMicrophoneData(byteArrayOf(1, 2, 3))

        // Assert
        assertEquals(0, pipeline.chunksEnqueued)
    }

    @Test
    fun `onMicrophoneData delivers frame after codec is known`() {
        // Arrange
        val pipeline = AudioPipeline()
        val manager = GlassesMicrophoneManager(pipeline)

        // Act
        manager.updateCodec(2)
        manager.onMicrophoneData(byteArrayOf(1, 2, 3))

        // Assert
        assertEquals(1, pipeline.chunksEnqueued)
    }

    @Test
    fun `onMicrophoneData drops frame after resetCodecGate and delivers after next updateCodec`() {
        // Arrange
        val pipeline = AudioPipeline()
        val manager = GlassesMicrophoneManager(pipeline)
        manager.updateCodec(2)
        manager.onMicrophoneData(byteArrayOf(1))    // enqueued → chunksEnqueued=1
        manager.resetCodecGate()

        // Act
        manager.onMicrophoneData(byteArrayOf(2))    // dropped
        manager.updateCodec(3)
        manager.onMicrophoneData(byteArrayOf(3, 4)) // enqueued → chunksEnqueued=2

        // Assert
        assertEquals(2, pipeline.chunksEnqueued)
    }

    @Test
    fun `isCodecKnown is false before updateCodec and true after`() {
        val manager = GlassesMicrophoneManager(AudioPipeline())

        assertFalse(manager.isCodecKnown)

        manager.updateCodec(2)

        assertTrue(manager.isCodecKnown)
    }

    @Test
    fun `isCodecKnown resets to false after resetCodecGate`() {
        val manager = GlassesMicrophoneManager(AudioPipeline())
        manager.updateCodec(2)

        manager.resetCodecGate()

        assertFalse(manager.isCodecKnown)
    }

    @Test
    fun `onMicrophoneData after updateCodec enqueues decoded chunk in pipeline`() {
        // Arrange
        val pipeline = AudioPipeline()
        val manager = GlassesMicrophoneManager(pipeline)
        manager.updateCodec(2) // mulaw

        // Act
        manager.onMicrophoneData(byteArrayOf(0x7F.toByte(), 0x00.toByte(), 0x55.toByte()))

        // Assert
        assertEquals(1, pipeline.chunksEnqueued)
    }

    @Test
    fun `onMicrophoneData before updateCodec does not enqueue`() {
        // Arrange
        val pipeline = AudioPipeline()
        val manager = GlassesMicrophoneManager(pipeline)

        // Act
        manager.onMicrophoneData(byteArrayOf(0x7F.toByte()))

        // Assert
        assertEquals(0, pipeline.chunksEnqueued)
    }
}
