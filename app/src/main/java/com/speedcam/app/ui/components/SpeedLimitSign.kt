package com.speedcam.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.theme.RoadSignBorderRed
import com.speedcam.app.ui.theme.RoadSignFaceWhite
import com.speedcam.app.ui.theme.RoadSignTextBlack

/**
 * Universal Speed Limit Sign following Vienna Convention / GOST 3.24 standards.
 *
 * White circular face, bold red circular border, sharp black typography.
 * Designed for instant recognition at highway speeds.
 */
@Composable
fun SpeedLimitSign(
    limitKmh: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    borderWidth: Dp = if (size > 50.dp) 5.dp else 3.5.dp,
) {
    val limitText = limitKmh?.takeIf { it > 0 }?.toString() ?: "—"
    val fontSize = (size.value * 0.44f).sp

    Box(
        modifier =
            modifier
                .size(size)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(RoadSignFaceWhite)
                .border(borderWidth, RoadSignBorderRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = limitText,
            color = RoadSignTextBlack,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            lineHeight = fontSize,
        )
    }
}

/**
 * Compact Speed Limit Badge for list items and dense layouts.
 */
@Composable
fun CompactSpeedLimitBadge(
    limitKmh: Int?,
    modifier: Modifier = Modifier,
) {
    if (limitKmh != null && limitKmh > 0) {
        SpeedLimitSign(limitKmh = limitKmh, size = 36.dp, borderWidth = 2.5.dp, modifier = modifier)
    } else {
        Box(
            modifier =
                modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF262E3D))
                    .border(1.dp, Color(0xFF384459), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "—",
                color = Color(0xFF94A3B8),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
