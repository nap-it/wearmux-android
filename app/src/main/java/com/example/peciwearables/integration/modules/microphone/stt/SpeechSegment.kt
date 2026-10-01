package com.example.peciwearables.integration.modules.microphone.stt

/** Recognized text from external speech services, also used for Sherpa keyword results. */
data class SpeechSegment(
    val start: Float,
    val end: Float,
    val text: String,
    val completed: Boolean,
)
