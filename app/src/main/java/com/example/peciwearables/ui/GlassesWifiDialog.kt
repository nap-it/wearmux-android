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
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.network.WifiInspector


@Composable
fun GlassesWifiDialog(
    viewModel: AppViewModel,
    initialPassword: String = "",
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var detectedSsid by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf(initialPassword) }
    var manualSsid by remember { mutableStateOf("") }

    // Detecta o SSID actual no momento em que o dialog abre, e re-detecta
    // sempre que voltamos do ecrã de Definições do Android (ON_RESUME).
    LaunchedEffect(Unit) {
        detectedSsid = WifiInspector.currentSsid(context)
    }

    val effectiveSsid = detectedSsid ?: manualSsid.ifBlank { null }

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
                "Wi-Fi nos óculos",
                color = Constants.primaryTextColor,
                fontSize = 16.sp,
            )
            Text(
                "Os óculos ligam-se à mesma rede que o telemóvel. Só precisas de meter a password.",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp,
            )

            // Card de SSID — leitura + atalho
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Constants.cardBackgroundElevated)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rede do telemóvel",
                        color = Constants.secondaryTextColor,
                        fontSize = 10.sp,
                    )
                    Text(
                        text = detectedSsid ?: "— sem ligação Wi-Fi —",
                        color = if (detectedSsid != null) Constants.primaryTextColor else Constants.warningColor,
                        fontSize = 13.sp,
                    )
                }
                OutlinedButton(
                    onClick = {
                        WifiInspector.openSystemWifiSettings(context)
                        // Quando voltar à app, refaz a detecção via LaunchedEffect.
                    },
                ) { Text("Mudar rede", fontSize = 11.sp) }
            }

            if (detectedSsid == null) {
                // Fallback manual — útil se o sistema mascarar o SSID.
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

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancelar") }
                Button(
                    onClick = {
                        val s = effectiveSsid ?: return@Button
                        viewModel.sendWifiConfig(s, password)
                        viewModel.connectWifi()
                        onDismiss()
                    },
                    enabled = effectiveSsid != null && password.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Constants.accentColor),
                    modifier = Modifier.weight(1f),
                ) { Text("Enviar e ligar", color = Color.Black) }
            }
        }
    }
}


