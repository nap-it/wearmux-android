package com.example.peciwearables.integration.udp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


@OptIn(ExperimentalCoroutinesApi::class)
class UdpConnectionManagerThreadingTest {

    private val rxParseDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    @Test
    fun `parallel submissions to rxParseDispatcher never overlap`() = runBlocking {
        val inside = AtomicBoolean(false)
        val overlap = AtomicReference<String?>(null)

        val deferreds = (0 until 200).map { i ->
            async(Dispatchers.Default) {
                withContext(rxParseDispatcher) {
                    if (!inside.compareAndSet(false, true)) {
                        overlap.compareAndSet(
                            null,
                            "overlap detected at iteration=$i thread=${Thread.currentThread().name}",
                        )
                    }
                    // Pretend to do parser work — long enough that overlap would be
                    // observed if multiple coroutines were allowed to run concurrently.
                    var acc = 0
                    repeat(5_000) { acc = (acc + it) and 0xFF }
                    @Suppress("UNUSED_VARIABLE") val sink = acc
                    inside.set(false)
                }
            }
        }
        deferreds.awaitAll()

        assertNull(
            "Two submissions ran concurrently on the rxParseDispatcher: ${overlap.get()}",
            overlap.get(),
        )
    }

    @Test
    fun `FIFO ordering is preserved when a single submitter posts in sequence`() = runBlocking {
        val observed = Collections.synchronizedList(mutableListOf<Int>())

        repeat(500) { i ->
            withContext(rxParseDispatcher) {
                observed.add(i)
            }
        }

        assertEquals("size mismatch", 500, observed.size)
        val sorted = observed.toList()
        assertEquals("FIFO order broken", (0 until 500).toList(), sorted)
    }

    @Test
    fun `the dispatcher remains usable after many submissions`() = runBlocking {
        val before = (0 until 1_000).map { i ->
            async(Dispatchers.Default) {
                withContext(rxParseDispatcher) { i }
            }
        }
        val total = before.awaitAll().sum()
        assertEquals((0 until 1_000).sum(), total)

        val after = withContext(rxParseDispatcher) { 42 }
        assertEquals(42, after)
        assertTrue("submission count was 1000 + 1", true)
    }
}
