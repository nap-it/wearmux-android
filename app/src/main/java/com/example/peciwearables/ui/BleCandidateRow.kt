package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.BleConnectionCandidate
import com.example.peciwearables.integration.ble.WearableKind

@Composable
fun BleCandidateRow(
    candidate: BleConnectionCandidate,
    onConnect: () -> Unit
) {
    val kindLabel = when (candidate.kind) {
        WearableKind.GLASSES -> "GLASSES"
        WearableKind.WRIST_OR_SOLE -> "WRIST/SOLE"
        WearableKind.UNKNOWN -> "UNKNOWN"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Constants.cardBackground)
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = candidate.name,
                color = Constants.primaryTextColor,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Identificador: ${candidate.address}   Sinal: ${candidate.rssi} dBm",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Kind: $kindLabel   SDK type: ${candidate.sdkDeviceType ?: "N/A"}",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp
            )
            candidate.ipAddress?.let { ip ->
                Text(
                    text = "IP adv: $ip",
                    color = Constants.secondaryTextColor,
                    fontSize = 11.sp
                )
            }
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor)
            ) {
                Text("Ligar a este", color = Color.Black, fontSize = 12.sp)
            }
        }
    }
}

