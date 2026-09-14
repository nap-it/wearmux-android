package com.example.peciwearables.integration.protocol.tlv

/**
 * Tipos de mensagem de socket UDP — espelho exato do array SocketMessageTypes
 * em UDPConnectionManager.ts do SDK TypeScript.
 *
 * Formato TLV: [socketType: 1B] [length: 2B LE] [data: length bytes]
 *
 * As mensagens TxRx são encapsuladas dentro de MESSAGE (ordinal 5).
 */
enum class SocketMessageType {
    PING,                    // 0 — keep-alive enviado pelo telefone
    PONG,                    // 1 — resposta dos óculos
    SET_REMOTE_RECEIVE_PORT, // 2 — handshake: porta do telefone enviada aos óculos (uint16 LE)
    BATTERY_LEVEL,           // 3 — nível de bateria via UDP
    DEVICE_INFORMATION,      // 4 — informação do dispositivo via UDP
    MESSAGE;                 // 5 — wrapper para mensagens TxRx

    companion object {
        /**
         * Converte ordinal para enum, ou null se inválido.
         */
        fun fromOrdinal(ordinal: Int): SocketMessageType? = entries.getOrNull(ordinal)
    }
}
