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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.example.peciwearables.integration.inference.InferenceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL


@Composable
fun ProcessingDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val tabs = listOf("IMU", "Detection", "Voice")
    var selectedTab by remember { mutableStateOf(0) }

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
            Text("Processing", color = Constants.primaryTextColor, fontSize = 16.sp)
            Text(
                "Choose where each type of inference runs. Applied when you tap the chip.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Constants.cardBackgroundElevated,
                contentColor = Constants.accentColor,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            ) {
                tabs.forEachIndexed { i, name ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = {
                            Text(
                                name,
                                fontSize = 12.sp,
                                color = if (selectedTab == i) Constants.accentColor else Constants.secondaryTextColor,
                            )
                        },
                    )
                }
            }

            when (tabs[selectedTab]) {
                "IMU" -> ImuTab(viewModel)
                "Detection" -> CameraTab(viewModel)
                "Voice" -> VoiceTab(viewModel)
            }

            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
        }
    }
}

private enum class VoiceRuntime {
    LOCAL,
    CLOUD,
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

// ---------------- Câmara (YOLO) ----------------

@Composable
private fun CameraTab(viewModel: AppViewModel) {
    val current by viewModel.glassesInferenceMode.collectAsStateWithLifecycle()
    val url by viewModel.glassesInferenceCloudUrl.collectAsStateWithLifecycle()
    val serverConnected by viewModel.yoloServerConnected.collectAsStateWithLifecycle()
    var urlInput by remember(url) { mutableStateOf(url) }
    var pinging by remember { mutableStateOf(false) }

    Text("Object detection (YOLO)", color = Constants.primaryTextColor, fontSize = 13.sp)
    Text(
        text = when (current) {
            InferenceMode.LOCAL -> "YOLO TFLite on the phone — no network latency."
            InferenceMode.CLOUD -> "Frames sent over HTTP to the YOLO server."
        },
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )

    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = urlInput,
        onValueChange = { urlInput = it },
        label = { Text("YOLO server URL", fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = peciTextFieldColors(),
    )
    Text(
        text = "Default: ${CloudConfig.DETECT_URL}",
        color = Constants.secondaryTextColor,
        fontSize = 10.sp,
    )

    Spacer(Modifier.height(4.dp))

    // Indicador de estado + botões
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bolinha de estado
        val dotColor = when {
            pinging -> Color(0xFFFFA500)
            serverConnected == true -> Color(0xFF4CAF50)
            serverConnected == false -> Color(0xFFF44336)
            else -> Constants.secondaryTextColor
        }
        Spacer(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = when {
                pinging -> "Connecting..."
                serverConnected == true -> "Connected"
                serverConnected == false -> "No response"
                else -> "Disconnected"
            },
            color = Constants.secondaryTextColor,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        if (current == InferenceMode.CLOUD) {
            // Botão Desligar
            OutlinedButton(
                onClick = { viewModel.disconnectYoloServer() },
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect", color = Constants.primaryTextColor, fontSize = 11.sp) }
        } else {
            // Botão Ligar — faz ping ao /health antes de confirmar
            Button(
                onClick = { pinging = true },
                enabled = !pinging,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                modifier = Modifier.weight(1f),
            ) { Text("Connect to server", color = Color.Black, fontSize = 11.sp) }
        }
    }

    // Ping ao /health quando pinging=true
    if (pinging) {
        LaunchedEffect(urlInput) {
            val healthUrl = urlInput.trim().replace("/detect", "") + "/health"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(healthUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.responseCode == 200
                } catch (e: Exception) {
                    false
                }
            }
            viewModel.yoloServerConnected.value = ok
            if (ok) viewModel.connectYoloServer(urlInput.trim())
            pinging = false
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Depth Anything V2 ────────────────────────────────────────────
    Text("Depth Anything (distance)", color = Constants.primaryTextColor, fontSize = 13.sp)
    Text(
        text = "Estimates object distance. Used by UC1.2 and UC4.1.",
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )
    var depthUrlInput by remember { mutableStateOf(CloudConfig.DEPTH_URL) }
    OutlinedTextField(
        value = depthUrlInput,
        onValueChange = { depthUrlInput = it },
        label = { Text("Depth server URL", fontSize = 11.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = peciTextFieldColors(),
    )
    Button(
        onClick = { viewModel.depthSetEndpoint(depthUrlInput.trim()) },
        colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save Depth URL", color = Color.Black, fontSize = 11.sp) }

}

// ---------------- Voz (STT) ----------------

@Composable
private fun VoiceTab(viewModel: AppViewModel) {
    val connected by viewModel.whisperConnected.collectAsStateWithLifecycle()
    val transcription by viewModel.whisperTranscription.collectAsStateWithLifecycle()
    var runtime by rememberSaveable { mutableStateOf(VoiceRuntime.CLOUD) }
    var serverInput by rememberSaveable { mutableStateOf(CloudConfig.WHISPER_HOST_PORT) }
    val latestCompleted = transcription.lastOrNull { it.completed }?.text?.trim().orEmpty()
    val latestLive = transcription.lastOrNull()?.text?.trim().orEmpty()
    val parsedServer = remember(serverInput) { parseWhisperServer(serverInput) }

    Text("Speech recognition", color = Constants.primaryTextColor, fontSize = 13.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VoiceRuntime.values().forEach { m ->
            FilterChip(
                selected = runtime == m,
                onClick = {
                    runtime = m
                    if (m == VoiceRuntime.LOCAL && connected) viewModel.whisperDisconnect()
                },
                label = {
                    Text(
                        when (m) {
                            VoiceRuntime.CLOUD -> "Cloud"
                            VoiceRuntime.LOCAL -> "Local"
                        },
                        fontSize = 11.sp,
                        color = if (runtime == m) Color.Black else Constants.primaryTextColor,
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
    Text(
        text = when (runtime) {
            VoiceRuntime.CLOUD -> "Sherpa KWS on a local server via WebSocket (host:port)."
            VoiceRuntime.LOCAL -> "No cloud server: keeps processing local/off."
        },
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )

    if (runtime == VoiceRuntime.CLOUD) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = serverInput,
            onValueChange = { serverInput = it },
            label = { Text("Server URL (optional)", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = peciTextFieldColors(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { viewModel.whisperDisconnect() },
                enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect", color = Color.White, fontSize = 11.sp) }
            Button(
                onClick = {
                    val target = parsedServer ?: return@Button
                    viewModel.whisperConnect(target.first, target.second)
                },
                enabled = parsedServer != null && !connected,
                colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                modifier = Modifier.weight(1f),
            ) { Text("Connect server", color = Color.Black, fontSize = 11.sp) }
        }

        if (serverInput.isNotBlank() && parsedServer == null) {
            Text(
                text = "Invalid format. Use host:port or http://host:port",
                color = Constants.warningColor,
                fontSize = 10.sp,
            )
        }

        Text(
            text = if (connected) "Connected to the Sherpa KWS server" else "Server disconnected",
            color = if (connected) Constants.successColor else Constants.secondaryTextColor,
            fontSize = 11.sp,
        )

        if (latestLive.isNotBlank()) {
            Text(
                text = "Live: $latestLive",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
        if (latestCompleted.isNotBlank()) {
            Text(
                text = "Final: $latestCompleted",
                color = Constants.primaryTextColor,
                fontSize = 12.sp,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Glasses microphone", color = Constants.primaryTextColor, fontSize = 13.sp)
    Text(
        "For Sherpa KWS use 16k/16. Applied immediately if the microphone is active.",
        color = Constants.secondaryTextColor,
        fontSize = 10.sp,
    )

    val currentRate by viewModel.glassesMicSampleRateHz.collectAsStateWithLifecycle()
    val currentBits by viewModel.glassesMicBitDepth.collectAsStateWithLifecycle()

    val micProfiles = listOf(
        Triple(8_000,  8,  "8k/8"),
        Triple(8_000,  16, "8k/16"),
        Triple(16_000, 8,  "16k/8"),
        Triple(16_000, 16, "16k/16"),
    )
    micProfiles.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { (rate, bits, label) ->
                FilterChip(
                    selected = currentRate == rate && currentBits == bits,
                    onClick = { viewModel.applyMicProfile(rate, bits) },
                    label = {
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (currentRate == rate && currentBits == bits) Color.Black else Constants.primaryTextColor,
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
    }
}

private fun parseWhisperServer(inputRaw: String): Pair<String, Int>? {
    val input = inputRaw.trim()
    if (input.isBlank()) return null

    if (input.startsWith("http://") || input.startsWith("https://")) {
        return try {
            val uri = java.net.URI(input)
            val host = uri.host?.trim().orEmpty()
            if (host.isBlank()) null else host to (uri.port.takeIf { it in 1..65535 } ?: CloudConfig.KWS_PORT)
        } catch (_: Exception) {
            null
        }
    }

    val hostPort = input.substringBefore('/').trim()
    if (hostPort.isBlank()) return null
    val lastColon = hostPort.lastIndexOf(':')
    if (lastColon > 0 && lastColon < hostPort.length - 1) {
        val host = hostPort.substring(0, lastColon).trim()
        val port = hostPort.substring(lastColon + 1).trim().toIntOrNull()
        return if (host.isNotBlank() && port != null && port in 1..65535) host to port else null
    }
    return hostPort to CloudConfig.KWS_PORT
}

