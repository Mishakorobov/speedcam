package com.speedcam.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.speedcam.wear.model.WearTelemetry

/**
 * High-contrast, glanceable speedometer and hazard display for motorcycle riders on Wear OS.
 */
@Composable
fun WearSpeedometerScreen(
    telemetry: WearTelemetry,
    onOpenSettings: () -> Unit,
    onToggleService: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ringColor by animateColorAsState(
        targetValue = resolveRingColor(telemetry),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "ringColor",
    )

    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        SpeedometerOuterRing(telemetry = telemetry, ringColor = ringColor)
        SpeedometerCenterCluster(
            telemetry = telemetry,
            onOpenSettings = onOpenSettings,
            onToggleService = onToggleService,
            onToggleMute = onToggleMute,
        )
    }
}

@Composable
private fun SpeedometerOuterRing(
    telemetry: WearTelemetry,
    ringColor: Color,
) {
    val transition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseAlpha",
    )

    val strokeWidth = if (telemetry.overLimit) 10f else 6f
    val finalColor = if (telemetry.overLimit) ringColor.copy(alpha = pulseAlpha) else ringColor

    Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
        drawCircle(
            color = finalColor,
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun SpeedometerCenterCluster(
    telemetry: WearTelemetry,
    onOpenSettings: () -> Unit,
    onToggleService: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        TopHazardBadge(telemetry = telemetry)
        CentralSpeedReadout(telemetry = telemetry)
        BottomControlActions(
            telemetry = telemetry,
            onOpenSettings = onOpenSettings,
            onToggleService = onToggleService,
            onToggleMute = onToggleMute,
        )
    }
}

@Composable
private fun TopHazardBadge(telemetry: WearTelemetry) {
    val distance = telemetry.nearestDistanceM
    val limit = telemetry.speedLimitKmh
    val inSpecs = telemetry.inSpecsZone

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            distance != null -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (telemetry.overLimit) Color(0xFFFF1744) else Color(0xFFFFB300),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "${distance}m",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            inSpecs -> {
                Text(
                    text = "SPECS AVG",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF00E5FF),
                )
            }
            telemetry.running -> {
                Text(
                    text = "RADAR ACTIVE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF10B981),
                )
            }
            else -> {
                Text(
                    text = "OFFLINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                )
            }
        }
    }
}

@Composable
private fun CentralSpeedReadout(telemetry: WearTelemetry) {
    val speedColor = if (telemetry.overLimit) Color(0xFFFF1744) else Color.White

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${telemetry.speedKmh}",
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                color = speedColor,
                lineHeight = 46.sp,
            )
            Text(
                text = "KM/H",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF94A3B8),
            )
        }

        telemetry.speedLimitKmh?.let { limit ->
            SpeedLimitBadge(limit = limit)
        }
    }
}

@Composable
private fun SpeedLimitBadge(limit: Int) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(3.5.dp, Color(0xFFDC2626), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$limit",
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            color = Color.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BottomControlActions(
    telemetry: WearTelemetry,
    onOpenSettings: () -> Unit,
    onToggleService: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .clickable { onToggleMute() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Mute",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (telemetry.running) Color(0xFFDC2626) else Color(0xFF10B981))
                    .clickable { onToggleService() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (telemetry.running) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = "Toggle Service",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .clickable { onOpenSettings() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun resolveRingColor(telemetry: WearTelemetry): Color =
    when {
        telemetry.overLimit -> Color(0xFFFF1744)
        telemetry.inSpecsZone -> Color(0xFF00E5FF)
        telemetry.nearestDistanceM != null -> Color(0xFFFFB300)
        telemetry.running -> Color(0xFF10B981)
        else -> Color(0xFF334155)
    }
