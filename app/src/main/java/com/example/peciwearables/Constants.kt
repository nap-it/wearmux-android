package com.example.peciwearables

import androidx.compose.ui.graphics.Color


object Constants {
    // Backgrounds em camadas (escuro → claro)
    val backgroundColor = Color(0xFF0D0D11)        // página
    val cardBackground = Color(0xFF161821)         // card
    val cardBackgroundElevated = Color(0xFF1F2230) // card-on-card / chip

    // Accents
    val accentColor = Color(0xFF7B61FF)            // roxo principal
    val accentMuted = Color(0xFF5B45D9)            // roxo apagado (hover/disabled)
    val accentSoftBg = Color(0x337B61FF)           // overlay suave para banners

    // Texto
    val primaryTextColor = Color(0xFFFFFFFF)
    val secondaryTextColor = Color(0xFFB0B3C0)
    val mutedTextColor = Color(0xFF6B7280)

    // Estados (dot, pill, mensagens curtas)
    val successColor = Color(0xFF34D399)
    val warningColor = Color(0xFFFFB74D)
    val errorColor = Color(0xFFEF4444)
    val idleColor = Color(0xFF6B7280)

    // Botões secundários (cinza neutro acima do card)
    val neutralButton = Color(0xFF2E3A59)
    val mutedButton = Color(0xFF424242)
}
