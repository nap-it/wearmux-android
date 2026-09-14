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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.GlassesConnectionMode
import com.example.peciwearables.integration.ble.BleDeviceState
import com.example.peciwearables.integration.network.WifiInspector
import com.example.peciwearables.integration.watch.WatchClient

/** Dispositivo que pode ser configurado com este dialog. */
enum class ConnectableDevice {
    GLASSES,
    WRISTBAND,
    GALAXY_WATCH,
    ESP32_CAM,
}


@Composable
fun DeviceConnectionDialog(
    viewModel: AppViewModel,
    device: ConnectableDevice,
    onDismiss: () -> Unit,
) {
    val title = when (device) {
        ConnectableDevice.GLASSES -> "Glasses"
        ConnectableDevice.WRISTBAND -> "Wristband"
        ConnectableDevice.GALAXY_WATCH -> "Galaxy Watch"
        ConnectableDevice.ESP32_CAM -> "ESP32 Camera (Manual Wi-Fi)"
    }
    val supportsWifi = device == ConnectableDevice.GLASSES
    val tabs = if (supportsWifi) listOf("BLE", "Wi-Fi") else listOf("BLE")
    var selectedTab by remember { mutableStateOf(0) }

    // Quando o dialog abre, sincroniza a tab seleccionada com o modo actual
    // dos óculos — assim quem já estava em Wi-Fi vê a tab Wi-Fi por defeito.
    val glassesMode by viewModel.glassesConnectionMode.collectAsStateWithLifecycle()
    LaunchedEffect(device, glassesMode) {
        if (supportsWifi) selectedTab = if (glassesMode == GlassesConnectionMode.WIFI) 1 else 0
    }

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
            Text(title, color = Constants.primaryTextColor, fontSize = 16.sp)

            // TabRow só faz sentido quando há mais que 1 transport (Omi).
            // Para pulseira/watch que só têm BLE renderizamos directamente.
            if (tabs.size > 1) {
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
                    "BLE" -> BleTab(viewModel, device)
                    "Wi-Fi" -> WifiTab(viewModel)
                }
            } else {
                // 1 transport: render directo, sem cabeçalho de tab.
                BleTab(viewModel, device)
            }

            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
        }
    }
}

@Composable
private fun BleTab(viewModel: AppViewModel, device: ConnectableDevice) {
    when (device) {
        ConnectableDevice.GLASSES -> {
            val glassesState by viewModel.glassesState.collectAsStateWithLifecycle()
            BleSection(
                state = glassesState.label(),
                color = glassesState.color(),
                primaryLabel = if (glassesState == BleDeviceState.CONNECTED || glassesState == BleDeviceState.READY)
                    "Reconnect" else "Connect via BLE",
                onPrimary = {
                    viewModel.setGlassesConnectionMode(GlassesConnectionMode.BLE)
                    viewModel.connectGlasses()
                },
                showDisconnect = glassesState != BleDeviceState.DISCONNECTED,
                onDisconnect = { viewModel.disconnectGlasses() },
            )
        }
        ConnectableDevice.WRISTBAND -> {
            val wristbandState by viewModel.wristbandState.collectAsStateWithLifecycle()
            BleSection(
                state = wristbandState.label(),
                color = wristbandState.color(),
                primaryLabel = if (wristbandState == BleDeviceState.CONNECTED || wristbandState == BleDeviceState.READY)
                    "Reconnect" else "Connect via BLE",
                onPrimary = { viewModel.connectWristband() },
                showDisconnect = wristbandState != BleDeviceState.DISCONNECTED,
                onDisconnect = { viewModel.disconnectWristband() },
            )
        }
        ConnectableDevice.ESP32_CAM -> {
            Text(
                text = "First connect the phone's Wi-Fi to the 'ESP32-CAM' network.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
            BleSection(
                state = "Manual",
                color = Constants.accentColor,
                primaryLabel = "Connect via Wi-Fi",
                onPrimary = { viewModel.connectEsp32() },
            )
        }
        ConnectableDevice.GALAXY_WATCH -> {
            val watchState by viewModel.watchState.collectAsStateWithLifecycle()
            val name by viewModel.watchName.collectAsStateWithLifecycle()
            val battery by viewModel.watchBattery.collectAsStateWithLifecycle()
            Text(
                text = "Transport: Bluetooth paired through the Galaxy Wearable app.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )
            BleSection(
                state = describeWatch(watchState, name, battery),
                color = colorFor(watchState),
                primaryLabel = if (watchState == WatchClient.State.STREAMING) "Stop IMU" else "Start IMU",
                // Só permitimos start/stop quando o watch está mesmo
                // alcançável por Bluetooth — em REMOTE as mensagens dão timeout.
                primaryEnabled = watchState == WatchClient.State.AVAILABLE ||
                    watchState == WatchClient.State.STREAMING,
                onPrimary = {
                    if (watchState == WatchClient.State.STREAMING) viewModel.watchStopImu()
                    else viewModel.watchStartImu()
                },
            )
            when (watchState) {
                WatchClient.State.DISCONNECTED -> Text(
                    text = "For the watch to show up here, install Galaxy Wearable on the phone and pair the Galaxy Watch 8.",
                    color = Constants.warningColor,
                    fontSize = 11.sp,
                )
                WatchClient.State.REMOTE -> Text(
                    text = "The system sees the watch on the Google account but it is out of Bluetooth range. Move closer (~10 m) for messages to work.",
                    color = Constants.warningColor,
                    fontSize = 11.sp,
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun BleSection(
    state: String,
    color: Color,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    showDisconnect: Boolean = false,
    onDisconnect: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Constants.cardBackgroundElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = "Status",
            color = Constants.secondaryTextColor,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        Text(text = state, color = color, fontSize = 13.sp)
    }
    Button(
        onClick = onPrimary,
        enabled = primaryEnabled,
        colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(primaryLabel, color = Color.Black, fontSize = 12.sp) }
    if (showDisconnect) {
        OutlinedButton(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Constants.errorColor),
        ) { Text("Disconnect", fontSize = 12.sp) }
    }
}

@Composable
private fun WifiTab(viewModel: AppViewModel) {
    val context = LocalContext.current
    val glassesCanSendWifi by remember(viewModel) {
        // glassesCanSendWifi não está exposto no VM — derivamos do state.
        viewModel.glassesState
    }.collectAsStateWithLifecycle()
    val glassesReady = glassesCanSendWifi == BleDeviceState.READY ||
        glassesCanSendWifi == BleDeviceState.CONNECTED ||
        glassesCanSendWifi == BleDeviceState.CONNECTING

    var detectedSsid by remember { mutableStateOf<String?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        detectedSsid = WifiInspector.currentSsid(context)
    }

    Text(
        text = "The glasses will join the same network as the phone.",
        color = Constants.secondaryTextColor,
        fontSize = 11.sp,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Constants.cardBackgroundElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Phone network", color = Constants.secondaryTextColor, fontSize = 10.sp)
            Text(
                text = detectedSsid ?: "— no Wi-Fi connection —",
                color = if (detectedSsid != null) Constants.primaryTextColor else Constants.warningColor,
                fontSize = 13.sp,
            )
        }
        OutlinedButton(
            onClick = { WifiInspector.openSystemWifiSettings(context) },
        ) { Text("Change network", fontSize = 11.sp) }
    }

    if (detectedSsid == null) {
        OutlinedTextField(
            value = manualSsid,
            onValueChange = { manualSsid = it },
            label = { Text("SSID manual") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = peciTextFieldColors(),
        )
    }

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password Wi-Fi") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        colors = peciTextFieldColors(),
    )

    val ssid = detectedSsid ?: manualSsid.ifBlank { null }
    Button(
        onClick = {
            val s = ssid ?: return@Button
            viewModel.setGlassesConnectionMode(GlassesConnectionMode.WIFI)
            viewModel.sendWifiConfig(s, password)
            viewModel.connectWifi()
        },
        enabled = ssid != null && password.isNotBlank() && glassesReady,
        colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (glassesReady) "Send network and connect Wi-Fi" else "Connect the glasses over BLE first",
            color = Color.Black,
            fontSize = 12.sp,
        )
    }
}

// ---- helpers ----

// (textFieldColors local removido — usar `peciTextFieldColors()` de PeciComponents)

private fun BleDeviceState.label(): String = when (this) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> "Connected"
    BleDeviceState.CONNECTING -> "Connecting"
    BleDeviceState.DISCOVERING -> "Discovering services"
    BleDeviceState.CONFIGURING -> "Configuring"
    BleDeviceState.ERROR -> "Error"
    BleDeviceState.DISCONNECTED -> "Disconnected"
}

private fun BleDeviceState.color(): Color = when (this) {
    BleDeviceState.CONNECTED, BleDeviceState.READY -> Constants.successColor
    BleDeviceState.CONNECTING, BleDeviceState.DISCOVERING, BleDeviceState.CONFIGURING -> Constants.warningColor
    BleDeviceState.ERROR -> Constants.errorColor
    BleDeviceState.DISCONNECTED -> Constants.idleColor
}

private fun describeWatch(state: WatchClient.State, name: String?, battery: Int): String {
    val batteryStr = if (battery >= 0) "$battery%" else "—"
    val core = when (state) {
        WatchClient.State.STREAMING -> "Streaming"
        WatchClient.State.AVAILABLE -> "Ready"
        WatchClient.State.REMOTE -> "Out of range (cloud)"
        WatchClient.State.ERROR -> "Error"
        WatchClient.State.DISCONNECTED -> "No connection"
    }
    val tail = listOfNotNull(name, "bat $batteryStr").joinToString(" · ")
    return "$core · $tail"
}

private fun colorFor(state: WatchClient.State): Color = when (state) {
    WatchClient.State.STREAMING -> Constants.successColor
    WatchClient.State.AVAILABLE -> Constants.accentColor
    WatchClient.State.REMOTE -> Constants.warningColor
    WatchClient.State.ERROR -> Constants.errorColor
    WatchClient.State.DISCONNECTED -> Constants.idleColor
}

