package com.speedcam.app.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.components.SpeedLimitSign
import com.speedcam.app.ui.components.getCameraIcon
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedSafe
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.app.util.toRussianBadge
import com.speedcam.core.detect.CameraKind
import com.speedcam.service.DetectionUiState
import com.speedcam.service.HazardView
import com.speedcam.service.SectionUiState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * High-contrast automotive Picture-in-Picture (PiP) speedometer content.
 *
 * Designed for 16:9 glanceability when floating over third-party turn-by-turn navigation apps.
 * Features a large GPS speed readout, speed-limit road sign badge, distance countdown,
 * SPECS average-speed corridor status, and road-clear indicators.
 */
@Composable
fun PipSpeedometerContent(
    state: DetectionUiState,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (state.overLimit) SpeedDanger else Color(0x33FFFFFF)
    val hasHazard = state.nearest != null || state.activeSection != null

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color(0xFF0A0F1D))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                PipSpeedCluster(
                    speedKmh = state.speedKmh,
                    overLimit = state.overLimit,
                    hasHazard = hasHazard,
                    muted = state.muted,
                    running = state.running,
                )
            }

            Spacer(
                modifier =
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.75f)
                        .background(Color(0x2AFFFFFF)),
            )

            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                PipHazardCluster(state = state)
            }
        }
    }
}

@Composable
private fun PipSpeedCluster(
    speedKmh: Double,
    overLimit: Boolean,
    hasHazard: Boolean,
    muted: Boolean,
    running: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (running) "${speedKmh.roundToInt()}" else "—",
            color = resolvePipSpeedColor(overLimit, hasHazard, running),
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 36.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                text = "KM/H",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            if (muted) {
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Muted",
                    tint = SpeedWarning,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun PipHazardCluster(state: DetectionUiState) {
    val activeSection = state.activeSection
    val nearest = state.nearest
    when {
        activeSection != null -> PipSpecsSectionView(activeSection)
        nearest != null -> PipCameraHazardView(nearest)
        else -> PipClearRoadView(running = state.running, hasFix = state.hasFix)
    }
}

@Composable
private fun PipCameraHazardView(hazard: HazardView) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SpeedLimitSign(limitKmh = hazard.limitKmh, size = 36.dp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = getCameraIcon(hazard.kind),
                contentDescription = null,
                tint = if (hazard.behind) SpeedWarning else Color.White,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = formatPipDistance(hazard.distanceM),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = formatCameraKindLabel(hazard.kind),
            color = Color(0xFF94A3B8),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PipSpecsSectionView(section: SectionUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SpeedLimitSign(limitKmh = section.limitKmh, size = 32.dp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "СРЕДНЯЯ ${section.averageKmh.roundToInt()} км/ч",
            color = if (section.overLimit) SpeedDanger else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "осталось ${formatPipDistance(section.remainingM)}",
            color = Color(0xFF94A3B8),
            fontSize = 8.sp,
        )
    }
}

@Composable
private fun PipClearRoadView(
    running: Boolean,
    hasFix: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (running) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SpeedSafe,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "ДОРОГА ЧИСТА",
                color = SpeedSafe,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (hasFix) "GPS АКТИВЕН" else "ПОИСК GPS",
                color = Color(0xFF94A3B8),
                fontSize = 7.sp,
            )
        } else {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "РАДАР ВЫКЛ",
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal fun resolvePipSpeedColor(
    overLimit: Boolean,
    hasHazard: Boolean,
    running: Boolean,
): Color =
    when {
        !running -> Color(0xFF94A3B8)
        overLimit -> SpeedDanger
        hasHazard -> SpeedWarning
        else -> Color.White
    }

internal fun formatCameraKindLabel(kind: CameraKind): String = kind.toRussianBadge()

internal fun formatPipDistance(
    distanceM: Double,
    locale: Locale = Locale.getDefault(),
): String {
    val rounded = distanceM.roundToInt()
    val isEnglish = locale == Locale.US || locale == Locale.ENGLISH || locale == Locale.UK
    return if (rounded >= 1000) {
        val unit = if (isEnglish) "km" else "км"
        String.format(locale, "%.1f %s", rounded / 1000.0, unit)
    } else {
        val unit = if (isEnglish) "m" else "м"
        "$rounded $unit"
    }
}
