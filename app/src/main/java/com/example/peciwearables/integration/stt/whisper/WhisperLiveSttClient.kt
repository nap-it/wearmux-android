// =============================================================================
// LEGACY — WhisperLive STT Client
// =============================================================================
// Este ficheiro implementa o cliente WebSocket para o servidor WhisperLive
// (transcrição contínua via Whisper). Foi substituído pelo SherpaKwsClient
// para o caso de uso de keyword spotting (UC1.3 "cross").
//
// Mantido para referência e possível reativação futura (ex: UC4.1 assistente
// visual por voz, UC4.5 transcrição de conversa). NÃO instanciado atualmente.
// =============================================================================
package com.example.peciwearables.integration.stt.whisper

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WhisperLiveSttClient(
    private val host: String,
    private val port: Int = 9090,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onSegments: (List<WhisperSegment>) -> Unit,
    private val onDebug: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "WhisperLiveSttClient"
        private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private val serverReadyRegex = Regex("\"message\"\\s*:\\s*\"SERVER_READY\"")
        private val segmentObjectRegex = Regex("\\{[^{}]*\\}")
        private val textFieldRegex = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")

        fun parseSegments(json: String): List<WhisperSegment> {
            val segmentsArrayContent = extractSegmentsArray(json) ?: return emptyList()
            return segmentObjectRegex.findAll(segmentsArrayContent).map { match ->
                val segmentJson = match.value
                WhisperSegment(
                    start = readFloatField(segmentJson, "start"),
                    end = readFloatField(segmentJson, "end"),
                    text = readTextField(segmentJson),
                    completed = readBooleanField(segmentJson, listOf("completed", "final", "is_final", "isFinal")),
                )
            }.toList()
        }

        fun isServerReady(json: String): Boolean = serverReadyRegex.containsMatchIn(json)

        private fun extractSegmentsArray(json: String): String? {
            val keyIndex = json.indexOf("\"segments\"")
            if (keyIndex < 0) return null
            val openBracket = json.indexOf('[', keyIndex)
            if (openBracket < 0) return null

            var depth = 0
            for (i in openBracket until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return json.substring(openBracket + 1, i)
                        }
                    }
                }
            }
            return null
        }

        private fun readFloatField(json: String, fieldName: String): Float {
            val pattern = Regex("\"$fieldName\"\\s*:\\s*(?:\"([^\"]+)\"|([-+]?\\d*\\.?\\d+))")
            val match = pattern.find(json) ?: return 0f
            val raw = match.groups[1]?.value ?: match.groups[2]?.value ?: return 0f
            return raw.toFloatOrNull() ?: 0f
        }

        private fun readTextField(json: String): String {
            val raw = textFieldRegex.find(json)?.groupValues?.get(1) ?: return ""
            return unescapeJsonString(raw)
        }

        // Decode JSON escape sequences (e.g., \u00e1) into actual characters.
        private fun unescapeJsonString(raw: String): String {
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '\\' || i + 1 >= raw.length) {
                    out.append(c)
                    i++
                    continue
                }

                val next = raw[i + 1]
                when (next) {
                    '"', '\\', '/' -> {
                        out.append(next)
                        i += 2
                    }
                    'b' -> {
                        out.append('\b')
                        i += 2
                    }
                    'f' -> {
                        out.append('\u000C')
                        i += 2
                    }
                    'n' -> {
                        out.append('\n')
                        i += 2
                    }
                    'r' -> {
                        out.append('\r')
                        i += 2
                    }
                    't' -> {
                        out.append('\t')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                if (code in 0xD800..0xDBFF && i + 11 < raw.length && raw[i + 6] == '\\' && raw[i + 7] == 'u') {
                                    val lowHex = raw.substring(i + 8, i + 12)
                                    val low = lowHex.toIntOrNull(16)
                                    if (low != null && low in 0xDC00..0xDFFF) {
                                        val codePoint = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                        out.append(Character.toChars(codePoint))
                                        i += 12
                                        continue
                                    }
                                }
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append('\\')
                        out.append(next)
                        i += 2
                    }
                    else -> {
                        out.append(next)
                        i += 2
                    }
                }
            }
            return out.toString()
        }

        private fun readBooleanField(json: String, fieldNames: List<String>): Boolean {
            for (field in fieldNames) {
                val regex = Regex("\"$field\"\\s*:\\s*(true|false|0|1)")
                val raw = regex.find(json)?.groupValues?.get(1) ?: continue
                return when (raw) {
                    "1" -> true
                    "0" -> false
                    else -> raw.toBooleanStrictOrNull() ?: false
                }
            }
            return false
        }
    }

    private val uid: String = UUID.randomUUID().toString()
    private val stopped = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectTask: ScheduledFuture<*>? = null
    private var clockSyncFuture: ScheduledFuture<*>? = null
    private var reconnectDelayMs: Long = RECONNECT_INITIAL_DELAY_MS
    private var socket: WebSocket? = null

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private fun debug(message: String) {
        Log.d(TAG, message)
        runCatching { onDebug(message) }
    }

    fun connect() {
        stopped.set(false)
        connected.set(false)
        reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
        cancelReconnectTask()
        debug("connect requested to ws://$host:$port")
        openSocket()
    }

    fun disconnect() {
        stopped.set(true)
        cancelReconnectTask()
        stopClockSync()
        connected.set(false)
        socket?.send("END_OF_AUDIO")
        socket?.close(1000, "client disconnect")
        socket = null
        debug("disconnect requested")
        onDisconnected()
    }

    fun sendAudio(bytes: ByteArray) {
        if (stopped.get()) return
        socket?.send(bytes.toByteString())
    }

    private fun openSocket() {
        if (stopped.get()) return
        debug("opening websocket ws://$host:$port")
        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()
        socket = httpClient.newWebSocket(request, socketListener)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val handshake = """{"uid":"$uid","hub_timestamp":${System.currentTimeMillis()},"language":"en","task":"transcribe","model":"small","use_vad":true,"vad_parameters":{"onset":0.5,"min_silence_duration_ms":400,"speech_pad_ms":200}}"""
            if (connected.compareAndSet(false, true)) {
                reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
                onConnected()
            }
            debug("websocket opened, sending handshake")
            webSocket.send(handshake)
            startClockSync(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isServerReady(text)) {
                if (connected.compareAndSet(false, true)) {
                    reconnectDelayMs = RECONNECT_INITIAL_DELAY_MS
                    onConnected()
                }
                return
            }
            val segments = parseSegments(text)
            if (segments.isNotEmpty()) {
                onSegments(segments)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            debug("websocket closed: code=$code reason=$reason")
            handleSocketTermination()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            debug("websocket failure: ${t.message ?: "unknown"} code=${response?.code ?: "-"}")
            handleSocketTermination()
        }
    }

    private fun handleSocketTermination() {
        stopClockSync()
        socket = null
        val wasConnected = connected.getAndSet(false)
        if (wasConnected || !stopped.get()) {
            onDisconnected()
        }
        scheduleReconnectIfNeeded()
    }

    private fun scheduleReconnectIfNeeded() {
        if (stopped.get()) return
        cancelReconnectTask()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectTask = reconnectScheduler.schedule(
            { openSocket() },
            delayMs,
            TimeUnit.MILLISECONDS
        )
        debug("scheduling reconnect in ${delayMs}ms")
    }

    private fun cancelReconnectTask() {
        reconnectTask?.cancel(false)
        reconnectTask = null
    }

    private fun startClockSync(webSocket: WebSocket) {
        stopClockSync()
        clockSyncFuture = reconnectScheduler.scheduleAtFixedRate(
            {
                if (!stopped.get()) {
                    webSocket.send("""{"type":"clock_sync","hub_timestamp":${System.currentTimeMillis()}}""")
                }
            },
            1_000L,
            1_000L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopClockSync() {
        clockSyncFuture?.cancel(false)
        clockSyncFuture = null
    }
}
