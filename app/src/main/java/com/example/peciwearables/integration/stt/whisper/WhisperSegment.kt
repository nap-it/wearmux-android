package com.example.peciwearables.integration.stt.whisper

data class WhisperSegment(
    val start: Float,
    val end: Float,
    val text: String,
    val completed: Boolean,
)
