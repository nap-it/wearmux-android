package com.example.peciwearables.integration.stt.sherpa

import android.content.Context
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.audio.PhoneMicrophoneRecorder
import com.example.peciwearables.integration.latency.LatencyCsvWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


class SherpaKwsCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnectedChanged: (Boolean) -> Unit,
    private val onKeyword: (String) -> Unit,
    private val onLog: (String) -> Unit,
    private val glassesMicStreaming: () -> Boolean,
    private val csvWriter: LatencyCsvWriter? = null,
) {
    private var client: SherpaKwsClient? = null
    private var session: SherpaKwsSession? = null
    private var phoneMic: PhoneMicrophoneRecorder? = null

    val activeClient: SherpaKwsClient? get() = client
    val activeSession: SherpaKwsSession? get() = session

    fun connect(host: String) {
        disconnect()
        val c = SherpaKwsClient(
            host = host,
            port = CloudConfig.KWS_PORT,
            csvWriter = csvWriter,
            onConnected = {
                scope.launch {
                    onConnectedChanged(true)
                    onLog("🎙 KWS: connected to the Sherpa server ($host:${CloudConfig.KWS_PORT})")
                }
            },
            onDisconnected = {
                scope.launch {
                    onConnectedChanged(false)
                    onLog("🎙 KWS: connection to the server lost")
                }
            },
            onKeyword = { keyword ->
                // Repõe timer para a próxima utterance
                session?.resetChunkTimer()
                scope.launch {
                    onLog("🎙 KWS: keyword=$keyword detected")
                    onKeyword(keyword)
                }
            },
        )
        client = c
        session = SherpaKwsSession(sendAudio = c::sendAudio)
        c.connect()
        onLog("🎙 KWS: connecting to $host:${CloudConfig.KWS_PORT}...")

        runCatching { phoneMic?.stop() }
        val rec = PhoneMicrophoneRecorder(
            context = context,
            onPcm = { pcm ->
                if (!glassesMicStreaming()) {
                    // Marca o momento do primeiro chunk enviado para calcular RTT do KWS
                    val s = session
                    if (s != null && s.firstChunkSentMs == 0L) {
                        c.kwsSendTimeMs = System.currentTimeMillis()
                    }
                    s?.feedAudio(pcm)
                }
            },
        )
        if (rec.start()) {
            phoneMic = rec
            onLog("🎙 Phone mic ON → Sherpa KWS")
        } else {
            onLog("⚠ Phone mic unavailable for Sherpa KWS (permission?)")
        }
    }

    fun disconnect() {
        runCatching { phoneMic?.stop() }
        phoneMic = null
        session?.flush()
        session = null
        client?.disconnect()
        client = null
        onConnectedChanged(false)
    }

    fun feedAudio(pcm: ShortArray) {
        // Marca o momento do primeiro chunk enviado para calcular RTT do KWS (óculos mic path)
        val s = session
        if (s != null && s.firstChunkSentMs == 0L) {
            client?.kwsSendTimeMs = System.currentTimeMillis()
        }
        s?.feedAudio(pcm)
    }
}
