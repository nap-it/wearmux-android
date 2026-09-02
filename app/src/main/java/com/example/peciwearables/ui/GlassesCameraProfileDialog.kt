package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants


@Composable
fun GlassesCameraProfileDialog(
    viewModel: AppViewModel,
    initialResolution: Int = 480,
    initialQuality: Int = 74,
    initialRateMs: Int = 10,
    onDismiss: () -> Unit,
) {
    data class Preset(val name: String, val res: Int, val q: Int, val rate: Int, val desc: String)
    val presets = remember {
        listOf(
            Preset("Equilibrado", 480, 74, 10, "Default recomendado · 480p · ~10 fps"),
            Preset("Qualidade", 640, 85, 15, "Mais detalhe · 640p · ~7 fps"),
            Preset("Performance", 480, 60, 5, "Latência mínima para YOLO · 480p · ~20 fps"),
        )
    }

    var selectedPreset by remember { mutableStateOf(presets[0].name) }
    var customRes by remember { mutableStateOf(initialResolution.toString()) }
    var customQ by remember { mutableStateOf(initialQuality.toString()) }
    var customRate by remember { mutableStateOf(initialRateMs.toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Constants.cardBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Perfil de câmara dos óculos",
                color = Constants.primaryTextColor,
                fontSize = 16.sp,
            )
            Text(
                "Escolhe um preset ou afina os parâmetros manualmente.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )

            // Presets em chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { p ->
                    FilterChip(
                        selected = selectedPreset == p.name,
                        onClick = { selectedPreset = p.name },
                        label = { Text(p.name, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Constants.accentColor,
                            containerColor = Constants.cardBackgroundElevated,
                            selectedLabelColor = Color.Black,
                            labelColor = Constants.primaryTextColor,
                        ),
                    )
                }
                FilterChip(
                    selected = selectedPreset == "Custom",
                    onClick = { selectedPreset = "Custom" },
                    label = { Text("Custom", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Constants.accentColor,
                        containerColor = Constants.cardBackgroundElevated,
                        selectedLabelColor = Color.Black,
                        labelColor = Constants.primaryTextColor,
                    ),
                )
            }

            val current = presets.firstOrNull { it.name == selectedPreset }
            if (current != null) {
                Text(current.desc, color = Constants.secondaryTextColor, fontSize = 11.sp)
            } else {
                // Custom inputs
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("Resol.", customRes, Modifier.weight(1f)) { customRes = it }
                    NumField("Qualidade", customQ, Modifier.weight(1f)) { customQ = it }
                    NumField("Rate ms", customRate, Modifier.weight(1f)) { customRate = it }
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancelar") }
                Button(
                    onClick = {
                        val (res, q, rate) = if (current != null) {
                            Triple(current.res, current.q, current.rate)
                        } else {
                            Triple(
                                customRes.toIntOrNull() ?: 480,
                                customQ.toIntOrNull() ?: 74,
                                customRate.toIntOrNull() ?: 10,
                            )
                        }
                        viewModel.applyGlassesCameraProfile(res.coerceAtLeast(480), q, rate)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                    modifier = Modifier.weight(1f),
                ) { Text("Aplicar", color = Color.Black) }
            }
        }
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Constants.primaryTextColor,
            unfocusedTextColor = Constants.primaryTextColor,
            focusedBorderColor = Constants.accentColor,
            unfocusedBorderColor = Constants.secondaryTextColor,
            focusedLabelColor = Constants.accentColor,
            unfocusedLabelColor = Constants.secondaryTextColor,
        ),
    )
}
