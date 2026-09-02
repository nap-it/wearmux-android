package com.example.peciwearables.integration.udp

import com.example.peciwearables.integration.protocol.tlv.SocketMessageType
import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter


class UdpSocketHandler(
    /**
     * Porta local do telefone. Usada para validar o eco do handshake.
     * Deve ser definida antes da primeira chamada a [onRxBytes].
     */
    var localPort: Int = 0,

    /**
     * Chamada para enviar bytes UDP ao device.
     * Em produção: `DatagramSocket.send(DatagramPacket(...))`.
     * Nos testes: `capturedTx.add(bytes)`.
     */
    val onTxNeeded: (ByteArray) -> Unit = {},

    /**
     * Disparada quando o handshake UDP fica completo:
     * eco `SET_REMOTE_RECEIVE_PORT` recebido com a porta correcta.
     */
    val onConnected: () -> Unit = {},

    /**
     * Disparada quando os glasses enviam um PING (para o telefone responder com PONG).
     * O keep-alive inverso também é feito pelo [UdpConnectionManager] com timers.
     */
    val onPingReceived: () -> Unit = {},

    /** Disparada quando chega um PONG em resposta ao nosso keep-alive. */
    val onPongReceived: () -> Unit = {},

    /**
     * Chamada quando chega uma mensagem `SocketMessageType.MESSAGE` com TxRx encapsulado.
     * O caller (ex: [UdpConnectionManager]) faz o routing para câmara/microfone/sensores.
     */
    val onTxRxMessage: (ByteArray) -> Unit = {},

    /** Callback opcional de debug textual. */
    val onDebug: (String) -> Unit = {},
) {

    companion object {

        /** Bytes UDP a enviar para o handshake: SET_REMOTE_RECEIVE_PORT(localPort). */
        fun buildHandshakeBytes(localPort: Int): ByteArray =
            TlvWriter.setRemoteReceivePort(localPort)

        /** Bytes do PING keep-alive enviado pelo telefone. */
        fun buildPingBytes(): ByteArray = TlvWriter.socketPing()

        /** Bytes do PONG enviado em resposta a um PING dos glasses. */
        fun buildPongBytes(): ByteArray = TlvWriter.socketPong()
    }

    // ── Estado ────────────────────────────────────────────────────────────────

    /** true após eco SET_REMOTE_RECEIVE_PORT confirmado com a porta correcta. */
    var isConnected: Boolean = false
        private set

    /** Número de PINGs recebidos dos glasses (para debug/testes). */
    var pingsReceived: Int = 0
        private set

    /** Número de PONGs recebidos dos glasses (para debug/testes). */
    var pongsReceived: Int = 0
        private set

    /** Numero total de datagramas UDP recebidos. */
    var datagramsReceived: Int = 0
        private set

    /** Numero total de erros de parse em datagramas socket TLV. */
    var parseErrors: Int = 0
        private set

    /** Numero de erros de parse que ocorreram apos prefixo valido parseado. */
    var parseErrorsWithValidPrefix: Int = 0
        private set

    /** Numero de erros de parse por truncamento de datagrama. */
    var parseTruncatedErrors: Int = 0
        private set

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Ponto de entrada: bytes raw de um datagrama UDP recebido.
     * Pode conter múltiplas mensagens socket TLV concatenadas.
     */
    fun onRxBytes(data: ByteArray) {
        datagramsReceived++
        val parse = TlvParser.parseSocketLenient(data)
        if (parse.messages.isNotEmpty()) {
            onDebug(
                "socket datagram parseado: ${parse.messages.size} mensagem(ns), ${data.size}B " +
                    "(consumed=${parse.bytesConsumed})"
            )
        }
        for (msg in parse.messages) {
            handleSocketMessage(msg.type, msg.data)
        }
        if (parse.hasError) {
            parseErrors++
            if (parse.messages.isNotEmpty()) {
                parseErrorsWithValidPrefix++
            }
            val error = parse.errorMessage.orEmpty()
            if (error.contains("truncated", ignoreCase = true)) {
                parseTruncatedErrors++
            }
            onDebug(
                "socket parse error: $error " +
                    "(parsed=${parse.messages.size}, consumed=${parse.bytesConsumed}/${data.size})"
            )
            println(
                "E/UdpSocketHandler: parse error: $error " +
                    "(parsed=${parse.messages.size}, consumed=${parse.bytesConsumed}/${data.size})"
            )
        }
    }

    /** Reinicia o estado para uma nova ligação. */
    fun reset() {
        isConnected = false
        pingsReceived = 0
        pongsReceived = 0
        datagramsReceived = 0
        parseErrors = 0
        parseErrorsWithValidPrefix = 0
        parseTruncatedErrors = 0
    }

    // ── Lógica interna ────────────────────────────────────────────────────────

    private fun handleSocketMessage(type: SocketMessageType, data: ByteArray) {
        onDebug("RX socket ${type.name} (${data.size}B)")
        println("D/UdpSocketHandler: RX socket ${type.name} (${data.size}B)")

        when (type) {
            SocketMessageType.PING -> {
                pingsReceived++
                onDebug("PING recebido dos glasses -> responder PONG")
                // Glasses fazem ping → telefone responde com pong
                onTxNeeded(buildPongBytes())
                onPingReceived()
            }

            SocketMessageType.PONG -> {
                pongsReceived++
                onDebug("PONG recebido dos glasses")
                onPongReceived()
                // Confirmação do nosso PING keep-alive — registado pelo caller
            }

            SocketMessageType.SET_REMOTE_RECEIVE_PORT -> {
                // Eco do handshake: glasses devolvem a porta que receberam
                if (data.size >= 2) {
                    val port = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                    if (port == localPort) {
                        if (!isConnected) {
                            isConnected = true
                            onDebug("SET_REMOTE_RECEIVE_PORT confirmado com porta esperada=$port")
                            println("D/UdpSocketHandler: UDP handshake complete (port=$port) → connected")
                            onConnected()
                        }
                    } else {
                        onDebug("SET_REMOTE_RECEIVE_PORT mismatch: expected=$localPort got=$port")
                        println("W/UdpSocketHandler: SET_REMOTE_RECEIVE_PORT port mismatch: expected $localPort, got $port")
                    }
                } else {
                    onDebug("SET_REMOTE_RECEIVE_PORT payload curto (${data.size}B)")
                    println("W/UdpSocketHandler: SET_REMOTE_RECEIVE_PORT payload too short (${data.size}B)")
                }
            }

            SocketMessageType.MESSAGE -> {
                // Inner TxRx TLV encapsulado — passa para o caller fazer routing
                if (data.isNotEmpty()) {
                    onDebug("MESSAGE recebido com payload TxRx ${data.size}B")
                    onTxRxMessage(data)
                } else {
                    onDebug("MESSAGE recebido vazio")
                }
            }

            SocketMessageType.BATTERY_LEVEL -> {
                onDebug("BATTERY_LEVEL recebido (${data.size}B)")
                // Processado futuramente
            }

            SocketMessageType.DEVICE_INFORMATION -> {
                onDebug("DEVICE_INFORMATION recebido (${data.size}B)")
                // Processado futuramente
            }
        }
    }
}
