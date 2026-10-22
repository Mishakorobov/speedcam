package com.speedcam.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.theme.BrandPrimaryDark
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedSafe
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.app.util.toRussianTitle
import com.speedcam.service.HazardView
import com.speedcam.service.SectionUiState
import kotlin.math.roundToInt

/**
 * Primary Upcoming Hazard Alert Card (Dual-Slot Primary).
 */
@Composable
fun PrimaryHazardAlertCard(
    hazard: HazardView,
    overLimit: Boolean,
    onMute: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distance = hazard.distanceM.roundToInt()
    val maxWarningDistance = 1000f
    val progress = (1f - (hazard.distanceM.toFloat() / maxWarningDistance)).coerceIn(0.05f, 1f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (overLimit) Color(0xFF261217) else MaterialTheme.colorScheme.surfaceVariant,
            ),
        border =
            if (overLimit) {
                androidx.compose.foundation.BorderStroke(1.5.dp, SpeedDanger)
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            PrimaryHazardCardHeader(hazard, overLimit, onMute, onBlock)
            Spacer(modifier = Modifier.height(14.dp))
            PrimaryHazardDistanceRow(distance, overLimit, hazard.limitKmh)
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                color = if (overLimit) SpeedDanger else BrandPrimaryDark,
                trackColor = Color(0xFF1E2533),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PrimaryHazardCardHeader(
    hazard: HazardView,
    overLimit: Boolean,
    onMute: () -> Unit,
    onBlock: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HazardKindTitle(hazard = hazard, overLimit = overLimit)
        HazardQuickActionButtons(onMute = onMute, onBlock = onBlock)
    }
}

@Composable
private fun HazardKindTitle(
    hazard: HazardView,
    overLimit: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (overLimit) SpeedDanger else BrandPrimaryDark),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = getCameraIcon(hazard.kind),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Column {
            Text(
                text = hazard.kind.toRussianTitle().uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (overLimit) SpeedDanger else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NearMe,
                    contentDescription = null,
                    tint = if (hazard.behind) SpeedWarning else SpeedSafe,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (hazard.behind) "В СПИНУ / ВСТРЕЧНАЯ" else "ВПЕРЕДИ",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hazard.behind) SpeedWarning else SpeedSafe,
                )
            }
        }
    }
}

@Composable
private fun HazardQuickActionButtons(
    onMute: () -> Unit,
    onBlock: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onMute, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = "Заглушить оповещение",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onBlock, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = "Заблокировать камеру",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PrimaryHazardDistanceRow(
    distance: Int,
    overLimit: Boolean,
    limitKmh: Int?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "$distance м",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = if (overLimit) SpeedDanger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "РАССТОЯНИЕ ДО КАМЕРЫ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
            )
        }
        SpeedLimitSign(limitKmh = limitKmh, size = 56.dp)
    }
}

/**
 * Secondary Upcoming Hazard Slot.
 */
@Composable
fun SecondaryHazardCard(
    hazard: HazardView,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = getCameraIcon(hazard.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        text = "Следующая: ${hazard.kind.toRussianTitle().lowercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "через ${hazard.distanceM.roundToInt()} м",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CompactSpeedLimitBadge(limitKmh = hazard.limitKmh)
        }
    }
}

/**
 * Average Speed Enforcement Zone (SPECS Corridor) Card.
 */
@Composable
fun SpecsSectionAlertCard(
    section: SectionUiState,
    modifier: Modifier = Modifier,
) {
    val isOverLimit = section.overLimit
    val avgSpeed = section.averageKmh.roundToInt()
    val limit = section.limitKmh
    val remaining = section.remainingM.roundToInt()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (isOverLimit) Color(0xFF2B1417) else Color(0xFF122033),
            ),
        border =
            androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (isOverLimit) SpeedDanger else BrandPrimaryDark,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            SpecsSectionHeader(isOverLimit = isOverLimit, limit = limit)
            Spacer(modifier = Modifier.height(12.dp))
            SpecsSectionMetrics(avgSpeed = avgSpeed, isOverLimit = isOverLimit, remaining = remaining)
        }
    }
}

@Composable
private fun SpecsSectionHeader(
    isOverLimit: Boolean,
    limit: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LinearScale,
                contentDescription = null,
                tint = if (isOverLimit) SpeedDanger else BrandPrimaryDark,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = "КОНТРОЛЬ СРЕДНЕЙ СКОРОСТИ",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isOverLimit) SpeedDanger else BrandPrimaryDark,
            )
        }
        SpeedLimitSign(limitKmh = limit, size = 44.dp)
    }
}

@Composable
private fun SpecsSectionMetrics(
    avgSpeed: Int,
    isOverLimit: Boolean,
    remaining: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(
                text = "$avgSpeed км/ч",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = if (isOverLimit) SpeedDanger else SpeedSafe,
            )
            Text(
                text = if (isOverLimit) "ПРЕВЫШЕНИЕ СКОРОСТИ!" else "ВАША СРЕДНЯЯ СКОРОСТЬ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isOverLimit) SpeedDanger else SpeedSafe,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$remaining м",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "ОСТАЛОСЬ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Empty / All Clear State Card when no camera is within range.
 */
@Composable
fun EmptyHazardCard(
    radarActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (radarActive) SpeedSafe.copy(alpha = 0.15f) else Color(0xFF283144)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (radarActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (radarActive) SpeedSafe else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (radarActive) "Впереди свободно" else "Радар-детектор на паузе",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text =
                        if (radarActive) {
                            "Камер и дорожных опасностей по курсу не обнаружено."
                        } else {
                            "Нажмите кнопку выше для возобновления мониторинга."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun getCameraIcon(kind: com.speedcam.core.detect.CameraKind): ImageVector =
    when (kind) {
        com.speedcam.core.detect.CameraKind.RED_LIGHT -> Icons.Default.Traffic
        com.speedcam.core.detect.CameraKind.AVERAGE_ENTRY,
        com.speedcam.core.detect.CameraKind.AVERAGE_EXIT,
        -> Icons.Default.LinearScale
        com.speedcam.core.detect.CameraKind.MOBILE -> Icons.Default.Videocam
        else -> Icons.Default.CameraAlt
    }
