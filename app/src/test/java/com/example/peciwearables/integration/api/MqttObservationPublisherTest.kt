package com.example.peciwearables.integration.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttConfigTest {

    @Test
    fun `um host sozinho ganha esquema e porta`() {
        val cfg = MqttConfig(brokerUrl = "192.168.1.50")
        assertEquals("tcp://192.168.1.50:1883", cfg.normalizedBrokerUrl())
    }

    @Test
    fun `um host com porta so ganha o esquema`() {
        assertEquals("tcp://broker.local:1884", MqttConfig(brokerUrl = "broker.local:1884").normalizedBrokerUrl())
    }

    @Test
    fun `um url completo fica intacto`() {
        assertEquals("ssl://broker.local:8883", MqttConfig(brokerUrl = "ssl://broker.local:8883").normalizedBrokerUrl())
        assertEquals("tcp://broker.local:1883", MqttConfig(brokerUrl = "tcp://broker.local:1883/").normalizedBrokerUrl())
    }

    @Test
    fun `sem broker o url e vazio`() {
        assertEquals("", MqttConfig(brokerUrl = "   ").normalizedBrokerUrl())
    }

    @Test
    fun `o topico usa o mesmo nome da rota HTTP`() {
        val cfg = MqttConfig(topicPrefix = "wearmux")
        assertEquals("wearmux/telemetry", cfg.topicFor("telemetry"))
        assertEquals("wearmux/inputs/audio_stt", cfg.topicFor("inputs/audio_stt"))
        assertEquals("wearmux/inputs/glasses_pose", cfg.topicFor("/inputs/glasses_pose"))
    }

    @Test
    fun `barras a mais no prefixo sao ignoradas`() {
        assertEquals("lab/hub/telemetry", MqttConfig(topicPrefix = "/lab/hub/").topicFor("telemetry"))
    }

    @Test
    fun `prefixo vazio cai no default`() {
        assertEquals("wearmux/telemetry", MqttConfig(topicPrefix = "  ").topicFor("telemetry"))
    }

    @Test
    fun `so e utilizavel com broker e activado`() {
        assertFalse(MqttConfig(enabled = false, brokerUrl = "broker.local").isUsable)
        assertFalse(MqttConfig(enabled = true, brokerUrl = "").isUsable)
        assertTrue(MqttConfig(enabled = true, brokerUrl = "broker.local").isUsable)
    }
}

private class FakeTransport : MqttTransport {
    val published = mutableListOf<Triple<String, String, Int>>()
    var closed = false
    var failNext = false

    override fun publish(topic: String, payload: ByteArray, qos: Int) {
        if (failNext) {
            failNext = false
            throw IllegalStateException("broker unreachable")
        }
        published += Triple(topic, payload.decodeToString(), qos)
    }

    override fun close() { closed = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MqttObservationPublisherTest {

    private fun publisher(
        config: MqttConfig,
        factory: (MqttConfig) -> MqttTransport,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = MqttObservationPublisher(
        scope = scope,
        config = { config },
        transportFactory = factory,
        dispatcher = UnconfinedTestDispatcher(scope.coroutineContext[kotlinx.coroutines.test.TestCoroutineScheduler]),
    )

    @Test
    fun `desligado nao publica nem abre ligacao`() = runTest {
        var created = 0
        val transport = FakeTransport()
        val p = publisher(MqttConfig(enabled = false, brokerUrl = "broker.local"), { created++; transport }, this)

        p.publish("telemetry", """{"lat":1}""")

        assertEquals(0, created)
        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `sem broker nao publica`() = runTest {
        val transport = FakeTransport()
        val p = publisher(MqttConfig(enabled = true, brokerUrl = ""), { transport }, this)

        p.publish("telemetry", """{"lat":1}""")

        assertTrue(transport.published.isEmpty())
    }

    @Test
    fun `publica o payload no topico da modalidade`() = runTest {
        val transport = FakeTransport()
        val p = publisher(MqttConfig(enabled = true, brokerUrl = "broker.local"), { transport }, this)

        p.publish("inputs/audio_stt", """{"text":"crossing"}""")

        assertEquals(1, transport.published.size)
        val (topic, payload, qos) = transport.published.single()
        assertEquals("wearmux/inputs/audio_stt", topic)
        assertEquals("""{"text":"crossing"}""", payload)
        assertEquals(0, qos)
    }

    @Test
    fun `reutiliza a ligacao entre publicacoes`() = runTest {
        var created = 0
        val transport = FakeTransport()
        val p = publisher(MqttConfig(enabled = true, brokerUrl = "broker.local"), { created++; transport }, this)

        p.publish("telemetry", "{}")
        p.publish("telemetry", "{}")
        p.publish("inputs/glasses_pose", "{}")

        assertEquals(1, created)
        assertEquals(3, transport.published.size)
    }

    @Test
    fun `uma falha do broker nao propaga e larga a ligacao`() = runTest {
        val first = FakeTransport().apply { failNext = true }
        val second = FakeTransport()
        var created = 0
        val p = publisher(
            MqttConfig(enabled = true, brokerUrl = "broker.local"),
            { if (created++ == 0) first else second },
            this,
        )

        p.publish("telemetry", "{}")   // falha, larga a ligação
        p.publish("telemetry", "{}")   // reabre e publica

        assertTrue(first.closed)
        assertEquals(1, second.published.size)
        assertEquals(2, created)
    }

    @Test
    fun `shutdown fecha a ligacao aberta`() = runTest {
        val transport = FakeTransport()
        val p = publisher(MqttConfig(enabled = true, brokerUrl = "broker.local"), { transport }, this)
        p.publish("telemetry", "{}")

        p.shutdown()

        assertTrue(transport.closed)
    }

    @Test
    fun `o transporte recebe a configuracao pedida`() = runTest {
        var seen: MqttConfig? = null
        val transport = FakeTransport()
        val cfg = MqttConfig(enabled = true, brokerUrl = "broker.local", topicPrefix = "lab", qos = 1)
        val p = publisher(cfg, { seen = it; transport }, this)

        p.publish("telemetry", "{}")

        assertSame(cfg, seen)
        assertEquals("lab/telemetry", transport.published.single().first)
        assertEquals(1, transport.published.single().third)
    }
}
