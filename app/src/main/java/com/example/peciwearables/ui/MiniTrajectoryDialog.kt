package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.NavisensImuSource


@Composable
fun MiniTrajectoryDialog(
    viewModel: AppViewModel,
    forSource: NavisensImuSource,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(Constants.cardBackground)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Trajeto · $title",
                    color = Constants.primaryTextColor,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Fechar",
                        tint = Constants.primaryTextColor,
                    )
                }
            }

            NavisensWebView(
                modifier = Modifier.fillMaxWidth(),
                // Em FUSED encaminha o stream fundido (média ponderada
                // adaptativa); demais fontes mantêm o fluxo dos óculos.
                glassesImuFlow = if (forSource == NavisensImuSource.FUSED)
                    viewModel.fusedImuStream
                else
                    viewModel.glassesImuStream,
                onStarted = {},
                onStopped = {},
            )

            Text(
                text = "Fonte: ${forSource.label()}",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
    }
}

private fun NavisensImuSource.label(): String = when (this) {
    NavisensImuSource.PHONE -> "Telemóvel"
    NavisensImuSource.GLASSES -> "Óculos"
    NavisensImuSource.WATCH -> "Galaxy Watch"
    NavisensImuSource.FUSED -> "Fundido (N fontes)"
}
