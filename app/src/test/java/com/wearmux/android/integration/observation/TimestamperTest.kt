package com.wearmux.android.integration.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Fig. 3 — bloco "Timestamper". O contrato que estes testes fixam é o que
 * distingue o carimbo de aquisição do carimbo de envio: uma observação que já
 * traz carimbo mantém-no até à cloud, por muito que demore a ser transmitida.
 */
class TimestamperTest {

    private fun fixedAt(vararg values: Long): Timestamper {
        val seq = values.iterator()
        return Timestamper { seq.next() }
    }

    @Test
    fun `now devolve o relogio do hub`() {
        assertEquals(1_000L, fixedAt(1_000L).now())
    }

    @Test
    fun `stamp carimba o valor com o instante actual`() {
        val observation = fixedAt(4_242L).stamp("keyword")
        assertEquals("keyword", observation.value)
        assertEquals(4_242L, observation.hubTimestampMs)
    }

    @Test
    fun `hubTimestampFor preserva o carimbo da aquisicao`() {
        // O envio acontece 800 ms depois da aquisição: o carimbo enviado tem de
        // continuar a ser o da aquisição, não o do POST.
        val timestamper = fixedAt(10_800L)
        assertEquals(10_000L, timestamper.hubTimestampFor(10_000L))
    }

    @Test
    fun `hubTimestampFor gera carimbo novo quando nao ha observacao`() {
        assertEquals(7_000L, fixedAt(7_000L).hubTimestampFor(null))
    }

    @Test
    fun `hubTimestampFor trata zero como ausencia de carimbo`() {
        // 0L é o valor inicial dos campos `*AtMs` antes do primeiro frame.
        assertEquals(7_000L, fixedAt(7_000L).hubTimestampFor(0L))
    }

    @Test
    fun `carimbo da aquisicao difere do instante de envio`() {
        val timestamper = fixedAt(500L, 900L)
        val observed = timestamper.now()
        assertEquals(observed, timestamper.hubTimestampFor(observed))
        assertNotEquals(observed, timestamper.now())
    }
}
