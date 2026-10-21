package com.speedcam.app.hud.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcam.app.ui.components.SpeedLimitSign
import com.speedcam.app.ui.components.getCameraIcon
import com.speedcam.app.ui.theme.HudOverlayBorder
import com.speedcam.app.ui.theme.HudOverlayDanger
import com.speedcam.app.ui.theme.HudOverlayDark
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.service.DetectionState
import kotlin.math.roundToInt

/**
 * Automotive Floating HUD Overlay content.
 *
 * Designed for glanceability while navigating. Floats unobtrusively over Google Maps or
 * Yandex Navigator with deep OLED contrast, active road-sign limit badge, and instant
 * tactile gestures (tap to mute, double-tap to report, long press to block).
 */
@Composable
fun HudContent(
    onDragTo: (x: Int, y: Int) -> Unit,
    onCommitDrag: () -> Unit,
    onBlock: () -> Unit,
    onMute: () -> Unit,
    onReport: () -> Unit = {},
) {
    val state by DetectionState.state.collectAsStateWithLifecycle()

    var origin by remember { mutableStateOf(Pair(0, 0)) }
    var dragging by remember { mutableStateOf(false) }

    val isOverLimit = state.overLimit
    val backgroundColor by animateColorAsState(
        targetValue = if (isOverLimit) HudOverlayDanger else HudOverlayDark,
        label = "HudBgColor",
    )
    val borderColor = if (isOverLimit) SpeedDanger else HudOverlayBorder

    Box(
        modifier =
            Modifier
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(backgroundColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .pointerInput(state.nearest?.cameraId) {
                    detectTapGestures(
                        onTap = { onMute() },
                        onDoubleTap = { onReport() },
                        onLongPress = { onBlock() },
                    )
                }.pointerInput(state.nearest?.cameraId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            origin = offset.x.roundToInt() to offset.y.roundToInt()
                        },
                        onDragEnd = {
                            dragging = false
                            onCommitDrag()
                        },
                        onDragCancel = { dragging = false },
                        onDrag = { change, amount ->
                            change.consume()
                            origin = (origin.first + amount.x.roundToInt()) to
                                (origin.second + amount.y.roundToInt())
                            onDragTo(origin.first, origin.second)
                        },
                    )
                },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HudCenterCluster(state = state)
            state.activeSection?.let { section ->
                SectionReadout(section)
            }
            if (state.muted || dragging) {
                HudStatusFooter(muted = state.muted, dragging = dragging)
            }
        }
    }
}

@Composable
private fun HudCenterCluster(state: com.speedcam.service.DetectionUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HazardHudSlot(state = state)
        SpeedReadout(speedKmh = state.speedKmh)
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh
        SpeedLimitSign(limitKmh = limit, size = 42.dp)
    }
}

@Composable
private fun HudStatusFooter(
    muted: Boolean,
    dragging: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        if (muted) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Muted",
                tint = SpeedWarning,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "MUTED",
                color = SpeedWarning,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (muted && dragging) {
            Text(
                text = " · ",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
            )
        }
        if (dragging) {
            Text(
                text = "DRAG TO MOVE",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HazardHudSlot(state: com.speedcam.service.DetectionUiState) {
    val hazard = state.nearest
    val activeSection = state.activeSection

    Box(
        modifier =
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            activeSection != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "AVG", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(
                        text = "${activeSection.remainingM.roundToInt()}m",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                    )
                }
            }
            hazard != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = getCameraIcon(hazard.kind),
                        contentDescription = null,
                        tint = if (hazard.behind) SpeedWarning else Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "${hazard.distanceM.roundToInt()}m",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            else -> {
                Text(
                    text = "OK",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SpeedReadout(speedKmh: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = speedKmh.roundToInt().toString(),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 34.sp,
        )
        Text(
            text = "KM/H",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun SectionReadout(section: com.speedcam.service.SectionUiState) {
    val text =
        if (section.completed) {
            "AVG ${section.averageKmh.roundToInt()} km/h · LIMIT ${section.limitKmh}"
        } else {
            "~${section.averageKmh.roundToInt()} km/h · ${section.remainingM.roundToInt()} m left"
        }
    Text(
        text = text,
        color = if (section.overLimit) SpeedDanger else Color.White.copy(alpha = 0.9f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Kept so the overlay's scale setting has a home once it exists. */
@Composable
internal fun HudScaleProvider(): Float = LocalContext.current.let { 1f }
