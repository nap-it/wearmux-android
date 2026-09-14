package com.example.peciwearables.integration.protocol.tlv

import java.io.ByteArrayOutputStream

/**
 * Serialização de mensagens TLV.
 *
 * Formato de cada mensagem:
 *   [typeEnum: 1B] [length: 2B little-endian] [data: length bytes]
 *
 * Equivale a createMessage() em ServerUtils.ts e sendTxMessages() em BaseConnectionManager.ts.
 */
object TlvWriter {

    /**
     * Mensagem a serializar (tipo + payload opcional).
     *
     * @param type  enum TxRxMessageType (ou SocketMessageType quando usado internamente)
     * @param data  payload da mensagem (vazio por defeito para mensagens "get")
     */
    data class Message<T : Enum<T>>(
        val type: T,
        val data: ByteArray = ByteArray(0)
    )

    // ══════════════════════════════════════════════════════════
    // TxRx — encode
    // ══════════════════════════════════════════════════════════

    /**
     * Serializa uma mensagem TxRx individual.
     *
     * @param type  tipo da mensagem
     * @param data  payload (vazio para mensagens "get")
     * @return ByteArray com formato [type:1B][len:2B LE][data]
     */
    fun encode(type: TxRxMessageType, data: ByteArray = ByteArray(0)): ByteArray =
        encodeTlv(type.ordinal, data)

    /**
     * Serializa uma lista de mensagens TxRx concatenadas.
     */
    fun encodeAll(messages: List<Message<TxRxMessageType>>): ByteArray {
        val out = ByteArrayOutputStream()
        for (msg in messages) {
            out.write(encodeTlv(msg.type.ordinal, msg.data))
        }
        return out.toByteArray()
    }

    /**
     * Overload vararg para conveniência.
     */
    fun encodeAll(vararg messages: Message<TxRxMessageType>): ByteArray =
        encodeAll(messages.toList())

    /**
     * Alias semântico para serializar um conjunto de mensagens TxRx.
     */
    fun txRxMessages(messages: List<Message<TxRxMessageType>>): ByteArray =
        encodeAll(messages)

    /**
     * Serializa uma mensagem "get" (sem payload).
     */
    fun txRxGet(type: TxRxMessageType): ByteArray = encode(type, ByteArray(0))

    /**
     * Serializa uma mensagem "set" (com payload).
     */
    fun txRxSet(type: TxRxMessageType, data: ByteArray): ByteArray = encode(type, data)

    // ══════════════════════════════════════════════════════════
    // TxRx — MTU partitioning
    // ══════════════════════════════════════════════════════════

    /**
     * Particiona uma lista de mensagens em pacotes que respeitam o MTU BLE.
     *
     * O BLE ATT usa 3 bytes de overhead por pacote, logo:
     *   maxPayload = mtu - 3
     *
     * Se uma mensagem individual exceder maxPayload, é enviada sozinha num pacote próprio
     * (não há fragmentação intra-mensagem).
     *
     * @param messages  lista de mensagens a enviar
     * @param mtu       MTU negociado (tipicamente 517 para BLE 5)
     * @return lista de ByteArrays, cada um cabe num pacote BLE
     */
    fun encodePartitioned(
        messages: List<Message<TxRxMessageType>>,
        mtu: Int
    ): List<ByteArray> {
        val maxPayload = mtu - 3 // ATT overhead
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()

        for (msg in messages) {
            val encoded = encodeTlv(msg.type.ordinal, msg.data)

            if (current.size() > 0 && current.size() + encoded.size > maxPayload) {
                // Fechar pacote atual e começar novo
                packets.add(current.toByteArray())
                current.reset()
            }

            current.write(encoded)

            // Se esta mensagem sozinha já excede o maxPayload, fechar imediatamente
            if (current.size() >= maxPayload) {
                packets.add(current.toByteArray())
                current.reset()
            }
        }

        if (current.size() > 0) {
            packets.add(current.toByteArray())
        }

        return packets
    }

    // ══════════════════════════════════════════════════════════
    // Socket (UDP) messages
    // ══════════════════════════════════════════════════════════

    /**
     * Produz uma mensagem socket PING (sem payload).
     * Enviado pelo telefone a cada 2s — keep-alive.
     */
    fun socketPing(): ByteArray = encodeTlv(SocketMessageType.PING.ordinal, ByteArray(0))

    /**
     * Produz uma mensagem socket PONG (sem payload).
     * Resposta ao PING dos óculos.
     */
    fun socketPong(): ByteArray = encodeTlv(SocketMessageType.PONG.ordinal, ByteArray(0))

    /**
     * Produz uma mensagem SET_REMOTE_RECEIVE_PORT.
     * Envia a porta UDP do telefone para os óculos durante o handshake.
     *
     * @param port  porta local do telefone (uint16)
     */
    fun setRemoteReceivePort(port: Int): ByteArray {
        val portBytes = uint16Payload(port)
        return encodeTlv(SocketMessageType.SET_REMOTE_RECEIVE_PORT.ordinal, portBytes)
    }

    /**
     * Encapsula bytes TxRx numa mensagem socket MESSAGE.
     *
     * Estrutura outer: [MESSAGE:1B][len:2B LE][txRxPayload]
     */
    fun wrapTxRxInSocketMessage(txRxBytes: ByteArray): ByteArray =
        encodeTlv(SocketMessageType.MESSAGE.ordinal, txRxBytes)

    // ══════════════════════════════════════════════════════════
    // Payload helpers
    // ══════════════════════════════════════════════════════════

    /** Codifica uma string UTF-8 como payload. */
    fun stringPayload(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** Codifica um booleano: true→1, false→0. */
    fun booleanPayload(b: Boolean): ByteArray = byteArrayOf(if (b) 1 else 0)

    /** Codifica um inteiro como uint16 little-endian (2 bytes). */
    fun uint16Payload(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte()
    )

    // ══════════════════════════════════════════════════════════
    // Core TLV encoder
    // ══════════════════════════════════════════════════════════

    /**
     * Serializa uma mensagem TLV genérica.
     * Formato: [typeOrdinal: 1B] [length: 2B LE] [data: length bytes]
     */
    private fun encodeTlv(typeOrdinal: Int, data: ByteArray): ByteArray {
        val buf = ByteArray(1 + 2 + data.size)
        buf[0] = typeOrdinal.toByte()
        buf[1] = (data.size and 0xFF).toByte()       // length low byte
        buf[2] = ((data.size shr 8) and 0xFF).toByte() // length high byte
        data.copyInto(buf, destinationOffset = 3)
        return buf
    }
}
