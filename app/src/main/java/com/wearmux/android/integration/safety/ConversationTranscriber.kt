package com.wearmux.android.integration.safety

import com.wearmux.android.integration.modules.android.TextToSpeechEngine
import com.wearmux.android.integration.modules.microphone.stt.SpeechSegment


class ConversationTranscriber(
    private val tts: TextToSpeechEngine,
) {
    @Volatile private var lastSpokenHash: Int = 0

    fun onSegments(segments: List<SpeechSegment>) {
        val latest = segments.lastOrNull { it.completed } ?: return
        val text = latest.text.trim()
        if (text.length < 3) return

        // Ignorar comandos vocais usados por outros UCs — usa token matching
        // para evitar falsos positivos em palavras como "across", "prepare".
        if (VoiceCommandMatcher.matchesCrossing(text) || VoiceCommandMatcher.matchesStop(text)) return

        val hash = text.hashCode()
        if (hash == lastSpokenHash) return
        lastSpokenHash = hash

        tts.speak(text, flush = false)
    }

    fun reset() {
        lastSpokenHash = 0
    }
}
