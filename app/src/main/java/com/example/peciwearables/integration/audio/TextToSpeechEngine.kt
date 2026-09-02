package com.example.peciwearables.integration.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


class TextToSpeechEngine(
    private val context: Context,
    private val locale: Locale = Locale.forLanguageTag("pt-PT"),
) : UtteranceProgressListener() {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private var tts: TextToSpeech? = null
    private val pending = ArrayDeque<QueuedUtterance>()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val avail = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fall-back para inglês se PT não estiver instalado.
                    tts?.setLanguage(Locale.US)
                }
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(this)
                _ready.value = true
                Log.i(TAG, "TTS init OK · locale=$locale")
                flushPending()
            } else {
                Log.w(TAG, "TTS init falhou: $status")
            }
        }
    }

    /**
     * Adiciona à fila. `flush=true` interrompe a frase actual e fala já a
     * nova (útil para alertas).
     */
    fun speak(text: String, flush: Boolean = false): Boolean {
        val t = tts ?: return false
        if (text.isBlank()) return false
        if (!_ready.value) {
            synchronized(pending) { pending.addLast(QueuedUtterance(text, flush)) }
            Log.d(TAG, "TTS not ready; queued text")
            return true
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = UUID.randomUUID().toString()
        return t.speak(text, mode, null, id) == TextToSpeech.SUCCESS
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        _ready.value = false
        _isSpeaking.value = false
    }

    // UtteranceProgressListener
    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
    override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) { _isSpeaking.value = false }

    private fun flushPending() {
        val t = tts ?: return
        val queued = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (queued.isEmpty()) return
        queued.forEach { item ->
            val mode = if (item.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = UUID.randomUUID().toString()
            t.speak(item.text, mode, null, id)
        }
    }

    private data class QueuedUtterance(val text: String, val flush: Boolean)

    companion object {
        private const val TAG = "TextToSpeechEngine"
    }
}
