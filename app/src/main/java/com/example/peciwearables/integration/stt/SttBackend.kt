package com.example.peciwearables.integration.stt

/**
 * Abstracao para Speech-to-Text.
 * Permite alternar entre processamento local e cloud.
 */
interface SttBackend {
    /**
     * Transcreve audio PCM 16-bit mono 16kHz.
     */
    suspend fun transcribe(pcmSamples: ShortArray, sampleRate: Int = 16000): SttResult
    fun close()
}

data class SttResult(
    val text: String,
    val language: String = "",
    val durationMs: Long = 0,
    val backend: String = ""
)

enum class SttMode {
    LOCAL,
    CLOUD
}

enum class SttInputSource {
    GLASSES,
    PHONE
}