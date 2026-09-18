package com.example.peciwearables.integration.modules.microphone.stt

data class WhisperSegment(
    val start: Float,
    val end: Float,
    val text: String,
    val completed: Boolean,
)
