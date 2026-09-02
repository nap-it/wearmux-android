package com.example.peciwearables.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.peciwearables.AppViewModel
import com.example.peciwearables.Constants
import com.example.peciwearables.integration.pdr.SavedRoute
import com.example.peciwearables.integration.safety.haversineMeters

@Composable
fun RouteMapPreview(
    waypoints: List<Pair<Double, Double>>,
    currentPosition: Pair<Double, Double>?,
    label: String,
    accentColor: Color,
    heading: Double = 0.0,
    stepCount: Int = 0,
    accuracy: Float = 0f,
    speed: Float = 0f,
    source: String = "",
) {
    if (waypoints.isEmpty() && currentPosition == null) return

    // Pulsação para o marcador de posição actual
    val infiniteTransition = rememberInfiniteTransition(label = "pdr_pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_radius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val allPoints = buildList {
        addAll(waypoints)
        currentPosition?.let { add(it) }
    }
    val minLat = allPoints.minOf { it.first }
    val maxLat = allPoints.maxOf { it.first }
    val minLon = allPoints.minOf { it.second }
    val maxLon = allPoints.maxOf { it.second }
    val minVisibleSpan = 2e-5
    val latSpan = (maxLat - minLat).coerceAtLeast(minVisibleSpan)
    val lonSpan = (maxLon - minLon).coerceAtLeast(minVisibleSpan)

    // Calcular distância total do trajeto (metros)
    val totalDistanceM = remember(waypoints) {
        if (waypoints.size < 2) 0.0
        else waypoints.zipWithNext().sumOf { (a, b) ->
            haversineMeters(a.first, a.second, b.first, b.second)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101014))
            .border(1.dp, Color(0xFF2A2A2E), RoundedCornerShape(12.dp))
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Header com label
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181C))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            if (source.isNotEmpty()) {
                Text(
                    text = source.uppercase(),
                    color = when (source) {
                        "gps" -> Color(0xFF4CAF50)
                        "pdr" -> Color(0xFFFF9800)
                        "fused" -> Color(0xFF2196F3)
                        else -> Constants.secondaryTextColor
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Canvas do mapa
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .background(Color(0xFF0D0D11))
        ) {
            val pad = 16.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad

            fun project(p: Pair<Double, Double>): Offset {
                val x = pad + ((p.second - minLon) / lonSpan * w).toFloat()
                val y = pad + (h - (p.first - minLat) / latSpan * h).toFloat()
                return Offset(x, y)
            }

            // Grelha 6x6 fina
            val gridColor = Color(0xFF1C1C22)
            val gridLines = 6
            for (i in 0..gridLines) {
                val gx = pad + w * i / gridLines
                val gy = pad + h * i / gridLines
                drawLine(gridColor, Offset(gx, pad), Offset(gx, pad + h), 0.5f)
                drawLine(gridColor, Offset(pad, gy), Offset(pad + w, gy), 0.5f)
            }

            // Eixos (mais visíveis)
            val axisColor = Color(0xFF2A2A30)
            drawLine(axisColor, Offset(pad, pad + h), Offset(pad + w, pad + h), 1.5f)
            drawLine(axisColor, Offset(pad, pad), Offset(pad, pad + h), 1.5f)

            // Trail glow (linha larga translúcida por baixo)
            if (waypoints.size >= 2) {
                val glowPath = Path().apply {
                    val first = project(waypoints.first())
                    moveTo(first.x, first.y)
                    waypoints.drop(1).forEach { p ->
                        val o = project(p)
                        lineTo(o.x, o.y)
                    }
                }
                // Glow exterior
                drawPath(
                    path = glowPath,
                    color = accentColor.copy(alpha = 0.15f),
                    style = Stroke(width = 12f, cap = StrokeCap.Round)
                )
                // Glow médio
                drawPath(
                    path = glowPath,
                    color = accentColor.copy(alpha = 0.3f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
                // Linha sólida principal
                drawPath(
                    path = glowPath,
                    color = accentColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }

            // Marcador de início (verde com anel)
            waypoints.firstOrNull()?.let {
                val c = project(it)
                drawCircle(Color(0xFF4CAF50).copy(alpha = 0.25f), radius = 10f, center = c)
                drawCircle(Color(0xFF4CAF50), radius = 5f, center = c)
                drawCircle(Color.White, radius = 2f, center = c)
            }

            // Marcador de fim
            if (waypoints.size >= 2) {
                val c = project(waypoints.last())
                drawCircle(accentColor.copy(alpha = 0.25f), radius = 10f, center = c)
                drawCircle(accentColor, radius = 5f, center = c)
            }

            // Posição actual PDR com pulsação e seta de heading
            currentPosition?.let { pos ->
                val c = project(pos)
                // Pulso animado
                drawCircle(Color(0xFFFF5252).copy(alpha = pulseAlpha), radius = pulseRadius, center = c)
                // Círculo de accuracy (se disponível)
                if (accuracy > 0f) {
                    val accRadius = (accuracy / (latSpan.toFloat() * 111_000f) * h).coerceIn(4f, w / 3f)
                    drawCircle(
                        Color(0xFFFF5252).copy(alpha = 0.08f),
                        radius = accRadius,
                        center = c
                    )
                }
                // Ponto sólido
                drawCircle(Color(0xFFFF5252), radius = 7f, center = c)
                drawCircle(Color.White, radius = 3f, center = c)

                // Seta de heading
                if (heading != 0.0 || waypoints.size > 1) {
                    val arrowLen = 20f
                    val headingRad = Math.toRadians(heading)
                    // heading: 0=Norte (cima), 90=Este (direita)
                    val dx = (arrowLen * kotlin.math.sin(headingRad)).toFloat()
                    val dy = (-arrowLen * kotlin.math.cos(headingRad)).toFloat()
                    drawLine(
                        Color(0xFFFF5252),
                        start = c,
                        end = Offset(c.x + dx, c.y + dy),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // Overlay de dados em tempo real 
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181C))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            currentPosition?.let { pos ->
                Text(
                    text = "\"timestamp\": ${System.currentTimeMillis()},",
                    color = Color(0xFF6A6A7A),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "\"position\": { %.6f, %.6f },".format(pos.first, pos.second),
                    color = Color(0xFF8A8AFF),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "\"heading\": %.1f,  \"speed\": %.2f,".format(heading, speed),
                    color = Color(0xFF8AFF8A),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "steps: $stepCount",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "dist: %.1fm".format(totalDistanceM),
                    color = accentColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (accuracy > 0f) {
                    Text(
                        text = "acc: %.1fm".format(accuracy),
                        color = Color(0xFFFF9800),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun SavedRouteRow(
    route: SavedRoute,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Constants.cardBackground)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = route.name,
                color = Constants.primaryTextColor,
                fontSize = 14.sp
            )
            Text(
                text = "${route.waypoints.size} waypoints" + if (isActive) " • activo" else "",
                color = Constants.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        if (isActive) {
            TextButton(onClick = onDeactivate) { Text("Desactivar") }
        } else {
            TextButton(onClick = onActivate) { Text("Activar") }
        }
        TextButton(onClick = onDelete) {
            Text("Eliminar", color = Color(0xFFE57373))
        }
    }
}
