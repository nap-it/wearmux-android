package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.peciwearables.Constants


@Composable
fun PeciCard(
    modifier: Modifier = Modifier,
    background: Color = Constants.cardBackground,
    padding: PaddingValues = PaddingValues(14.dp),
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Cabeçalho de secção dentro de um PeciCard. */
@Composable
fun PeciSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Constants.primaryTextColor,
        fontSize = 14.sp,
        modifier = modifier,
    )
}

/** Linha de detalhe pequena (ex: "Bateria · 80%"). */
@Composable
fun PeciDetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Constants.secondaryTextColor, fontSize = 12.sp)
        Text(value, color = Constants.primaryTextColor, fontSize = 12.sp)
    }
}


@Composable
fun peciTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Constants.primaryTextColor,
    unfocusedTextColor = Constants.primaryTextColor,
    focusedBorderColor = Constants.accentColor,
    unfocusedBorderColor = Constants.secondaryTextColor,
    focusedLabelColor = Constants.accentColor,
    unfocusedLabelColor = Constants.secondaryTextColor,
)
