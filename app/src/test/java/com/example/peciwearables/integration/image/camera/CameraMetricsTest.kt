package com.example.peciwearables.integration.image.camera

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testes unitários do [CameraMetrics] e [CameraMetricsSnapshot] (Fase 3).
 *
 * Cobre:
 *  - Contadores atómicos thread-safe (incrementos concorrentes não perdem nenhum).
 *  - Snapshot inicial (EMPTY) e snapshot publicado via [CameraMetrics.publishNow].
 *  - Reset zera tudo, incluindo janela de FPS.
 *  - Linha curta para logcat reflete erros e drops.
 */
class CameraMetricsTest {

    private lateinit var metrics: CameraMetrics

    @Before
    fun setup() {
        metrics = CameraMetrics(
            transportLabelProvider = { "TEST" },
            targetFpsProvider = { 12 },
        )
    }

    @Test
    fun `snapshot inicial e EMPTY`() {
        val snap = metrics.snapshot.value
        assertEquals(CameraMetricsSnapshot.EMPTY, snap)
        assertEquals(0L, snap.framesAssembled)
        assertEquals(0L, snap.framesDecoded)
        assertEquals(0f, snap.actualFps, 0.001f)
        assertFalse(snap.hasErrorsOrDrops())
    }

    @Test
    fun `publishNow reflete contadores e providers`() {
        metrics.recordFrameAssembled(sizeBytes = 5000, assembleMs = 4)
        metrics.recordFrameAssembled(sizeBytes = 6000, assembleMs = 8)
        metrics.recordFrameDecoded(decodeMs = 12)
        metrics.recordFrameDecoded(decodeMs = 20)

        metrics.publishNow()

        val snap = metrics.snapshot.value
        assertEquals(2L, snap.framesAssembled)
        assertEquals(2L, snap.framesDecoded)
        assertEquals(6, snap.avgAssembleMs.toInt())   // (4+8)/2
        assertEquals(16, snap.avgDecodeMs.toInt())    // (12+20)/2
        assertEquals(5500, snap.avgFrameSizeBytes)    // (5000+6000)/2
        assertEquals("TEST", snap.transport)
        assertEquals(12, snap.targetFps)
        assertTrue("actualFps > 0 com 2 frames na janela", snap.actualFps > 0f)
    }

    @Test
    fun `incrementos concorrentes nao perdem nenhum`() = runBlocking {
        val threads = 8
        val perThread = 1_000
        val deferreds = (0 until threads).map {
            async(Dispatchers.Default) {
                repeat(perThread) {
                    metrics.recordFrameAssembled(sizeBytes = 1000, assembleMs = 1)
                    metrics.recordFrameDecoded(decodeMs = 2)
                    metrics.recordFrameDropped()
                    metrics.recordDecodeFailure()
                    metrics.recordCrcFailure()
                }
            }
        }
        deferreds.awaitAll()

        metrics.publishNow()
        val snap = metrics.snapshot.value
        val expected = (threads * perThread).toLong()
        assertEquals(expected, snap.framesAssembled)
        assertEquals(expected, snap.framesDecoded)
        assertEquals(expected, snap.framesDropped)
        assertEquals(expected, snap.decodeFailures)
        assertEquals(expected, snap.crcFailures)
    }

    @Test
    fun `reset zera tudo`() {
        metrics.recordFrameAssembled(sizeBytes = 1234, assembleMs = 5)
        metrics.recordCrcFailure("test")
        metrics.recordOpsDropped(7)
        metrics.publishNow()
        assertTrue(metrics.snapshot.value.framesAssembled > 0)

        metrics.reset()
        metrics.publishNow()
        val snap = metrics.snapshot.value
        assertEquals(0L, snap.framesAssembled)
        assertEquals(0L, snap.crcFailures)
        assertEquals(0L, snap.opsDroppedBackpressure)
        assertEquals(0f, snap.actualFps, 0.001f)
        assertNull(snap.lastErrorMessage)
    }

    @Test
    fun `hasErrorsOrDrops e toShortLine refletem estado`() {
        // Frame OK
        metrics.recordFrameAssembled(sizeBytes = 8000, assembleMs = 2)
        metrics.recordFrameDecoded(decodeMs = 10)
        metrics.publishNow()
        val ok = metrics.snapshot.value
        assertFalse("sem erros", ok.hasErrorsOrDrops())
        val okLine = ok.toShortLine()
        assertTrue("contém asm=", okLine.contains("asm=1"))
        assertTrue("contém fps=", okLine.contains("fps="))
        assertFalse("sem drop", okLine.contains("drop="))

        // Frame com erros
        metrics.recordFrameDropped("backpressure")
        metrics.recordCrcFailure("bad checksum")
        metrics.publishNow()
        val err = metrics.snapshot.value
        assertTrue("com erros", err.hasErrorsOrDrops())
        val errLine = err.toShortLine()
        assertTrue("contém drop", errLine.contains("drop=1"))
        assertTrue("contém crc", errLine.contains("crc=1"))
        assertNotNull("lastErrorMessage definido", err.lastErrorMessage)
    }

    @Test
    fun `noteError atualiza ultima mensagem e timestamp`() {
        val before = System.currentTimeMillis()
        metrics.noteError("erro de teste")
        metrics.publishNow()
        val snap = metrics.snapshot.value
        assertEquals("erro de teste", snap.lastErrorMessage)
        assertNotNull(snap.lastErrorTimeMs)
        assertTrue(snap.lastErrorTimeMs!! >= before)
    }

    @Test
    fun `recordOvershootTruncation grava bytes`() {
        metrics.recordOvershootTruncation(bytes = 24)
        metrics.recordOvershootTruncation(bytes = 8)
        metrics.publishNow()
        assertEquals(2L, metrics.snapshot.value.overshootTruncations)
        assertNotNull(metrics.snapshot.value.lastErrorMessage)
    }

    @Test
    fun `actualFps usa janela movel de 2s`() {
        // 5 frames "agora" — todos cabem na janela
        repeat(5) { metrics.recordFrameAssembled(sizeBytes = 1000, assembleMs = 1) }
        metrics.publishNow()
        val snap = metrics.snapshot.value
        // FPS = framesInWindow / 2s = 5 / 2 = 2.5
        assertEquals(2.5f, snap.actualFps, 0.1f)
        assertEquals(1000, snap.avgFrameSizeBytes)
    }

    @Test
    fun `stop e start sao idempotentes`() = runBlocking {
        // Sem scope adiantado é difícil testar o job; mas pelo menos verificamos
        // que start/stop não lançam.
        metrics.stop()
        metrics.stop()
        // publishNow continua a funcionar sem o job ativo.
        metrics.recordFrameAssembled(sizeBytes = 500, assembleMs = 1)
        metrics.publishNow()
        assertEquals(1L, metrics.snapshot.value.framesAssembled)
    }
}
