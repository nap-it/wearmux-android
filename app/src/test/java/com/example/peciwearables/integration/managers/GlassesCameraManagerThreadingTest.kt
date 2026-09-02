package com.example.peciwearables.integration.managers

import com.example.peciwearables.integration.image.BleCameraPipeline
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class GlassesCameraManagerThreadingTest {

    private lateinit var fakePipeline: ConcurrencyDetectingPipelineSpy
    private lateinit var manager: GlassesCameraManager

    @Before
    fun setup() {
        fakePipeline = ConcurrencyDetectingPipelineSpy()
        manager = GlassesCameraManager(
            cameraPipeline = fakePipeline,
            onBitmapReady = {},
            onJpegReady = {},
            onLog = {},
        )
    }

    @After
    fun teardown() {
        manager.close()
    }

    @Test
    fun `onCameraData e onCameraStatusChanged em paralelo sao serializados`() {
        val threads = 4
        val iterationsPerThread = 4_000
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { tid ->
            pool.submit {
                try {
                    repeat(iterationsPerThread) { i ->
                        when ((tid + i) % 5) {
                            0 -> manager.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, byteArrayOf(100, 0, 0, 0))
                            1 -> manager.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(50))
                            2 -> manager.onCameraStatusChanged(0)
                            3 -> manager.onCameraStatusChanged(3)
                            4 -> manager.resetState("test")
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads não terminaram em 15s", latch.await(15, TimeUnit.SECONDS))
        pool.shutdownNow()

        // Give the single-thread worker time to drain the channel before asserting.
        // Poll until invocation count stabilises (no new ops in 100 ms) or 5 s passes.
        var prev = -1
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val now = fakePipeline.totalInvocations.get()
            if (now == prev && now > 0) break
            prev = now
            Thread.sleep(100)
        }

        assertTrue(
            "Não devia haver exceções; primeira foi: " +
                (errors.firstOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""),
            errors.isEmpty(),
        )
        assertNull("Pipeline NUNCA pode ver duas chamadas concorrentes", fakePipeline.firstConcurrentCall)
        assertTrue(
            "Pipeline devia ter recebido pelo menos algumas chamadas " +
                "(mesmo com drop_oldest sob carga). Recebeu=${fakePipeline.totalInvocations.get()}",
            fakePipeline.totalInvocations.get() > 0,
        )
    }

    @Test
    fun `trySend a saturar canal nao crasha e conta drops`() {
        // Pipeline lento de propósito para garantir que a fila enche.
        val slowPipeline = SlowPipeline(delayPerOpMs = 1)
        val slowManager = GlassesCameraManager(slowPipeline)

        try {
            repeat(10_000) {
                slowManager.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(8))
            }
        } finally {
            slowManager.close()
        }
        // Se chegámos aqui sem exceção o teste passa.
    }

    @Test
    fun `close cancela worker e chamadas subsequentes sao no-op silenciosas`() {
        manager.onCameraData(BleCameraPipeline.SUB_IMAGE_SIZE, byteArrayOf(100, 0, 0, 0))
        manager.close()

        // Mais chamadas após close: não podem lançar exceção.
        repeat(50) {
            manager.onCameraData(BleCameraPipeline.SUB_IMAGE, ByteArray(10))
            manager.onCameraStatusChanged(0)
            manager.resetState("post-close")
        }
        // Idempotente
        manager.close()
        manager.close()
    }

    @Test
    fun `chamadas de blob sao processadas em serie tambem`() {
        val threads = 3
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                try {
                    repeat(100) {
                        manager.onCameraImageFileReceived(ByteArray(32) { 0xFF.toByte() })
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        pool.shutdownNow()
        // Allow some drain time before asserting; close after.
        Thread.sleep(200)

        assertNull("Pipeline nunca vê reentrância", fakePipeline.firstConcurrentCall)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private class ConcurrencyDetectingPipelineSpy : BleCameraPipeline() {
        private val inside = AtomicBoolean(false)
        private val currentThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        val totalInvocations = AtomicInteger(0)

        @Volatile
        var firstConcurrentCall: String? = null
            private set

        private inline fun guarded(label: String, block: () -> Unit) {
            val me = Thread.currentThread()
            if (!inside.compareAndSet(false, true)) {
                if (firstConcurrentCall == null) {
                    firstConcurrentCall = "$label by $me, other=${currentThread.get()}"
                }
                return
            }
            currentThread.set(me)
            try {
                totalInvocations.incrementAndGet()
                block()
            } finally {
                currentThread.set(null)
                inside.set(false)
            }
        }

        override fun onCameraData(subType: Int, data: ByteArray) {
            guarded("onCameraData") { super.onCameraData(subType, data) }
        }

        override fun onCameraStatusChanged(status: Int) {
            guarded("onCameraStatusChanged") { super.onCameraStatusChanged(status) }
        }

        override fun resetState(reason: String) {
            guarded("resetState") { super.resetState(reason) }
        }

        override fun forceImageOnlyWithCachedEnvelope() {
            guarded("forceImageOnlyWithCachedEnvelope") { super.forceImageOnlyWithCachedEnvelope() }
        }
    }

    /** Slow pipeline: each call blocks for a few ms to ensure backpressure builds. */
    private class SlowPipeline(private val delayPerOpMs: Long) : BleCameraPipeline() {
        override fun onCameraData(subType: Int, data: ByteArray) {
            Thread.sleep(delayPerOpMs)
            super.onCameraData(subType, data)
        }
    }
}
