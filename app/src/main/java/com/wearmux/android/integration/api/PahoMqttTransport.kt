package com.wearmux.android.integration.api

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * [MqttTransport] sobre o cliente Eclipse Paho.
 *
 * A persistência é em memória: as observações são úteis enquanto são recentes,
 * não vale a pena guardá-las em disco para reenviar mais tarde. A ligação é
 * aberta na primeira publicação e o Paho trata da reconexão.
 */
class PahoMqttTransport(private val config: MqttConfig) : MqttTransport {

    private val client: MqttClient by lazy {
        MqttClient(config.normalizedBrokerUrl(), clientId(), MemoryPersistence())
    }

    private val options = MqttConnectOptions().apply {
        isCleanSession = true
        isAutomaticReconnect = true
        connectionTimeout = CONNECT_TIMEOUT_S
        keepAliveInterval = KEEPALIVE_S
    }

    override fun publish(topic: String, payload: ByteArray, qos: Int) {
        if (!client.isConnected) client.connect(options)
        client.publish(topic, MqttMessage(payload).apply { this.qos = qos.coerceIn(0, 2) })
    }

    override fun close() {
        runCatching { if (client.isConnected) client.disconnect(DISCONNECT_TIMEOUT_MS) }
        runCatching { client.close() }
    }

    // O broker recusa uma segunda ligação com o mesmo id; o sufixo evita que
    // duas instalações na mesma rede se expulsem uma à outra.
    private fun clientId(): String =
        "${config.clientId}-${MqttClient.generateClientId().takeLast(6)}"

    private companion object {
        const val CONNECT_TIMEOUT_S = 5
        const val KEEPALIVE_S = 30
        const val DISCONNECT_TIMEOUT_MS = 500L
    }
}
