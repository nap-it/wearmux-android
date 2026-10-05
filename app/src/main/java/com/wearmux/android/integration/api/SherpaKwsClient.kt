package com.wearmux.android.integration.api

import android.util.Log
import com.wearmux.android.integration.latency.LatencyCsvWriter
import com.wearmux.android.integration.observation.Timestamper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString


class SherpaKwsClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    /** `(keyword, utteranceStartMs)` — instante do 1.º chunk de áudio da utterance, 0 se desconhecido. */
    private val onKeyword: (keyword: String, utteranceStartMs: Long) -> Unit,
    private val csvWriter: LatencyCsvWriter? = null,
    private val timestamper: Timestamper = Timestamper.SYSTEM,
) {
    companion object {
        private const val TAG = "SherpaKwsClient"
        private const val RECONNECT_INITIAL_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private val kwsCounter = java.util.concurrent.atomic.AtomicInteger(0)

        fun parseKeyword(json: String): String? {
            val match = Regex("\"keyword\"\\s*:\\s*\"([^\"]+)\"").find(json) ?: return null
            return match.groupValues[1].trim().lowercase()
        }
    }

    /** Timestamp (ms) do primeiro chunk enviado na utterance actual.
     *  Definido externamente (pelo coordinator) antes de enviar áudio.
     *  Zero = nenhuma utterance em curso. */
    @Volatile var kwsSendTimeMs: Long = 0L

    private val stopped = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectDelayMs = RECONNECT_INITIAL_MS
    private var socket: WebSocket? = null

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        stopped.set(false)
        reconnectDelayMs = RECONNECT_INITIAL_MS
        reconnectTask?.cancel(false)
        openSocket()
    }

    fun disconnect() {
        stopped.set(true)
        reconnectTask?.cancel(false)
        socket?.send("END_OF_AUDIO")
        socket?.close(1000, "client disconnect")
        socket = null
        connected.set(false)
        onDisconnected()
    }

    fun sendAudio(bytes: ByteArray) {
        if (stopped.get()) return
        socket?.send(bytes.toByteString())
    }

    private fun openSocket() {
        if (stopped.get()) return
        Log.d(TAG, "connecting to ws://$host:$port")
        val req = Request.Builder().url("ws://$host:$port").build()
        socket = httpClient.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "connected to ws://$host:$port")
            reconnectDelayMs = RECONNECT_INITIAL_MS
            // Dá ao servidor a referência temporal do hub, para o caso de ser ele
            // a encaminhar a keyword em vez do hub.
            webSocket.send("{\"hub_timestamp\":${timestamper.now()}}")
            if (connected.compareAndSet(false, true)) onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val keyword = parseKeyword(text) ?: return
            Log.d(TAG, "keyword received: $keyword")
            val utteranceStartMs = kwsSendTimeMs
            if (utteranceStartMs > 0L) {
                val rttMs = System.currentTimeMillis() - utteranceStartMs
                val hubTs = utteranceStartMs
                val traceId = "KWS-${kwsCounter.incrementAndGet()}"
                Log.d(TAG, "BENCH|KWS|DONE|id=$traceId|keyword=$keyword|rtt_ms=$rttMs")
                csvWriter?.append(
                    filename = "kws_latency.csv",
                    header   = "trace_id,keyword,rtt_ms,hub_timestamp",
                    row      = "$traceId,$keyword,$rttMs,$hubTs"
                )
                kwsSendTimeMs = 0L
            }
            onKeyword(keyword, utteranceStartMs)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "closed: code=$code")
            handleTermination()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d(TAG, "failure: ${t.message}")
            handleTermination()
        }
    }

    private fun handleTermination() {
        socket = null
        val wasConnected = connected.getAndSet(false)
        if (wasConnected) onDisconnected()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        reconnectTask?.cancel(false)
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        reconnectTask = scheduler.schedule({ openSocket() }, delay, TimeUnit.MILLISECONDS)
        Log.d(TAG, "reconnecting in ${delay}ms")
    }
}
