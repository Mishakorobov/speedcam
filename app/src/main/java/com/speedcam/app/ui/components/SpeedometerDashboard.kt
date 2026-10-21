package com.speedcam.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.theme.BrandPrimaryDark
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedSafe
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.service.DetectionUiState
import kotlin.math.roundToInt

/**
 * Modern Instrument Cluster Speedometer Dashboard.
 *
 * Combines an analog sweep gauge, prominent digital readout, active speed limit sign,
 * overspeed visual warning glow, and telemetry status pills.
 */
@Composable
fun SpeedometerCluster(
    state: DetectionUiState,
    actions: SpeedometerActions,
    modifier: Modifier = Modifier,
) {
    val speed = state.speedKmh
    val activeLimit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh
    val isOverLimit = state.overLimit

    val currentSpeedInt = speed.roundToInt()
    val maxGaugeSpeed = 180f

    val speedFraction by animateFloatAsState(
        targetValue = (speed.toFloat() / maxGaugeSpeed).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "SpeedGaugeFraction",
    )

    val gaugeColor by animateColorAsState(
        targetValue =
            when {
                isOverLimit -> SpeedDanger
                speed > 0 -> BrandPrimaryDark
                else -> Color(0xFF64748B)
            },
        animationSpec = tween(durationMillis = 200),
        label = "GaugeColor",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isOverLimit) Color(0xFF1E1116) else MaterialTheme.colorScheme.surface,
            ),
        border =
            if (isOverLimit) {
                androidx.compose.foundation.BorderStroke(2.dp, SpeedDanger)
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TelemetryStatusRow(state = state)
            Spacer(modifier = Modifier.height(16.dp))
            SpeedometerDial(
                speedFraction = speedFraction,
                gaugeColor = gaugeColor,
                currentSpeedInt = currentSpeedInt,
                isOverLimit = isOverLimit,
                activeLimit = activeLimit,
            )
            if (isOverLimit && activeLimit != null) {
                OverspeedAlertBanner(speed = speed, activeLimit = activeLimit)
            }
            Spacer(modifier = Modifier.height(14.dp))
            RadarControlBar(state = state, actions = actions)
        }
    }
}

@Composable
private fun SpeedometerDial(
    speedFraction: Float,
    gaugeColor: Color,
    currentSpeedInt: Int,
    isOverLimit: Boolean,
    activeLimit: Int?,
) {
    Box(
        modifier = Modifier.size(240.dp).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(220.dp)) {
            val strokeWidth = 14.dp.toPx()
            val arcPadding = strokeWidth / 2
            val arcSize = Size(size.width - arcPadding * 2, size.height - arcPadding * 2)
            val topLeft = Offset(arcPadding, arcPadding)
            val startAngle = 140f
            val sweepRange = 260f

            drawArc(
                color = Color(0xFF1E2533),
                startAngle = startAngle,
                sweepAngle = sweepRange,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            if (speedFraction > 0.01f) {
                drawArc(
                    color = gaugeColor,
                    startAngle = startAngle,
                    sweepAngle = sweepRange * speedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$currentSpeedInt",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 58.sp),
                color = if (isOverLimit) SpeedDanger else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "KM/H",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }

        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
        ) {
            SpeedLimitSign(limitKmh = activeLimit, size = 52.dp)
        }
    }
}

@Composable
private fun OverspeedAlertBanner(
    speed: Double,
    activeLimit: Int,
) {
    val excess = (speed - activeLimit).roundToInt().coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = SpeedDanger.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, SpeedDanger.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "⚠️ OVER LIMIT BY +$excess KM/H",
                color = SpeedDanger,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RadarControlBar(
    state: DetectionUiState,
    actions: SpeedometerActions,
) {
    RadarControlButton(running = state.running, onToggle = { actions.onEnableChange(!state.running) })
    Spacer(modifier = Modifier.height(12.dp))
    RadarQuickActionsRow(state = state, actions = actions)
}

@Composable
private fun RadarControlButton(
    running: Boolean,
    onToggle: () -> Unit,
) {
    Button(
        onClick = onToggle,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (running) SpeedDanger else SpeedSafe,
                contentColor = Color.White,
            ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(
            imageVector = if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (running) "STOP RADAR MONITORING" else "START RADAR DETECTION",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RadarQuickActionsRow(
    state: DetectionUiState,
    actions: SpeedometerActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = actions.onToggleMute,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                imageVector = if (state.muted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                contentDescription = "Mute",
                modifier = Modifier.size(18.dp),
                tint = if (state.muted) SpeedWarning else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (state.muted) "Muted" else "Sound",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        FilledTonalButton(
            onClick = actions.onReportHazard,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AddAlert,
                contentDescription = "Report",
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Report", style = MaterialTheme.typography.labelMedium)
        }

        FilledTonalButton(
            onClick = actions.onToggleHud,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = "HUD",
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("HUD", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Status indicator row at the top of the speedometer cluster.
 */
@Composable
private fun TelemetryStatusRow(state: DetectionUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GpsStatusPill(hasFix = state.hasFix)
        AudioProfileBadges(
            headsetConnected = state.headsetConnected,
            profileName = state.profileName,
        )
    }
}

@Composable
private fun GpsStatusPill(hasFix: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (hasFix) Color(0xFF064E3B) else Color(0xFF33200D))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (hasFix) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
            contentDescription = null,
            tint = if (hasFix) SpeedSafe else SpeedWarning,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = if (hasFix) "GPS LOCKED" else "SEARCHING GPS",
            color = if (hasFix) SpeedSafe else SpeedWarning,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AudioProfileBadges(
    headsetConnected: Boolean,
    profileName: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (headsetConnected) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BrandPrimaryDark.copy(alpha = 0.15f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Headset,
                        contentDescription = "Bluetooth",
                        tint = BrandPrimaryDark,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "BT AUDIO",
                        color = BrandPrimaryDark,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = profileName.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
