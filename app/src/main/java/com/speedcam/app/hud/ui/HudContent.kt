package com.speedcam.app.hud.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcam.service.DetectionState
import kotlin.math.roundToInt

/**
 * The overlay's content.
 *
 * Laid out as the original's bubble: current speed large in the centre, the hazard's limit on
 * one side, its type on the other. Tapping mutes the camera for this session, long-pressing
 * blocks it permanently — the same two gestures the original's alert slots use, where a tap
 * is `nativeMuteHazardAtSlot` and a long-press is `nativeBlockHazardAtSlot`.
 */
@Composable
fun HudContent(
    onDragTo: (x: Int, y: Int) -> Unit,
    onCommitDrag: () -> Unit,
    onBlock: () -> Unit,
    onMute: () -> Unit,
) {
    val state by DetectionState.state.collectAsStateWithLifecycle()

    // Local drag origin, so the window follows the finger from wherever it was rather than
    // jumping to an absolute coordinate.
    var origin by remember { androidx.compose.runtime.mutableStateOf(Pair(0, 0)) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }

    val background = if (state.overLimit) Color(0xFFD32F2F) else Color(0xE6212121)

    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(state.nearest?.cameraId) {
                    detectTapGestures(
                        onTap = { onMute() },
                        onLongPress = { onBlock() },
                    )
                }.pointerInput(state.nearest?.cameraId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            origin = (offset.x.roundToInt()) to (offset.y.roundToInt())
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HazardBadge(state)
                SpeedReadout(state.speedKmh)
                LimitBadge(state.activeSection?.limitKmh ?: state.nearest?.limitKmh)
            }

            SectionReadout(state.activeSection)

            if (dragging) {
                Text(
                    text = "drag",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionReadout(section: com.speedcam.service.SectionUiState?) {
    if (section == null) return
    Text(
        text = "~${section.averageKmh.roundToInt()} km/h · ${section.remainingM.roundToInt()} m",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun HazardBadge(state: com.speedcam.service.DetectionUiState) {
    val hazard = state.nearest
    val text =
        when {
            state.activeSection != null -> "AVG"
            hazard == null -> "—"
            hazard.behind -> "ONC"
            else -> "CAM"
        }
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeedReadout(speedKmh: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = speedKmh.roundToInt().toString(),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(text = "km/h", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
    }
}

@Composable
private fun LimitBadge(limitKmh: Int?) {
    Box(
        modifier =
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = limitKmh?.takeIf { it > 0 }?.toString() ?: "—",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Kept so the overlay's scale setting has a home once it exists. */
@Composable
internal fun HudScaleProvider(): Float = LocalContext.current.let { 1f }
