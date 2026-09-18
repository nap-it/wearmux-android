package com.example.peciwearables.integration.api

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Definições do broker. Desligado por omissão: sem broker configurado o hub
 * comporta-se exactamente como antes e publica só por HTTP/WebSocket.
 */
data class MqttConfig(
    val enabled: Boolean = false,
    val brokerUrl: String = "",
    val topicPrefix: String = DEFAULT_TOPIC_PREFIX,
    val clientId: String = DEFAULT_CLIENT_ID,
    val qos: Int = 0,
) {
    val isUsable: Boolean get() = enabled && brokerUrl.isNotBlank()

    /**
     * O Paho exige esquema e porta explícitos. Aceitamos `host`, `host:porta` ou
     * um URL completo e completamos o que faltar.
     */
    fun normalizedBrokerUrl(): String {
        val raw = brokerUrl.trim().removeSuffix("/")
        if (raw.isEmpty()) return ""
        val withScheme = if (raw.contains("://")) raw else "$DEFAULT_SCHEME$raw"
        val authority = withScheme.substringAfter("://")
        return if (authority.substringAfter("[", "").contains("]") || authority.contains(':')) {
            withScheme
        } else {
            "$withScheme:$DEFAULT_PORT"
        }
    }

    /** Um tópico por modalidade, com o mesmo nome da rota HTTP equivalente. */
    fun topicFor(route: String): String {
        val prefix = topicPrefix.trim().trim('/').ifEmpty { DEFAULT_TOPIC_PREFIX }
        return "$prefix/${route.trim().trim('/')}"
    }

    companion object {
        const val DEFAULT_TOPIC_PREFIX = "wearmux"
        const val DEFAULT_CLIENT_ID = "wearmux-hub"
        const val DEFAULT_SCHEME = "tcp://"
        const val DEFAULT_PORT = 1883
    }
}

/** Ligação ao broker. Abstraída para os testes correrem sem broker nem Paho. */
interface MqttTransport {
    fun publish(topic: String, payload: ByteArray, qos: Int)
    fun close()
}

/**
 * Publica as observações do hub em tópicos MQTT, em paralelo com o envio HTTP.
 *
 * O carimbo já vem aplicado pelo Timestamper dentro do payload; aqui só muda o
 * transporte. Uma falha do broker nunca interrompe o caminho HTTP: tudo o que
 * acontece aqui é assíncrono e os erros ficam no log.
 */
class MqttObservationPublisher(
    private val scope: CoroutineScope,
    private val config: () -> MqttConfig,
    private val transportFactory: (MqttConfig) -> MqttTransport = { PahoMqttTransport(it) },
    private val onLog: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var transport: MqttTransport? = null
    private var openedWith: MqttConfig? = null

    fun publish(route: String, json: String) {
        val cfg = config()
        if (!cfg.isUsable) return
        scope.launch(dispatcher) {
            runCatching {
                transportFor(cfg).publish(cfg.topicFor(route), json.toByteArray(), cfg.qos)
            }.onFailure {
                Log.d(TAG, "publish to ${cfg.topicFor(route)} failed: ${it.message}")
                dropTransport()
            }
        }
    }

    private fun transportFor(cfg: MqttConfig): MqttTransport = synchronized(lock) {
        val current = transport
        if (current != null && openedWith?.sameEndpoint(cfg) == true) return current
        current?.let { runCatching { it.close() } }
        val created = transportFactory(cfg)
        transport = created
        openedWith = cfg
        onLog("MQTT: publishing to ${cfg.normalizedBrokerUrl()} under ${cfg.topicPrefix}/")
        created
    }

    private fun dropTransport() = synchronized(lock) {
        transport?.let { runCatching { it.close() } }
        transport = null
        openedWith = null
    }

    fun shutdown() = dropTransport()

    private fun MqttConfig.sameEndpoint(other: MqttConfig): Boolean =
        normalizedBrokerUrl() == other.normalizedBrokerUrl() && clientId == other.clientId

    private companion object { const val TAG = "MqttPublisher" }
}
