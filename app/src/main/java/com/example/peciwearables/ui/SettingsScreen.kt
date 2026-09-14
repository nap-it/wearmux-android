package com.example.peciwearables.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.BoxState
import com.example.peciwearables.Constants
import com.example.peciwearables.Tab
import com.example.peciwearables.integration.CloudConfig
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.GlassesNetworkStatusResolver
import com.example.peciwearables.integration.GlassesSettingsStore
import com.example.peciwearables.integration.MlProcessingLocation
import com.example.peciwearables.integration.ble.BleDeviceState

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
    val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()
    val udpActive by viewModel.udpActive.collectAsStateWithLifecycle()
    val udpServerActive by viewModel.udpServerActive.collectAsStateWithLifecycle()
    val glassesIp by viewModel.glassesIp.collectAsStateWithLifecycle()
    val glassesConnectionMode by viewModel.glassesConnectionMode.collectAsStateWithLifecycle()
    val mlProcessingLocation by viewModel.mlProcessingLocation.collectAsStateWithLifecycle()
    val glassesWifiSupported by viewModel.glassesWifiSupported.collectAsStateWithLifecycle()
    val glassesWifiConnectionEnabled by viewModel.glassesWifiConnectionEnabled.collectAsStateWithLifecycle()
    val glassesWifiConnected by viewModel.glassesWifiConnected.collectAsStateWithLifecycle()
    val glassesWifiSecure by viewModel.glassesWifiSecure.collectAsStateWithLifecycle()
    val unifiedServerUrl by viewModel.unifiedServerUrl.collectAsStateWithLifecycle()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsStateWithLifecycle()
    var unifiedUrlInput by remember { mutableStateOf("") }
    LaunchedEffect(unifiedServerUrl) { if (unifiedUrlInput.isEmpty()) unifiedUrlInput = unifiedServerUrl }
    var ssid by remember { mutableStateOf("MEO-4888D0") }
    var password by remember { mutableStateOf("6996fdb6b0") }
    var glassesCameraResolutionInput by remember { mutableStateOf("480") }
    var glassesCameraQualityInput by remember { mutableStateOf("74") }
    var glassesCameraRateInput by remember { mutableStateOf("10") }
    val glassesReady = glassesState == BleDeviceState.READY || glassesState == BleDeviceState.CONNECTED
    val glassesCanSendWifi = glassesReady || udpActive || glassesState == BleDeviceState.CONNECTING
    val glassesNetworkStatus = GlassesNetworkStatusResolver.resolve(
        bleState = glassesState,
        udpServerActive = udpServerActive,
        wifiSessionActive = udpActive,
        glassesIp = glassesIp
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Modo desenvolvimento — sempre visível, controla apenas a
        // visibilidade das ferramentas técnicas abaixo.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Developer mode",
                    color = Constants.primaryTextColor,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Shows technical tools, camera preview, IMU data, endpoints and advanced settings.",
                    color = Constants.secondaryTextColor,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = isDeveloperMode,
                onCheckedChange = { viewModel.setDeveloperMode(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Constants.accentColor),
            )
        }

        if (!isDeveloperMode) {
            Text(
                "Enable Developer mode to access server, camera, BLE and technical log settings.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
        }

        NormalSettingsSection(viewModel)

        if (isDeveloperMode) {
        Text(
            "PECI server",
            style = MaterialTheme.typography.labelMedium,
            color = Constants.secondaryTextColor,
        )
        OutlinedTextField(
            value = unifiedUrlInput,
            onValueChange = { unifiedUrlInput = it },
            label = { Text("Unified Server URL", color = Constants.secondaryTextColor) },
            placeholder = { Text(CloudConfig.DEFAULT_BASE_URL, color = Constants.secondaryTextColor) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Constants.primaryTextColor,
                unfocusedTextColor = Constants.primaryTextColor,
                focusedBorderColor = Constants.accentColor,
                unfocusedBorderColor = Constants.secondaryTextColor,
            ),
        )
        Button(
            onClick = { viewModel.unifiedServerSetUrl(unifiedUrlInput.trim()) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
        ) {
            Text("Save URL", color = Color.Black, fontSize = 13.sp)
        }

        // Painel Watch + Áudio em colapsável (igual a YOLO/STT) — primeira
        // posição na lista, mas só expande quando o utilizador toca.
        com.example.peciwearables.ui.ExpandableSection(
            title = "Galaxy Watch + Audio",
            subtitle = "IMU stream, vibrations, sounds on the phone",
            initiallyExpanded = false,
        ) {
            com.example.peciwearables.ui.WatchAndAudioPanel(viewModel)
        }

        // "Modo de ligação" e "Processamento IMU" foram retirados —
        // o modo está agora no DeviceConnectionDialog (engrenagem ⚙ no card
        // dos Omi); o processamento ficou unificado num único dialog.
        var showProcessingDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showProcessingDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Processing (IMU · Camera · Voice)…", color = Color.Black, fontSize = 13.sp)
        }
        if (showProcessingDialog) {
            com.example.peciwearables.ui.ProcessingDialog(
                viewModel = viewModel,
                onDismiss = { showProcessingDialog = false },
            )
        }

        // STT avançado (API key, URL, fonte) foi fundido na tab Voz do
        // dialog "Processamento" — evita duplicação na Settings.

        // Câmara dos óculos: presets escondidos num dialog para não poluir a Settings.
        var showCameraDialog by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showCameraDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Configure glasses camera…", color = Constants.primaryTextColor)
        }
        if (showCameraDialog) {
            com.example.peciwearables.ui.GlassesCameraProfileDialog(
                viewModel = viewModel,
                initialResolution = glassesCameraResolutionInput.toIntOrNull() ?: 480,
                initialQuality = glassesCameraQualityInput.toIntOrNull() ?: 74,
                initialRateMs = glassesCameraRateInput.toIntOrNull() ?: 10,
                onDismiss = { showCameraDialog = false },
            )
        }

        // Wi-Fi inline removido daqui — agora vive no DeviceConnectionDialog
        // (engrenagem ⚙ no card dos Omi na tab Info). Mantemos apenas o
        // atalho rápido "Reconnect UDP" para quando a sessão cai.
        OutlinedButton(
            onClick = { viewModel.connectWifi() },
            enabled = !udpActive && glassesCanSendWifi,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reconnect UDP", fontSize = 12.sp) }

        if (glassesNetworkStatus.wifiSessionActive) {
            Text(
                text = glassesNetworkStatus.sessionText,
                color = Color(0xFF4CAF50),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = glassesNetworkStatus.sessionText,
                color = Constants.secondaryTextColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Desligar dispositivos já ligados — só aparece quando há algo ligado.
        val glassesConnected = glassesState != BleDeviceState.DISCONNECTED && glassesState != BleDeviceState.ERROR
        val wristbandConnected = wristbandState != BleDeviceState.DISCONNECTED && wristbandState != BleDeviceState.ERROR
        if (glassesConnected || wristbandConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (glassesConnected) {
                    OutlinedButton(
                        onClick = { viewModel.disconnectGlasses() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor)
                    ) { Text("Disconnect glasses", fontSize = 12.sp) }
                }
                if (wristbandConnected) {
                    OutlinedButton(
                        onClick = { viewModel.disconnectWristband() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor)
                    ) { Text("Disconnect wristband", fontSize = 12.sp) }
                }
            }
        }

        // (Os botões "Ligar Omi" / "Ligar Sole" foram removidos daqui — já
        // existem nos cards da tab Info, evitando duplicação.)

        // Info de rede compactada numa linha só.
        Text(
            text = "IP ${viewModel.getLocalIp()} · UDP 3000 · Server ${if (glassesNetworkStatus.udpServerActive) "active" else "inactive"}",
            color = if (glassesNetworkStatus.udpServerActive) Constants.successColor else Constants.secondaryTextColor,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Avançado — controlo de serviço + log BLE colapsados por defeito.
        com.example.peciwearables.ui.ExpandableSection(
            title = "Advanced",
            subtitle = "Service control and BLE log",
            initiallyExpanded = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.startService() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.accentColor)
                ) { Text("Start service", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { viewModel.stopService() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor)
                ) { Text("Stop service", fontSize = 12.sp) }
            }

            val bleLog by viewModel.bleLog.collectAsStateWithLifecycle()
            val clipboardManager = LocalClipboardManager.current
            val ctx = LocalContext.current

            if (bleLog.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1117))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = bleLog,
                        color = Color(0xFF00E676),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(bleLog))
                            Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
                        },
                        enabled = bleLog.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Constants.neutralButton),
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy", color = Color.White, fontSize = 11.sp) }
                    Button(
                        onClick = { viewModel.clearBleLog() },
                        colors = ButtonDefaults.buttonColors(containerColor = Constants.mutedButton),
                        modifier = Modifier.weight(1f)
                    ) { Text("Clear", color = Color.White, fontSize = 11.sp) }
                }
            } else {
                Text(
                    "No entries in the BLE log.",
                    color = Constants.secondaryTextColor,
                    fontSize = 11.sp,
                )
            }
        }
        }
    }
}

@Composable
private fun NormalSettingsSection(viewModel: AppViewModel) {
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.soundEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showForgetConfirm by remember { mutableStateOf(false) }

    Text("ALERTS", style = MaterialTheme.typography.labelMedium, color = Constants.secondaryTextColor)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Vibration", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleSmall)
            Text(
                "Vibration alerts on the watch and wristband.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
        Switch(
            checked = vibrationEnabled,
            onCheckedChange = { viewModel.setVibrationEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Constants.accentColor),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Sound", color = Constants.primaryTextColor, style = MaterialTheme.typography.titleSmall)
            Text(
                "Sound alerts and voice notifications.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
        }
        Switch(
            checked = soundEnabled,
            onCheckedChange = { viewModel.setSoundEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = Constants.accentColor),
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text("NOTIFICATIONS", style = MaterialTheme.typography.labelMedium, color = Constants.secondaryTextColor)
    Text(
        "Receive notifications on the phone — coming soon.",
        color = Constants.secondaryTextColor,
        fontSize = 12.sp,
    )

    Spacer(modifier = Modifier.height(4.dp))
    Text("DEVICES", style = MaterialTheme.typography.labelMedium, color = Constants.secondaryTextColor)

    OutlinedButton(
        onClick = { viewModel.setTab(Tab.DEVICES) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Manage devices", color = Constants.primaryTextColor) }

    OutlinedButton(
        onClick = { showForgetConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor),
    ) { Text("Forget devices") }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text("Forget devices") },
            text = { Text("This removes the saved glasses camera profile. The devices remain paired in the phone's Bluetooth settings.") },
            confirmButton = {
                TextButton(onClick = {
                    GlassesSettingsStore(context).saveDesiredSettings(
                        com.example.peciwearables.integration.GlassesSettings(
                            resolution = null,
                            qualityFactor = null,
                            cameraRateMs = null,
                        )
                    )
                    Toast.makeText(context, "Glasses preferences cleared", Toast.LENGTH_SHORT).show()
                    showForgetConfirm = false
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
}
