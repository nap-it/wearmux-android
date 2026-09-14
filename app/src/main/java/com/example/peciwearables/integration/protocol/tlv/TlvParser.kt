package com.example.peciwearables.integration.protocol.tlv

/**
 * Parser de mensagens TLV recebidas via BLE (RX characteristic) ou UDP.
 *
 * Formato de cada mensagem:
 *   [typeEnum: 1B] [length: 2B little-endian] [data: length bytes]
 *
 * Equivale a parseMessage() em ParseUtils.ts e parseRxMessage() em BaseConnectionManager.ts.
 */
object TlvParser {

    // ══════════════════════════════════════════════════════════
    // Tipos de resultado
    // ══════════════════════════════════════════════════════════

    /** Mensagem TxRx parseada. */
    data class TxRxMessage(
        val type: TxRxMessageType,
        val data: ByteArray,
        val isLast: Boolean
    )

    /** Mensagem socket (UDP) parseada. */
    data class SocketMessage(
        val type: SocketMessageType,
        val data: ByteArray,
        val isLast: Boolean
    )

    /**
     * Resultado de parse socket em modo tolerante.
     *
     * - [messages] contem o prefixo valido parseado
     * - [errorMessage] descreve a primeira falha encontrada (se existir)
     * - [bytesConsumed] indica quantos bytes do datagrama foram processados
     */
    data class SocketParseResult(
        val messages: List<SocketMessage>,
        val bytesConsumed: Int,
        val errorMessage: String? = null,
    ) {
        val hasError: Boolean
            get() = !errorMessage.isNullOrBlank()
    }

    // ══════════════════════════════════════════════════════════
    // TxRx — parse
    // ══════════════════════════════════════════════════════════

    /**
     * Parseia mensagens TxRx com callback por cada mensagem encontrada.
     *
     * @param buf     buffer com mensagens TLV concatenadas
     * @param offset  offset de início no buffer (por defeito 0)
     * @param length  número de bytes a processar (por defeito buf.size - offset)
     * @param onMessage  callback: (type, data, isLast)
     *
     * @throws IllegalArgumentException se ordinal inválido ou buffer truncado
     */
    fun parseTxRx(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
        onMessage: (TxRxMessageType, ByteArray, Boolean) -> Unit
    ) {
        val end = offset + length
        var pos = offset

        while (pos < end) {
            // Verificar que há pelo menos 3 bytes para o header TLV
            require(end - pos >= 3) {
                "TLV truncated: need 3 header bytes at pos=$pos, only ${end - pos} remaining"
            }

            val typeOrdinal = buf[pos].toInt() and 0xFF
            val msgLength = ((buf[pos + 1].toInt() and 0xFF)) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8)
            pos += 3

            // Validar ordinal
            val type = TxRxMessageType.fromOrdinal(typeOrdinal)
                ?: throw IllegalArgumentException(
                    "Invalid TxRxMessageType ordinal: $typeOrdinal (max=${TxRxMessageType.entries.size - 1})"
                )

            // Verificar que há bytes suficientes para o payload
            require(end - pos >= msgLength) {
                "TLV truncated: type=$type claims $msgLength bytes but only ${end - pos} remaining"
            }

            val data = buf.copyOfRange(pos, pos + msgLength)
            pos += msgLength

            val isLast = pos >= end
            onMessage(type, data, isLast)
        }
    }

    /**
     * Parseia mensagens TxRx e devolve uma lista.
     *
     * @throws IllegalArgumentException se ordinal inválido ou buffer truncado
     */
    fun parseTxRxToList(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset
    ): List<TxRxMessage> {
        val result = mutableListOf<TxRxMessage>()
        parseTxRx(buf, offset, length) { type, data, isLast ->
            result.add(TxRxMessage(type, data, isLast))
        }
        return result
    }

    // ══════════════════════════════════════════════════════════
    // Socket (UDP) — parse
    // ══════════════════════════════════════════════════════════

    /**
     * Parseia mensagens socket UDP e devolve uma lista.
     *
     * Usa o mesmo formato TLV mas com ordinals de SocketMessageType.
     *
     * @throws IllegalArgumentException se ordinal inválido ou buffer truncado
     */
    fun parseSocketToList(buf: ByteArray): List<SocketMessage> {
        val result = mutableListOf<SocketMessage>()
        val end = buf.size
        var pos = 0

        while (pos < end) {
            require(end - pos >= 3) {
                "Socket TLV truncated: need 3 header bytes at pos=$pos"
            }

            val typeOrdinal = buf[pos].toInt() and 0xFF
            val msgLength = (buf[pos + 1].toInt() and 0xFF) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8)
            pos += 3

            val type = SocketMessageType.fromOrdinal(typeOrdinal)
                ?: throw IllegalArgumentException(
                    "Invalid SocketMessageType ordinal: $typeOrdinal (max=${SocketMessageType.entries.size - 1})"
                )

            require(end - pos >= msgLength) {
                "Socket TLV truncated: type=$type claims $msgLength bytes but only ${end - pos} remaining"
            }

            val data = buf.copyOfRange(pos, pos + msgLength)
            pos += msgLength

            val isLast = pos >= end
            result.add(SocketMessage(type, data, isLast))
        }

        return result
    }

    /**
     * Parse tolerante para UDP:
     * - processa todas as mensagens TLV completas no prefixo do datagrama
     * - se encontrar truncamento/ordinal invalido, devolve as mensagens ja parseadas
     *   e reporta o erro sem lançar excecao
     */
    fun parseSocketLenient(buf: ByteArray): SocketParseResult {
        val result = mutableListOf<SocketMessage>()
        val end = buf.size
        var pos = 0

        while (pos < end) {
            if (end - pos < 3) {
                return SocketParseResult(
                    messages = result,
                    bytesConsumed = pos,
                    errorMessage = "Socket TLV truncated: need 3 header bytes at pos=$pos"
                )
            }

            val msgStart = pos
            val typeOrdinal = buf[pos].toInt() and 0xFF
            val msgLength = (buf[pos + 1].toInt() and 0xFF) or
                ((buf[pos + 2].toInt() and 0xFF) shl 8)
            pos += 3

            val type = SocketMessageType.fromOrdinal(typeOrdinal)
                ?: return SocketParseResult(
                    messages = result,
                    bytesConsumed = msgStart,
                    errorMessage = "Invalid SocketMessageType ordinal: $typeOrdinal (max=${SocketMessageType.entries.size - 1})"
                )

            if (end - pos < msgLength) {
                return SocketParseResult(
                    messages = result,
                    bytesConsumed = msgStart,
                    errorMessage = "Socket TLV truncated: type=$type claims $msgLength bytes but only ${end - pos} remaining"
                )
            }

            val data = buf.copyOfRange(pos, pos + msgLength)
            pos += msgLength
            result.add(
                SocketMessage(
                    type = type,
                    data = data,
                    isLast = pos >= end,
                )
            )
        }

        return SocketParseResult(
            messages = result,
            bytesConsumed = end,
            errorMessage = null,
        )
    }
}
