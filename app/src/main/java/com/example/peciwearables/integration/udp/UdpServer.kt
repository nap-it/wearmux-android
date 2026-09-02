package com.example.peciwearables.integration.udp

import android.util.Log
import com.example.peciwearables.integration.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

/**
 * Wrapper para datagrama recebido com timestamp de receção.
 */
data class Datagram(
    val data: ByteArray,
    val receiveTimestampUs: Long, // SystemClock.elapsedRealtimeNanos()/1000
    val senderAddress: InetAddress?,
    val senderPort: Int
)


class UdpServer(private val port: Int = 3000) {

    companion object {
        private const val TAG = "UdpServer"
        private const val MAX_PACKET_SIZE = 1500 // MTU IPv4
    }

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var routerJob: Job? = null

    private val datagramChannel = Channel<Datagram>(Channel.BUFFERED)

    // Callbacks por tipo de mensagem
    var onAudioFrame: ((PacketHeader, AudioFrame) -> Unit)? = null
    var onImageChunk: ((PacketHeader, ImageChunk) -> Unit)? = null
    var onImuPayload: ((PacketHeader, ImuPayload) -> Unit)? = null
    var onControlMessage: ((PacketHeader, ByteArray) -> Unit)? = null

    // Métricas
    private var packetsReceived = 0L
    private var packetsInvalid = 0L

    val isRunning: Boolean get() = socket?.isBound == true && !socket!!.isClosed

    /**
     * Inicia o servidor UDP.
     */
    fun start(scope: CoroutineScope) {
        Log.i(TAG, "[UDP][Server] start() requested")
        if (isRunning) {
            Log.i(TAG, "[UDP][Server] start() ignored: already running")
            Log.w(TAG, "Server already running on port $port")
            return
        }

        try {
            socket = DatagramSocket(port).apply {
                reuseAddress = true
                soTimeout = 0 // blocking
            }
            Log.i(TAG, "[UDP][Server] socket bound on port $port")
            Log.d(TAG, "UDP server started on port $port")
        } catch (e: SocketException) {
            Log.e(TAG, "Failed to bind UDP socket: ${e.message}")
            return
        }

        // Coroutine de receção (I/O dispatcher)
        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isActive) {
                try {
                    socket?.receive(packet)

                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val receiveTs = android.os.SystemClock.elapsedRealtimeNanos() / 1000

                    datagramChannel.send(
                        Datagram(data, receiveTs, packet.address, packet.port)
                    )

                    packetsReceived++
                } catch (e: SocketException) {
                    if (isActive) Log.e(TAG, "Socket error: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Receive error: ${e.message}")
                }
            }
        }

        // Coroutine de routing (Default dispatcher para parsing)
        routerJob = scope.launch(Dispatchers.Default) {
            for (datagram in datagramChannel) {
                try {
                    routeDatagram(datagram)
                } catch (e: Exception) {
                    packetsInvalid++
                    Log.w(TAG, "Invalid packet: ${e.message}")
                }
            }
        }
    }

    /**
     * Para o servidor UDP.
     */
    fun stop() {
        Log.i(TAG, "[UDP][Server] stop() — socket closing")
        receiveJob?.cancel()
        routerJob?.cancel()
        datagramChannel.close()

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }

        socket = null
        Log.d(TAG, "UDP server stopped. Packets: $packetsReceived received, $packetsInvalid invalid")
    }

    /**
     * Parseia cabeçalho e encaminha payload para o pipeline correto.
     */
    private fun routeDatagram(datagram: Datagram) {
        if (datagram.data.size < PacketHeader.HEADER_SIZE) {
            packetsInvalid++
            return
        }

        val header = PacketHeader.parse(datagram.data)

        // Validar CRC se presente (flags.CRC)
        if (header.hasCrc()) {
            // TODO: validar headerCrc32 e payloadCrc32
        }

        // Extrair payload
        val payloadOffset = PacketHeader.HEADER_SIZE +
                (if (header.hasCrc()) 8 else 0) +    // 2×CRC32 = 8 bytes
                (if (header.hasHmac()) 16 else 0)      // HMAC truncado

        if (datagram.data.size < payloadOffset + header.payloadLen) {
            packetsInvalid++
            Log.w(TAG, "Packet truncated: expected ${payloadOffset + header.payloadLen}, got ${datagram.data.size}")
            return
        }

        val payload = datagram.data.copyOfRange(payloadOffset, payloadOffset + header.payloadLen)

        when (header.msgType) {
            PacketHeader.MSG_TYPE_AUDIO -> {
                val frame = AudioFrame.parse(payload)
                onAudioFrame?.invoke(header, frame)
            }
            PacketHeader.MSG_TYPE_IMAGE -> {
                val chunk = ImageChunk.parse(payload)
                onImageChunk?.invoke(header, chunk)
            }
            PacketHeader.MSG_TYPE_IMU -> {
                val imu = ImuPayload.parse(payload)
                onImuPayload?.invoke(header, imu)
            }
            PacketHeader.MSG_TYPE_CONTROL -> {
                onControlMessage?.invoke(header, payload)
            }
            else -> {
                Log.w(TAG, "Unknown msgType: ${header.msgType}")
                packetsInvalid++
            }
        }
    }

    fun getStats(): String {
        return "Packets received=$packetsReceived, invalid=$packetsInvalid"
    }
}
