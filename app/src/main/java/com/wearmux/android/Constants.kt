package com.wearmux.android

import androidx.compose.ui.graphics.Color


object Constants {
    // Backgrounds em camadas (escuro → claro)
    val backgroundColor = Color(0xFF0A0C10)        // página
    val cardBackground = Color(0xFF12151D)         // card
    val cardBackgroundElevated = Color(0xFF1A2030) // card-on-card / chip
    val cardBorderColor = Color(0xFF1D2230)        // borda fina dos cards

    // Accents — azul-acinzentado "tech", usado com intenção (CTAs e estados)
    val accentColor = Color(0xFF4C6FFF)            // azul principal
    val accentMuted = Color(0xFF3B5BE0)            // azul apagado (gradiente/hover/disabled)
    val accentSoftBg = Color(0x334C6FFF)           // overlay suave para banners

    // Texto
    val primaryTextColor = Color(0xFFF2F4F8)
    val secondaryTextColor = Color(0xFF8992A6)
    val mutedTextColor = Color(0xFF6B7280)

    // Estados (dot, pill, mensagens curtas)
    val successColor = Color(0xFF3DDC97)
    val warningColor = Color(0xFFF5A623)
    val errorColor = Color(0xFFEF4444)
    val idleColor = Color(0xFF5B667A)

    // Botões secundários (cinza neutro acima do card)
    val neutralButton = Color(0xFF2E3A59)
    val mutedButton = Color(0xFF424242)
}
