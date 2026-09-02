package com.example.peciwearables.integration.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPipelineTest {

    @Test
    fun `applyFade leaves center samples unchanged and fades edges`() {
        // Arrange — chunk de 20ms a 16kHz = 320 samples (> 2 * fadeSamples=160)
        val totalSamples = 320
        val fadeSamples = 160 // 16000 * 0.01
        val samples = ShortArray(totalSamples) { 32767 }

        // Act
        AudioPipeline.applyFade(samples)

        // Assert — primeiro sample deve ser 0 (fade-in: i=0 → factor=0)
        assertEquals(0.toShort(), samples[0])
        // último sample deve ser 0 (fade-out: pos=0 no último índice)
        assertEquals(0.toShort(), samples[totalSamples - 1])
        // sample central (índice 160) está fora das zonas de fade → valor original
        val center = samples[totalSamples / 2]
        assertTrue("Center sample should be near 32767, was $center", center > 30000)
    }

    @Test
    fun `applyFade does nothing when chunk shorter than fade duration`() {
        // Arrange — chunk muito curto (menos de 2 * fadeSamples)
        val samples = ShortArray(10) { 32767 }

        // Act — não deve lançar exceção
        AudioPipeline.applyFade(samples)

        // Assert — amostras mantidas (sem crash)
        assertEquals(10, samples.size)
    }

    @Test
    fun `enqueueChunk increments chunk count`() {
        // Arrange
        val pipeline = AudioPipeline()

        // Act
        pipeline.enqueueChunk(ShortArray(160) { 100 }, sampleRateHz = 16_000)
        pipeline.enqueueChunk(ShortArray(160) { 200 }, sampleRateHz = 16_000)

        // Assert
        assertEquals(2, pipeline.chunksEnqueued)
    }

    @Test
    fun `clearQueue does not affect cumulative enqueued count`() {
        // Arrange
        val pipeline = AudioPipeline()
        pipeline.enqueueChunk(ShortArray(160) { 100 }, sampleRateHz = 16_000)

        // Act
        pipeline.clearQueue()

        // Assert — o contador histórico mantém-se; clearQueue só esvazia a fila interna
        assertEquals(1, pipeline.chunksEnqueued)
    }

    @Test
    fun `getStats contains enqueued and played counts`() {
        // Arrange
        val pipeline = AudioPipeline()
        pipeline.enqueueChunk(ShortArray(160) { 100 }, sampleRateHz = 16_000)

        // Act
        val stats = pipeline.getStats()

        // Assert
        assertTrue("Stats should contain enqueued=1, was: $stats", stats.contains("enqueued=1"))
        assertTrue("Stats should contain played=0, was: $stats", stats.contains("played=0"))
    }
}
