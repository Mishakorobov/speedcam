package com.speedcam.app.hud.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.speedcam.core.data.HazardReportType
import com.speedcam.service.DetectionState
import kotlin.math.roundToInt

/**
 * Automotive Floating HUD Overlay content.
 *
 * Designed for glanceability while navigating. Floats unobtrusively over Google Maps or
 * Yandex Navigator with deep OLED contrast, active road-sign limit badge, instant tactile gestures,
 * and 1-tap crowdsourcing buttons for DPS ambushes, mobile tripods, and road accidents.
 */
@Composable
fun HudContent(
    isDragging: Boolean = false,
    autoDimOnClearRoad: Boolean = false,
    onMuteNearest: (() -> Unit)? = null,
    onBlockNearest: (() -> Unit)? = null,
    onReportHazard: (() -> Unit)? = null,
    onQuickReport: ((HazardReportType) -> Unit)? = null,
) {
    val state by DetectionState.state.collectAsStateWithLifecycle()

    val isOverLimit = state.overLimit
    val backgroundColor by animateColorAsState(
        targetValue = if (isOverLimit) HudOverlayDanger else HudOverlayDark,
        label = "HudBgColor",
    )
    val borderColor = if (isOverLimit) SpeedDanger else HudOverlayBorder

    val isRoadClear = state.nearest == null && state.activeSection == null
    val targetAlpha = if (autoDimOnClearRoad && isRoadClear) 0.45f else 1.0f
    val alpha by animateFloatAsState(targetValue = targetAlpha, label = "HudAlpha")

    Box(
        modifier =
            Modifier
                .graphicsLayer(alpha = alpha)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(backgroundColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HudCenterCluster(
                state = state,
                onMuteNearest = onMuteNearest,
                onBlockNearest = onBlockNearest,
                onReportHazard = onReportHazard,
            )
            state.activeSection?.let { section ->
                SectionReadout(section)
            }
            if (onQuickReport != null) {
                Spacer(modifier = Modifier.height(6.dp))
                HudQuickHazardBar(onQuickReport = onQuickReport)
            }
            if (state.muted || isDragging) {
                HudStatusFooter(muted = state.muted, dragging = isDragging)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HudCenterCluster(
    state: com.speedcam.service.DetectionUiState,
    onMuteNearest: (() -> Unit)? = null,
    onBlockNearest: (() -> Unit)? = null,
    onReportHazard: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onMuteNearest?.invoke() },
                onDoubleClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReportHazard?.invoke()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBlockNearest?.invoke()
                },
            ),
    ) {
        HazardHudSlot(state = state)
        SpeedReadout(speedKmh = state.speedKmh)
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh
        SpeedLimitSign(limitKmh = limit, size = 40.dp)
    }
}

@Composable
private fun HudQuickHazardBar(onQuickReport: (HazardReportType) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HudQuickButton(
            title = "ДПС",
            icon = Icons.Default.LocalPolice,
            color = Color(0xFF38BDF8),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onQuickReport(HazardReportType.DPS_PATROL)
            },
        )
        HudQuickButton(
            title = "Тренога",
            icon = Icons.Default.Videocam,
            color = Color(0xFFF59E0B),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onQuickReport(HazardReportType.MOBILE_TRIPOD)
            },
        )
        HudQuickButton(
            title = "ДТП",
            icon = Icons.Default.CarCrash,
            color = Color(0xFFEF4444),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onQuickReport(HazardReportType.ROAD_ACCIDENT)
            },
        )
    }
}

@Composable
private fun HudQuickButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FFFFFF))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
                contentDescription = "Звук выключен",
                tint = SpeedWarning,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "БЕЗ ЗВУКА",
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
                text = "ПЕРЕМЕЩЕНИЕ",
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
                    Text(text = "КСС", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(
                        text = "${activeSection.remainingM.roundToInt()}м",
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
                        text = "${hazard.distanceM.roundToInt()}м",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            else -> {
                Text(
                    text = "ОК",
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
            text = "КМ/Ч",
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
            "СРЕДНЯЯ ${section.averageKmh.roundToInt()} км/ч · ОГРАНИЧЕНИЕ ${section.limitKmh}"
        } else {
            "~${section.averageKmh.roundToInt()} км/ч · осталось ${section.remainingM.roundToInt()} м"
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
