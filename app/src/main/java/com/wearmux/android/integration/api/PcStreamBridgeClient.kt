package com.wearmux.android.integration.api

import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class PcStreamBridgeState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class PcStreamBridgeSettings(
    val host: String = "",
    val port: Int = 5051,
    val enabled: Boolean = false,
)

private data class PendingFrame(
    val seq: Long,
    val route: String,
    val source: String,
    val jpegBytes: ByteArray,
)

class PcStreamBridgeClient(
    private val scope: CoroutineScope,
    private val onStateChanged: (PcStreamBridgeState, String) -> Unit,
    private val onLog: (String) -> Unit,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val RECONNECT_DELAY_MS = 1_500L
        private const val DEFAULT_PORT = 5051
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    }

    private val settingsRef = AtomicReference(PcStreamBridgeSettings(port = DEFAULT_PORT))
    private val pendingFrameRef = AtomicReference<PendingFrame?>(null)
    private val sequence = AtomicLong(0L)
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var streamingActive = false

    @Volatile
    private var socket: Socket? = null

    private var workerJob: Job? = null

    fun updateSettings(settings: PcStreamBridgeSettings) {
        val normalized = settings.copy(host = settings.host.trim())
        settingsRef.set(normalized)
        pendingFrameRef.set(null)

        if (!normalized.enabled) {
            cancelWorker()
            onStateChanged(PcStreamBridgeState.DISABLED, "TCP bridge disabled")
            return
        }

        if (streamingActive) {
            restartWorker()
        } else {
            closeSocket()
            onStateChanged(
                PcStreamBridgeState.DISCONNECTED,
                if (normalized.host.isBlank()) {
                    "Bridge ready, host not configured yet"
                } else {
                    "Bridge ready for ${normalized.host}:${normalized.port}"
                }
            )
        }
    }

    fun setStreamingActive(active: Boolean) {
        streamingActive = active
        pendingFrameRef.set(null)

        if (!active) {
            cancelWorker()
            val settings = settingsRef.get()
            onStateChanged(
                if (settings.enabled) PcStreamBridgeState.DISCONNECTED else PcStreamBridgeState.DISABLED,
                if (settings.enabled) "Waiting for stream" else "TCP bridge disabled"
            )
            return
        }

        if (!settingsRef.get().enabled) {
            onStateChanged(PcStreamBridgeState.DISABLED, "TCP bridge disabled")
            return
        }

        ensureWorker()
    }

    fun submitFrame(jpegBytes: ByteArray, route: String, source: String = "stream") {
        val settings = settingsRef.get()
        if (!streamingActive || !settings.enabled || settings.host.isBlank()) return
        pendingFrameRef.set(
            PendingFrame(
                seq = sequence.incrementAndGet(),
                route = route,
                source = source,
                jpegBytes = jpegBytes,
            )
        )
        frameSignal.trySend(Unit)
    }

    fun shutdown() {
        streamingActive = false
        pendingFrameRef.set(null)
        cancelWorker()
    }

    private fun ensureWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch(Dispatchers.IO) {
            runLoop()
        }
    }

    private fun restartWorker() {
        cancelWorker()
        ensureWorker()
    }

    private fun cancelWorker() {
        workerJob?.cancel()
        workerJob = null
        closeSocket()
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val settings = settingsRef.get()
            if (!streamingActive) break
            if (!settings.enabled) {
                onStateChanged(PcStreamBridgeState.DISABLED, "TCP bridge disabled")
                break
            }
            if (settings.host.isBlank() || settings.port !in 1..65535) {
                onStateChanged(PcStreamBridgeState.ERROR, "Invalid TCP host or port")
                delay(RECONNECT_DELAY_MS)
                continue
            }

            onStateChanged(PcStreamBridgeState.CONNECTING, "Connecting to ${settings.host}:${settings.port}...")

            try {
                val connectedSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(settings.host, settings.port), CONNECT_TIMEOUT_MS)
                }
                socket = connectedSocket
                val output = BufferedOutputStream(connectedSocket.getOutputStream())
                onStateChanged(PcStreamBridgeState.CONNECTED, "Connected to ${settings.host}:${settings.port}")
                onLog("TCP bridge: connected to ${settings.host}:${settings.port}")

                while (currentCoroutineContext().isActive && streamingActive) {
                    val latestSettings = settingsRef.get()
                    if (!latestSettings.enabled || latestSettings.host != settings.host || latestSettings.port != settings.port) {
                        break
                    }

                    val frame = pendingFrameRef.getAndSet(null)
                    if (frame == null) {
                        frameSignal.receive()
                        continue
                    }

                    writeFrame(output, frame)
                    output.flush()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: error::class.java.simpleName
                onStateChanged(PcStreamBridgeState.ERROR, "TCP error: $message")
                onLog("TCP bridge: connection/send error ($message)")
            } finally {
                closeSocket()
            }

            if (!currentCoroutineContext().isActive || !streamingActive || !settingsRef.get().enabled) {
                break
            }

            onStateChanged(PcStreamBridgeState.DISCONNECTED, "Connection lost, trying to reconnect...")
            delay(RECONNECT_DELAY_MS)
        }

        val settings = settingsRef.get()
        onStateChanged(
            if (settings.enabled) PcStreamBridgeState.DISCONNECTED else PcStreamBridgeState.DISABLED,
            if (settings.enabled) "Waiting for stream" else "TCP bridge disabled"
        )
    }

    private fun writeFrame(output: BufferedOutputStream, frame: PendingFrame) {
        val headerJson = JSONObject()
            .put("seq", frame.seq)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("route", frame.route)
            .put("source", frame.source)
            .toString()
            .toByteArray(Charsets.UTF_8)

        output.write(MAGIC)
        output.write(intToBigEndian(headerJson.size))
        output.write(intToBigEndian(frame.jpegBytes.size))
        output.write(headerJson)
        output.write(frame.jpegBytes)
    }

    private fun intToBigEndian(value: Int): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }
}
