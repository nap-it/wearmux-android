package com.example.peciwearables.integration.udp

import android.util.Log
import com.example.peciwearables.integration.protocol.tlv.TlvParser
import com.example.peciwearables.integration.protocol.tlv.TlvWriter
import com.example.peciwearables.integration.protocol.tlv.TxRxMessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException


class UdpConnectionManager(
    private val glassesIp: String,
    private val glassesPort: Int = UDP_SEND_PORT,
) {

    companion object {
        private const val TAG = "UdpConnectionManager"

        /** Porta para onde enviar dados aos glasses (fixo no firmware Omi). */
        const val UDP_SEND_PORT = 3000

        /** Intervalo do timer de handshake + keep-alive (ms). */
        const val PING_INTERVAL_MS = 3_000L

        /** Timeout para receber PONG após PING (ms). */
        const val PONG_TIMEOUT_MS = 45_000L
        const val MAX_CONSECUTIVE_KEEP_ALIVE_TIMEOUTS = 10

        
        const val MAX_HANDSHAKE_ATTEMPTS = 20

        /** Tamanho máximo de datagrama UDP. */
        private const val MAX_PACKET_SIZE = 65_535
    }

    // ── Status ────────────────────────────────────────────────────────────────

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status

    // ── Callbacks públicos ────────────────────────────────────────────────────

    /**
     * Chamado com os bytes TxRx internos quando chega um `SocketMessageType.MESSAGE`.
     * O caller deve parsear com `TlvParser.parseTxRx()` e fazer routing por tipo.
     */
    var onTxRxMessage: ((ByteArray) -> Unit)? = null

    /**
     * Disparado quando o handshake UDP fica completo (SET_REMOTE_RECEIVE_PORT eco recebido).
     * O WearableService usa isto para desligar o BLE — espelha `reconnectViaUDP()` do SDK TS
     * que chama `disconnect()` antes de `connect({ type: "udp", ... })`.
     */
    var onUdpConnected: (() -> Unit)? = null

    /**
     * Disparado quando a sessão UDP termina.
     * O caller usa isto para limpar o routing Omi via Wi-Fi.
     */
    var onDisconnected: ((Status) -> Unit)? = null

    /** Callback opcional para logs detalhados do handshake/tráfego UDP. */
    var onDebugLog: ((String) -> Unit)? = null

    // ── Internos ──────────────────────────────────────────────────────────────

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var handshakeJob: Job? = null
    private var keepAliveJob: Job? = null
    private var sendScope: CoroutineScope? = null
    private var lastPongTimeMs: Long = 0L
    private var lastPingSentAtMs: Long = 0L
    private var localPort: Int = -1
    private var handshakeAttempts: Int = 0
    private var consecutiveKeepAliveTimeouts: Int = 0
    private val recentRttsMs = ArrayDeque<Long>()

    private val glassesAddress: InetAddress by lazy { InetAddress.getByName(glassesIp) }

    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val rxParseDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    private lateinit var handler: UdpSocketHandler

    val averageRttMs: Long
        get() = if (recentRttsMs.isEmpty()) 0L else recentRttsMs.average().toLong()

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Abre o socket e inicia handshake + receive loop.
     */
    fun connect(scope: CoroutineScope) {
        Log.i(TAG, "[UDP][Omi] connect() — state=${_status.value}")
        if (_status.value != Status.DISCONNECTED) {
            Log.i(TAG, "[UDP][Omi] connect() ignored: state=${_status.value}")
            Log.w(TAG, "Already connecting/connected")
            debug("connect() ignorado: status=${_status.value}")
            return
        }
        _status.value = Status.CONNECTING
        lastPongTimeMs = System.currentTimeMillis()
        handshakeAttempts = 0
        consecutiveKeepAliveTimeouts = 0
        sendScope?.cancel()
        sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        debug("a resolver IP dos glasses: $glassesIp:$glassesPort")

        try {
            // Bind numa porta aleatória
            socket = DatagramSocket().apply { reuseAddress = true }
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to open socket: ${e.message}")
            _status.value = Status.DISCONNECTED
            debug("falha ao abrir socket UDP: ${e.message}")
            return
        }

        localPort = socket!!.localPort
        Log.d(TAG, "UDP socket bound on local port $localPort → glasses $glassesIp:$glassesPort")
        debug("socket UDP aberto: localPort=$localPort remote=$glassesIp:$glassesPort")

        handler = UdpSocketHandler(
            localPort    = localPort,
            onTxNeeded   = { bytes -> sendRaw(bytes) },
            onConnected  = {
                _status.value = Status.CONNECTED
                consecutiveKeepAliveTimeouts = 0
                Log.i(TAG, "[UDP][Omi] CONNECTED on localPort=$localPort")
                Log.d(TAG, "UDP handshake complete — CONNECTED")
                debug("handshake UDP completo apos $handshakeAttempts tentativa(s)")
                startKeepAlive(scope)
                onUdpConnected?.invoke()
            },
            onPingReceived = {
                lastPongTimeMs = System.currentTimeMillis()
                consecutiveKeepAliveTimeouts = 0
                debug("keep-alive atualizado por PING/PONG inbound")
            },
            onPongReceived = {
                val now = System.currentTimeMillis()
                lastPongTimeMs = now
                consecutiveKeepAliveTimeouts = 0
                if (lastPingSentAtMs > 0L) {
                    val rtt = (now - lastPingSentAtMs).coerceAtLeast(1L)
                    recentRttsMs.addLast(rtt)
                    while (recentRttsMs.size > 10) {
                        recentRttsMs.removeFirst()
                    }
                    debug("keep-alive RTT=${rtt}ms avg=${averageRttMs}ms")
                }
            },
            onTxRxMessage  = {
                debug("routing MESSAGE TxRx ${it.size}B para OmiRxHandler")
                onTxRxMessage?.invoke(it)
            },
            onDebug = { msg -> debug(msg) }
        )

        startReceiveLoop(scope)
        startHandshake(scope, localPort)
    }

    /**
     * Fecha o socket e cancela todas as coroutines.
     */
    fun disconnect() {
        Log.i(TAG, "[UDP][Omi] disconnect() — cleaning up")
        val previousStatus = _status.value
        debug("disconnect() statusAnterior=$previousStatus localPort=$localPort")
        handshakeJob?.cancel()
        keepAliveJob?.cancel()
        receiveJob?.cancel()
        sendScope?.cancel()
        sendScope = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        handler.reset()
        _status.value = Status.DISCONNECTED
        localPort = -1
        consecutiveKeepAliveTimeouts = 0
        lastPingSentAtMs = 0L
        recentRttsMs.clear()
        Log.d(TAG, "UDP disconnected")
        if (previousStatus != Status.DISCONNECTED) {
            onDisconnected?.invoke(previousStatus)
        }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop(scope: CoroutineScope) {
        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket?.receive(packet) ?: break
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    debug("datagrama recebido de ${packet.address?.hostAddress}:${packet.port} com ${data.size}B")
                    // Registar PONG (qualquer datagrama recebido = glasses estão vivas)
                    lastPongTimeMs = System.currentTimeMillis()
                    consecutiveKeepAliveTimeouts = 0
                    // Parsear num dispatcher single-thread dedicado para que dois
                    // datagramas consecutivos NUNCA sejam processados em paralelo —
                    // evita race conditions no estado mutável do OmiRxHandler e
                    // FileTransferHandler (expectedLength, buffers, etc.).
                    withContext(rxParseDispatcher) {
                        handler.onRxBytes(data)
                    }
                } catch (e: SocketException) {
                    if (isActive) Log.e(TAG, "Socket error in receive loop: ${e.message}")
                    if (isActive) debug("socket error no receive loop: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Receive error: ${e.message}")
                    debug("erro a receber UDP: ${e.message}")
                }
            }
        }
    }

    // ── Handshake timer ───────────────────────────────────────────────────────

    private fun startHandshake(scope: CoroutineScope, localPort: Int) {
        handshakeJob = scope.launch(Dispatchers.IO) {
            while (isActive && !handler.isConnected) {
                handshakeAttempts++
                Log.d(TAG, "UDP handshake: sending SET_REMOTE_RECEIVE_PORT($localPort)")
                debug("handshake tentativa $handshakeAttempts: enviar SET_REMOTE_RECEIVE_PORT($localPort)")
                sendRaw(UdpSocketHandler.buildHandshakeBytes(localPort))

                if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) {
                    Log.w(
                        TAG,
                        "UDP handshake: aborted after $handshakeAttempts attempts to $glassesIp:$glassesPort"
                    )
                    debug(
                        "handshake abortado após $handshakeAttempts tentativas sem resposta de " +
                            "$glassesIp:$glassesPort. Provável IP em cache ou óculos noutra rede; " +
                            "BLE assume tudo."
                    )
                    disconnect()
                    return@launch
                }

                delay(PING_INTERVAL_MS)
            }
        }
    }

    // ── Keep-alive timer ──────────────────────────────────────────────────────

    private fun startKeepAlive(scope: CoroutineScope) {
        handshakeJob?.cancel() // handshake já concluído
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                val elapsed = System.currentTimeMillis() - lastPongTimeMs
                if (elapsed > PONG_TIMEOUT_MS) {
                    consecutiveKeepAliveTimeouts += 1
                    Log.w(TAG, "PONG timeout (${elapsed}ms) — disconnecting")
                    debug("timeout keep-alive: sem datagramas ha ${elapsed}ms (miss $consecutiveKeepAliveTimeouts/$MAX_CONSECUTIVE_KEEP_ALIVE_TIMEOUTS)")
                    if (consecutiveKeepAliveTimeouts >= MAX_CONSECUTIVE_KEEP_ALIVE_TIMEOUTS) {
                        disconnect()
                        break
                    }
                }
                Log.d(TAG, "UDP PING → glasses")
                debug("enviar PING keep-alive (elapsed=${elapsed}ms)")
                lastPingSentAtMs = System.currentTimeMillis()
                sendRaw(UdpSocketHandler.buildPingBytes())
            }
        }
    }

    // ── TX (TxRx encapsulado em SocketMessage.MESSAGE) ────────────────────────

    /**
     * Envia bytes TxRx TLV encapsulados num `SocketMessageType.MESSAGE` via UDP.
     * Usado pelo [OmiRxHandler]/[WearableService] para enviar comandos aos glasses
     * após a transição BLE→UDP.
     */
    fun sendTxRxMessage(txRxBytes: ByteArray) {
        debug("sendTxRxMessage ${txRxBytes.size}B")
        sendRaw(TlvWriter.wrapTxRxInSocketMessage(txRxBytes))
    }

    // ── Core send ─────────────────────────────────────────────────────────────

    private fun sendRaw(bytes: ByteArray) {
        val sock = socket ?: return
        val scope = sendScope ?: return
        val payloadDescription = describeSocketPayload(bytes)
        scope.launch {
            try {
                debug("TX raw $payloadDescription -> $glassesIp:$glassesPort [thread=${Thread.currentThread().name}]")
                val packet = DatagramPacket(bytes, bytes.size, glassesAddress, glassesPort)
                sock.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Send error (${e::class.java.simpleName}): ${e.message}", e)
                debug(
                    "erro a enviar UDP: ${e::class.java.simpleName}: ${e.message ?: "<sem mensagem>"} " +
                        "[thread=${Thread.currentThread().name}]"
                )
            }
        }
    }

    private fun describeSocketPayload(bytes: ByteArray): String {
        if (bytes.size < 3) return "${bytes.size}B (sem header TLV)"
        val typeOrdinal = bytes[0].toInt() and 0xFF
        val type = try {
            TlvParser.parseSocketToList(bytes).firstOrNull()?.type?.name
        } catch (_: Exception) {
            null
        }
        return if (type != null) "$type ${bytes.size}B" else "type=$typeOrdinal ${bytes.size}B"
    }

    private fun debug(message: String) {
        val noisy = message.startsWith("datagrama recebido") ||
            message.startsWith("socket datagram parseado") ||
            message.startsWith("RX socket MESSAGE") ||
            message.startsWith("MESSAGE recebido com payload") ||
            message.startsWith("routing MESSAGE") ||
            message.startsWith("TX raw MESSAGE")
        if (noisy) return
        Log.d(TAG, message)
        onDebugLog?.invoke(message)
    }
}
