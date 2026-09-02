package com.example.peciwearables.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.protocol.GlassesImuSample
import com.example.peciwearables.integration.wearable.devices.omi.quaternionToYawDeg

@Composable
fun HeadTrackingCard(
    latestGlassesImu: GlassesImuSample?,
    latestGlassesQuaternion: FloatArray?,
    imuStreamingEnabled: Boolean,
    onToggleImu: (Boolean) -> Unit,
    gzIntegralDeg: Float? = null,
    gzSpreadDeg: Float? = null,
) {
    val quat = latestGlassesQuaternion
    var roll = 0f
    var pitch = 0f
    var yaw = 0f
    val hasQuat = quat != null && quat.size == 4
    if (hasQuat) {
        val qx = quat!![0]; val qy = quat[1]; val qz = quat[2]; val qw = quat[3]
        yaw = quaternionToYawDeg(qx, qy, qz, qw)
        val sinrCosp = 2f * (qw * qx + qy * qz)
        val cosrCosp = 1f - 2f * (qx * qx + qy * qy)
        roll = Math.toDegrees(kotlin.math.atan2(sinrCosp, cosrCosp).toDouble()).toFloat()
            .coerceIn(-60f, 60f)
        val sinp = 2f * (qw * qy - qz * qx)
        pitch = Math.toDegrees(
            if (kotlin.math.abs(sinp) >= 1f) (kotlin.math.PI / 2 * kotlin.math.sign(sinp)).toDouble()
            else kotlin.math.asin(sinp).toDouble()
        ).toFloat().coerceIn(-45f, 45f)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Head Tracking (BNO085)",
                color = Constants.primaryTextColor,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (imuStreamingEnabled) "ON" else "OFF",
                    color = if (imuStreamingEnabled) Constants.accentColor else Constants.secondaryTextColor,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { onToggleImu(!imuStreamingEnabled) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (imuStreamingEnabled) Color(0xFFE53935) else Constants.accentColor,
                    ),
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(if (imuStreamingEnabled) "Stop" else "Start", fontSize = 11.sp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Constants.cardBackground).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !imuStreamingEnabled -> Text(
                    "Ativa o IMU para ver head tracking",
                    color = Constants.secondaryTextColor, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                !hasQuat -> Text(
                    "A aguardar quaternion...",
                    color = Constants.secondaryTextColor, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                else -> HeadTrackingVisual(quat!!, yaw, pitch, roll, latestGlassesImu, gzIntegralDeg, gzSpreadDeg)
            }
        }
    }
}

@Composable
private fun HeadTrackingVisual(
    quat: FloatArray,
    yaw: Float,
    pitch: Float,
    roll: Float,
    imu: GlassesImuSample?,
    gzIntegralDeg: Float?,
    gzSpreadDeg: Float?,
) {
    val accentColor = Constants.accentColor
    val gridColor = Color.White.copy(alpha = 0.10f)
    val pitchLineColor = Color(0xFF42A5F5)
    val rollLineColor = Color(0xFFAB47BC)
    val noseDotColor = Color(0xFFFF7043)

    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f * 0.85f

        drawCircle(gridColor, radius, Offset(cx, cy), style = Stroke(1.5f))
        drawCircle(gridColor, radius * 0.5f, Offset(cx, cy), style = Stroke(1f))
        drawLine(gridColor, Offset(cx - radius, cy), Offset(cx + radius, cy), strokeWidth = 1f)
        drawLine(gridColor, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeWidth = 1f)

        rotate(degrees = -yaw, pivot = Offset(cx, cy)) {
            val headW = radius * 0.6f
            val headH = radius * 0.8f
            drawOval(
                accentColor.copy(alpha = 0.25f),
                topLeft = Offset(cx - headW, cy - headH),
                size = Size(headW * 2f, headH * 2f),
                style = Stroke(2.5f),
            )
            val pitchOffset = (pitch / 90f) * radius * 0.6f
            drawCircle(noseDotColor, 10f, Offset(cx, cy - headH * 0.5f + pitchOffset))
            val rollLen = headW * 0.7f
            val rollRad = Math.toRadians(roll.toDouble()).toFloat()
            val rlx = kotlin.math.cos(rollRad) * rollLen
            val rly = kotlin.math.sin(rollRad) * rollLen
            drawLine(
                rollLineColor, Offset(cx - rlx, cy + rly), Offset(cx + rlx, cy - rly),
                strokeWidth = 2.5f, cap = StrokeCap.Round,
            )
        }

        val yawRad = Math.toRadians((-yaw).toDouble()).toFloat()
        val arrowLen = radius * 0.95f
        val arrowX = cx + kotlin.math.sin(yawRad) * arrowLen
        val arrowY = cy - kotlin.math.cos(yawRad) * arrowLen
        drawLine(
            accentColor, Offset(cx, cy), Offset(arrowX, arrowY),
            strokeWidth = 2f, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
        drawCircle(accentColor, 5f, Offset(arrowX, arrowY))
    }

    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        AxisReadout("YAW", yaw, accentColor)
        AxisReadout("PITCH", pitch, pitchLineColor)
        AxisReadout("ROLL", roll, rollLineColor)
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = "q = [${quat.joinToString(", ") { "%.3f".format(it) }}]",
        color = Constants.secondaryTextColor, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    if (imu != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Accel ${"%.1f".format(imu.ax)}/${"%.1f".format(imu.ay)}/${"%.1f".format(imu.az)}   " +
                "Gyro ${"%.1f".format(imu.gx)}/${"%.1f".format(imu.gy)}/${"%.1f".format(imu.gz)}",
            color = Constants.secondaryTextColor, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GyroRotationCard(imu.gy, gzIntegralDeg ?: 0f, gzSpreadDeg ?: 0f)
    }
}

@Composable
private fun AxisReadout(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
            "%+.1f".format(value), color = Constants.primaryTextColor,
            fontSize = 14.sp, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun GyroRotationCard(gy: Float, integralDeg: Float, spreadDeg: Float) {
    val spreadColor = if (spreadDeg >= 60f) Color(0xFF2E7D32) else Constants.secondaryTextColor
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Rotação Horizontal (gy)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "gy agora:  %+.1f °/s".format(gy),
                color = Constants.secondaryTextColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            Text(
                "Integral:  %+.1f °".format(integralDeg),
                color = Constants.secondaryTextColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
            Text(
                "Spread 8s: %+.1f °  [limiar: 60°]".format(spreadDeg),
                color = spreadColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                fontWeight = if (spreadDeg >= 60f) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
