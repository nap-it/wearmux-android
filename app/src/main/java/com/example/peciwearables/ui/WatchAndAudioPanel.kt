package com.example.peciwearables.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.NavisensImuSource
import com.example.peciwearables.integration.audio.AudioTestEngine
import com.example.peciwearables.integration.watch.WatchClient

private val cardBg get() = Constants.cardBackground
private val textPrimary get() = Constants.primaryTextColor
private val textSecondary get() = Constants.secondaryTextColor
private val accent get() = Constants.accentColor


@Composable
fun WatchAndAudioPanel(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Galaxy Watch",
            color = textPrimary,
            style = MaterialTheme.typography.titleMedium,
        )

        WatchSection(viewModel)
        Spacer(Modifier.height(4.dp))

        Text(
            text = "IMU source for Navisens",
            color = textPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        NavisensSourceSection(viewModel)
        Spacer(Modifier.height(4.dp))

        Text(
            text = "Audio test (phone)",
            color = textPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        AudioTestSection(viewModel)
    }
}

@Composable
private fun WatchSection(viewModel: AppViewModel) {
    val state by viewModel.watchState.collectAsStateWithLifecycle()
    val name by viewModel.watchName.collectAsStateWithLifecycle()
    val battery by viewModel.watchBattery.collectAsStateWithLifecycle()
    val rate by viewModel.watchSampleRateHz.collectAsStateWithLifecycle()
    val sps by viewModel.watchSamplesPerSec.collectAsStateWithLifecycle()
    val err by viewModel.watchLastError.collectAsStateWithLifecycle()

    val statusText = when (state) {
        WatchClient.State.DISCONNECTED -> "Disconnected"
        WatchClient.State.REMOTE -> "Far away (out of range)"
        WatchClient.State.AVAILABLE -> "Available"
        WatchClient.State.STREAMING -> "Streaming"
        WatchClient.State.ERROR -> "Error"
    }
    val statusColor = when (state) {
        WatchClient.State.STREAMING -> Constants.successColor
        WatchClient.State.AVAILABLE -> accent
        WatchClient.State.REMOTE -> Constants.warningColor
        WatchClient.State.ERROR -> Constants.errorColor
        else -> textSecondary
    }

    Text(
        text = (name ?: "No watch detected") + "  ·  $statusText",
        color = statusColor,
        fontSize = 13.sp,
    )
    Text(
        text = "Battery: ${if (battery >= 0) "$battery%" else "—"}  ·  ${rate} Hz  ·  ${sps}/s",
        color = textSecondary,
        fontSize = 12.sp,
    )
    err?.let {
        Text(text = "⚠ $it", color = Constants.errorColor, fontSize = 12.sp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { viewModel.watchStartImu() },
            enabled = state == WatchClient.State.AVAILABLE,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.weight(1f),
        ) { Text("Start IMU", color = Color.White, fontSize = 12.sp) }

        Button(
            onClick = { viewModel.watchStopImu() },
            enabled = state == WatchClient.State.STREAMING,
            colors = ButtonDefaults.buttonColors(containerColor = Constants.mutedButton),
            modifier = Modifier.weight(1f),
        ) { Text("Stop IMU", color = Color.White, fontSize = 12.sp) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(50, 100, 200).forEach { hz ->
            FilterChip(
                selected = rate == hz,
                onClick = { viewModel.watchSetSampleRate(hz) },
                label = { Text("$hz Hz", color = if (rate == hz) Color.Black else textPrimary) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    containerColor = Constants.cardBackgroundElevated,
                    selectedLabelColor = Color.Black,
                    labelColor = textPrimary,
                ),
            )
        }
    }

    OutlinedButton(
        onClick = { viewModel.watchBeep(880, 250) },
        enabled = state != WatchClient.State.DISCONNECTED,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Beep on the watch (speaker / watch BT headphones)", fontSize = 12.sp)
    }

    Text(
        text = "Watch vibrations",
        color = textSecondary,
        fontSize = 11.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Short" to 0,
            "Double" to 1,
            "Long" to 2,
        ).forEach { (label, id) ->
            Button(
                onClick = { viewModel.watchVibrate(id) },
                enabled = state != WatchClient.State.DISCONNECTED,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                modifier = Modifier.weight(1f),
            ) { Text(label, color = Color.White, fontSize = 11.sp) }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Triple" to 3,
            "Heartbeat" to 4,
            "Alarm" to 5,
        ).forEach { (label, id) ->
            Button(
                onClick = { viewModel.watchVibrate(id) },
                enabled = state != WatchClient.State.DISCONNECTED,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                modifier = Modifier.weight(1f),
            ) { Text(label, color = Color.White, fontSize = 11.sp) }
        }
    }

    Text(
        text = "Watch notifications",
        color = textSecondary,
        fontSize = 11.sp,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Button(
            onClick = {
                viewModel.watchNotify(
                    type = 0,
                    title = "PECI warning",
                    body = "Attention: non-critical event detected.",
                )
            },
            enabled = state == WatchClient.State.AVAILABLE || state == WatchClient.State.STREAMING,
            colors = ButtonDefaults.buttonColors(containerColor = Constants.warningColor),
            modifier = Modifier.weight(1f),
        ) { Text("WARNING", color = Color.Black, fontSize = 11.sp) }

        Button(
            onClick = {
                viewModel.watchNotify(
                    type = 1,
                    title = "PECI DANGER",
                    body = "Critical alert — check immediately.",
                )
            },
            enabled = state == WatchClient.State.AVAILABLE || state == WatchClient.State.STREAMING,
            colors = ButtonDefaults.buttonColors(containerColor = Constants.errorColor),
            modifier = Modifier.weight(1f),
        ) { Text("DANGER", color = Color.White, fontSize = 11.sp) }
    }
}

@Composable
private fun NavisensSourceSection(viewModel: AppViewModel) {
    val source by viewModel.navisensImuSource.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NavisensImuSource.values().forEach { src ->
            val label = when (src) {
                NavisensImuSource.PHONE -> "Phone"
                NavisensImuSource.GLASSES -> "Glasses"
                NavisensImuSource.WATCH -> "Watch"
                NavisensImuSource.FUSED -> "Fused"
            }
            FilterChip(
                selected = source == src,
                onClick = { viewModel.setNavisensImuSource(src) },
                label = { Text(label, color = if (source == src) Color.Black else textPrimary) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent,
                    containerColor = Constants.cardBackgroundElevated,
                    selectedLabelColor = Color.Black,
                    labelColor = textPrimary,
                ),
            )
        }
    }
    Text(
        text = "When the watch IMU is streaming, choosing \"Watch\" feeds its samples into the 2D trajectory.",
        color = textSecondary,
        fontSize = 11.sp,
    )
}

@Composable
private fun AudioTestSection(viewModel: AppViewModel) {
    val context = LocalContext.current
    val devices = remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        devices.value = AudioTestEngine.listOutputDevices(context)
    }

    Text(
        text = "Active outputs: " + (devices.value.takeIf { it.isNotEmpty() }?.joinToString("; ")
            ?: "—"),
        color = textSecondary,
        fontSize = 11.sp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            AudioTestEngine.TonePreset.BEEP_LOW,
            AudioTestEngine.TonePreset.BEEP_MID,
            AudioTestEngine.TonePreset.BEEP_HIGH,
        ).forEach { preset ->
            Button(
                onClick = { viewModel.playAudioPreset(preset) },
                colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when (preset) {
                        AudioTestEngine.TonePreset.BEEP_LOW -> "440 Hz"
                        AudioTestEngine.TonePreset.BEEP_MID -> "880 Hz"
                        AudioTestEngine.TonePreset.BEEP_HIGH -> "2 kHz"
                        else -> preset.label
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Button(
            onClick = { viewModel.playAudioPreset(AudioTestEngine.TonePreset.TONE_LONG) },
            colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
            modifier = Modifier.weight(1f),
        ) { Text("Tom 1 kHz / 1.5 s", color = Color.White, fontSize = 11.sp) }

        Button(
            onClick = { viewModel.playAudioPreset(AudioTestEngine.TonePreset.SWEEP) },
            colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
            modifier = Modifier.weight(1f),
        ) { Text("Sweep 200→4 kHz", color = Color.White, fontSize = 11.sp) }

        Button(
            onClick = { viewModel.stopAudioTest() },
            colors = ButtonDefaults.buttonColors(containerColor = Constants.mutedButton),
            modifier = Modifier.weight(1f),
        ) { Text("Stop", color = Color.White, fontSize = 11.sp) }
    }

    OutlinedButton(
        onClick = {
            viewModel.playAudioNotification(
                title = "PECI",
                body = "Test notification",
                freqHz = 880,
                durationMs = 350,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Play as notification (with banner + sound)", fontSize = 11.sp)
    }
}

