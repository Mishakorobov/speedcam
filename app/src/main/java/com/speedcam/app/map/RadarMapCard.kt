package com.speedcam.app.map

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionUiState

/**
 * Compact radar vector map card embedded into the main Dashboard (StatusTab).
 */
@Composable
fun RadarMapCard(
    detectionState: DetectionUiState,
    cameras: List<Camera>,
    sections: List<SectionRecord>,
    onExpandMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vehicleLat = detectionState.currentLat ?: 55.7558
    val vehicleLon = detectionState.currentLon ?: 37.6173
    val miniMapState =
        remember {
            VectorMapState(
                initialCenter = LatLon(vehicleLat, vehicleLon),
                initialZoom = 15.0f,
                initialBearing = (detectionState.currentBearingDeg ?: 0.0).toFloat(),
                initialFollowMode = MapFollowMode.FOLLOW_HEADING,
                initialTheme = MapThemeMode.AUTOMOTIVE_DARK,
            )
        }

    miniMapState.updateVehicle(
        lat = detectionState.currentLat,
        lon = detectionState.currentLon,
        vehicleBearingDeg = detectionState.currentBearingDeg,
    )

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onExpandMap),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        elevation = CardDefaults.cardElevation(6.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            RadarMapCardHeader()
            Spacer(modifier = Modifier.height(10.dp))
            RadarMapCardPreview(miniMapState, detectionState, cameras, sections, onExpandMap)
        }
    }
}

@Composable
private fun RadarMapCardHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Map,
                contentDescription = null,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Векторный радар",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Развернуть",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF38BDF8),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.OpenInFull,
                contentDescription = "Expand",
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun RadarMapCardPreview(
    mapState: VectorMapState,
    detectionState: DetectionUiState,
    cameras: List<Camera>,
    sections: List<SectionRecord>,
    onExpandMap: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp)),
    ) {
        VectorMapCanvas(
            state = mapState,
            detectionState = detectionState,
            mapData = VectorMapData(cameras, sections, emptyList()),
            modifier = Modifier.matchParentSize(),
            onCameraClick = { onExpandMap() },
        )
    }
}
