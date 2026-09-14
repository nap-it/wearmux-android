package com.example.peciwearables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable

enum class BoxState {
    NONE,
    INFO,
    GOOD,
    BAD,
    WARNING,
    FULL_BATTERY,
    MID_BATTERY,
    LOW_BATTERY,
    CHARGING_BATTERY,
}
@Composable
fun InfoBox(
    title: String,
    description: String,
    icon: Any,
    state: BoxState = BoxState.NONE,
    modalContent: List<ModalContent>? = null
) {
    val iconSize = 32.dp
    val circleSize = 48.dp
    var showModal by remember { mutableStateOf(false) }

    if (showModal && modalContent != null) {
        InfoBoxModal(
            title = title,
            content = modalContent,
            onDismiss = { showModal = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Constants.cardBackground)
            .then(
                if (modalContent != null) Modifier.clickable { showModal = true }
                else Modifier
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    )
    {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(Constants.accentColor.copy(alpha = 0.15f))
        ) {
            Icon(
                painter = when (icon) {
                    is ImageVector -> rememberVectorPainter(icon)
                    else -> painterResource(icon as Int)
                },
                contentDescription = title,
                tint = Constants.accentColor,
                modifier = Modifier.size(iconSize)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Title + Description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Constants.primaryTextColor,
                fontSize = 16.sp,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = Constants.secondaryTextColor,
                fontSize = 13.sp,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state != BoxState.NONE) {
            Spacer(modifier = Modifier.width(8.dp))

            val stateIcon: Any
            val stateColor: Color

            when (state) {
                BoxState.INFO -> {
                    stateIcon = R.drawable.circle_info_solid_full
                    stateColor = Color(0xFF2196F3)
                }
                BoxState.GOOD -> {
                    stateIcon = R.drawable.circle_check_solid_full
                    stateColor = Color(0xFF4CAF50)
                }
                BoxState.BAD -> {
                    stateIcon = R.drawable.circle_xmark_solid_full
                    stateColor = Color(0xFFF44336)
                }
                BoxState.WARNING -> {
                    stateIcon = R.drawable.circle_exclamation_solid_full
                    stateColor = Color(0xFFFF9800)
                }
                BoxState.FULL_BATTERY -> {
                    stateIcon = R.drawable.battery_full
                    stateColor = Color(0xFF4CAF50) // green
                }
                BoxState.MID_BATTERY -> {
                    stateIcon = R.drawable.battery_mid
                    stateColor = Color(0xFFFF9800) // orange
                }
                BoxState.LOW_BATTERY -> {
                    stateIcon = R.drawable.battery_low
                    stateColor = Color(0xFFF44336) // red
                }
                BoxState.CHARGING_BATTERY -> {
                    stateIcon = R.drawable.battery_bolt
                    stateColor = Color(0xFF4CAF50) // green
                }
                BoxState.NONE -> return@Row
            }

            Icon(
                painter = when (stateIcon) {
                    is ImageVector -> rememberVectorPainter(stateIcon)
                    else -> painterResource(stateIcon as Int)
                },
                contentDescription = state.name,
                tint = stateColor,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}