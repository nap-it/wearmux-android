package com.example.peciwearables.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.MlProcessingLocation


@Composable
fun ProcessingDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Constants.cardBackground)
                .border(BorderStroke(1.dp, Constants.cardBorderColor), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Processing", color = Constants.primaryTextColor, fontSize = 16.sp)
            Text(
                "Choose where the IMU classifier runs. Applied when you tap the chip.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )

            ImuTab(viewModel)

            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
        }
    }
}

// ---------------- IMU ----------------

@Composable
private fun ImuTab(viewModel: AppViewModel) {
    val current by viewModel.mlProcessingLocation.collectAsStateWithLifecycle()
    val serverUrl by viewModel.mlServerUrl.collectAsStateWithLifecycle()
    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }

    Text("walk/idle classifier", color = Constants.primaryTextColor, fontSize = 13.sp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MlProcessingLocation.values().forEach { loc ->
            FilterChip(
                selected = current == loc,
                onClick = { viewModel.setMlProcessingLocation(loc) },
                label = {
                    Text(
                        when (loc) {
                            MlProcessingLocation.WRISTBAND -> "Wristband"
                            MlProcessingLocation.APP -> "App"
                            MlProcessingLocation.SERVER -> "Server"
                        },
                        fontSize = 11.sp,
                        color = if (current == loc) Color.Black else Constants.primaryTextColor,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Constants.accentColor,
                    containerColor = Constants.cardBackgroundElevated,
                    selectedLabelColor = Color.Black,
                    labelColor = Constants.primaryTextColor,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (current == MlProcessingLocation.SERVER) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("IMU server URL or IP:Port", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = peciTextFieldColors(),
        )
        Text(
            text = "Example: ${CloudConfig.DEFAULT_HOST}:8081 (assumes http and /infer)",
            color = Constants.secondaryTextColor,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                var finalUrl = urlInput.trim()
                if (finalUrl.isNotBlank() && !finalUrl.startsWith("http")) {
                    val hasPort = finalUrl.contains(":")
                    finalUrl = "http://${finalUrl}${if (hasPort) "" else ":8081"}/infer"
                }
                viewModel.setMlServerUrl(finalUrl)
                urlInput = finalUrl
            },
            colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save URL", color = Color.Black, fontSize = 11.sp) }
    }

    Text(
        text = when (current) {
            MlProcessingLocation.WRISTBAND -> "On-device inference (peci-custom-v32, 100 Hz)."
            MlProcessingLocation.APP -> "Local Android inference (cpp-android-v23, 20 Hz)."
            MlProcessingLocation.SERVER -> "Remote inference (node-simd-v22, HTTP, 20 Hz)."
        },
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )
}
