package com.speedcam.app.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.components.CompactSpeedLimitBadge
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionUiState
import kotlinx.coroutines.launch

/**
 * Lightweight interactive vector map screen for speed camera detection.
 */
@Composable
fun VectorMapScreen(
    mapState: VectorMapState,
    detectionState: DetectionUiState,
    mapData: VectorMapData,
    actions: VectorMapActions,
    modifier: Modifier = Modifier,
) {
    var roads by remember { mutableStateOf<List<VectorRoad>>(emptyList()) }
    val context = LocalContext.current
    val roadRepo = remember { VectorRoadRepository(context) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(
        detectionState.currentLat,
        detectionState.currentLon,
        detectionState.currentBearingDeg,
        mapState.followMode,
    ) {
        mapState.updateVehicle(
            lat = detectionState.currentLat,
            lon = detectionState.currentLon,
            vehicleBearingDeg = detectionState.currentBearingDeg,
        )
    }

    LaunchedEffect(mapState.centerLat, mapState.centerLon, mapState.zoom) {
        val box = mapState.computeVisibleBoundingBox(1080f, 1920f)
        scope.launch {
            roads = roadRepo.getRoadsForBox(box, mapState.zoom)
        }
    }

    val completeMapData =
        remember(mapData.cameras, mapData.sections, roads) {
            VectorMapData(mapData.cameras, mapData.sections, roads)
        }

    VectorMapScreenLayout(
        mapState = mapState,
        detectionState = detectionState,
        mapData = completeMapData,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun VectorMapScreenLayout(
    mapState: VectorMapState,
    detectionState: DetectionUiState,
    mapData: VectorMapData,
    actions: VectorMapActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        VectorMapCanvas(
            state = mapState,
            detectionState = detectionState,
            mapData = mapData,
            modifier = Modifier.fillMaxSize(),
            onCameraClick = { cam -> mapState.inspectCamera(cam) },
        )

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 10.dp),
        ) {
            MapTopHudHeader(detectionState, mapData.cameras.size)
            MapFilterChipsRow(mapState.filterKind) { mapState.filterKind = it }
        }

        MapControlsColumn(
            state = mapState,
            vehicleLat = detectionState.currentLat,
            vehicleLon = detectionState.currentLon,
            vehicleBearing = detectionState.currentBearingDeg,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
        )

        AnimatedVisibility(
            visible = mapState.inspectedCamera != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            mapState.inspectedCamera?.let { cam ->
                val vLat = detectionState.currentLat
                val vLon = detectionState.currentLon
                val point = if (vLat != null && vLon != null) LatLon(vLat, vLon) else null
                CameraInspectorCard(cam, point, actions, onClose = { mapState.inspectCamera(null) })
            }
        }
    }
}

// --------------------------------------------------------------------- Map Components

@Composable
private fun MapTopHudHeader(
    detectionState: DetectionUiState,
    camerasInViewCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedPill(detectionState)
        StatusPill(detectionState, camerasInViewCount)
    }
}

@Composable
private fun SpeedPill(detectionState: DetectionUiState) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (detectionState.overLimit) SpeedDanger else Color(0xCC0F172A),
        tonalElevation = 6.dp,
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${detectionState.speedKmh.toInt()}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            Text(
                text = " км/ч",
                fontSize = 12.sp,
                color = Color(0xCCFFFFFF),
                modifier = Modifier.padding(top = 4.dp),
            )

            val limit = detectionState.activeSection?.limitKmh ?: detectionState.nearest?.limitKmh
            if (limit != null && limit > 0) {
                Spacer(modifier = Modifier.width(10.dp))
                CompactSpeedLimitBadge(limitKmh = limit)
            }
        }
    }
}

@Composable
private fun StatusPill(
    detectionState: DetectionUiState,
    count: Int,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC0F172A),
        tonalElevation = 6.dp,
    ) {
        val nearest = detectionState.nearest
        if (nearest != null) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "⚡ ${nearest.kind.name} (${nearest.distanceM.toInt()}м)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        } else {
            Text(
                text = "$count камер в базе",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MapFilterChipsRow(
    activeKind: CameraKind?,
    onSelectKind: (CameraKind?) -> Unit,
) {
    val items =
        listOf(
            null to "Все",
            CameraKind.FIXED to "Скорость",
            CameraKind.RED_LIGHT to "Светофоры",
            CameraKind.AVERAGE_ENTRY to "КСС",
            CameraKind.MOBILE to "Засады",
            CameraKind.DUMMY to "Муляжи",
        )

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { (kind, label) ->
            val isSelected = activeKind == kind
            FilterChip(
                selected = isSelected,
                onClick = { onSelectKind(if (isSelected) null else kind) },
                label = {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xCC0F172A),
                        labelColor = Color(0xFFE2E8F0),
                        selectedContainerColor = Color(0xFF0284C7),
                        selectedLabelColor = Color.White,
                    ),
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
}

@Composable
private fun MapControlsColumn(
    state: VectorMapState,
    vehicleLat: Double?,
    vehicleLon: Double?,
    vehicleBearing: Double?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FloatingActionButton(
            onClick = { state.toggleOrientation(vehicleBearing) },
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            containerColor = Color(0xDD0F172A),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = "Compass Orientation",
                modifier = Modifier.rotate(-state.bearingDeg),
                tint = if (state.followMode == MapFollowMode.FOLLOW_HEADING) Color(0xFF00E5FF) else Color.White,
            )
        }

        val isFollowing = state.followMode != MapFollowMode.FREE_ROAM
        FloatingActionButton(
            onClick = {
                if (vehicleLat != null && vehicleLon != null) {
                    state.centerOn(LatLon(vehicleLat, vehicleLon), 16.0f, MapFollowMode.FOLLOW_HEADING)
                }
            },
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            containerColor = if (isFollowing) Color(0xFF0284C7) else Color(0xDD0F172A),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
        ) {
            Icon(imageVector = Icons.Default.MyLocation, contentDescription = "Center on Vehicle", tint = Color.White)
        }

        MapSecondaryControls(state)
    }
}

@Composable
private fun MapSecondaryControls(state: VectorMapState) {
    FloatingActionButton(
        onClick = { state.zoomIn() },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        containerColor = Color(0xDD0F172A),
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(4.dp),
    ) {
        Icon(imageVector = Icons.Default.Add, contentDescription = "Zoom In")
    }

    FloatingActionButton(
        onClick = { state.zoomOut() },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        containerColor = Color(0xDD0F172A),
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(4.dp),
    ) {
        Icon(imageVector = Icons.Default.Remove, contentDescription = "Zoom Out")
    }

    FloatingActionButton(
        onClick = {
            state.themeMode =
                if (state.themeMode == MapThemeMode.AUTOMOTIVE_DARK) {
                    MapThemeMode.DAY_LIGHT
                } else {
                    MapThemeMode.AUTOMOTIVE_DARK
                }
        },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        containerColor = Color(0xDD0F172A),
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(4.dp),
    ) {
        val isDark = state.themeMode == MapThemeMode.AUTOMOTIVE_DARK
        Icon(
            imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
            contentDescription = "Toggle Map Theme",
        )
    }

    FloatingActionButton(
        onClick = { state.showDistanceRings = !state.showDistanceRings },
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        containerColor = Color(0xDD0F172A),
        contentColor = if (state.showDistanceRings) Color(0xFF00E5FF) else Color(0xFF64748B),
        elevation = FloatingActionButtonDefaults.elevation(4.dp),
    ) {
        Icon(
            imageVector = if (state.showDistanceRings) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = "Toggle Distance Rings",
        )
    }
}

@Composable
private fun CameraInspectorCard(
    camera: Camera,
    vehiclePoint: LatLon?,
    actions: VectorMapActions,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF00F172A)),
        elevation = CardDefaults.cardElevation(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            InspectorHeader(camera, onClose)
            Spacer(modifier = Modifier.height(8.dp))
            InspectorBody(camera, vehiclePoint)
            Spacer(modifier = Modifier.height(14.dp))
            InspectorActionsRow(camera, actions)
        }
    }
}

@Composable
private fun InspectorHeader(
    camera: Camera,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${camera.kind.name} #${camera.id}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

@Composable
private fun InspectorBody(
    camera: Camera,
    vehiclePoint: LatLon?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        camera.limitKmh?.let { limit ->
            if (limit > 0) {
                CompactSpeedLimitBadge(limitKmh = limit)
                Spacer(modifier = Modifier.width(12.dp))
            }
        }

        Column {
            if (vehiclePoint != null) {
                val distM = Geo.haversineMeters(vehiclePoint, camera.position).toInt()
                val bearingToCam = Geo.bearingDeg(vehiclePoint, camera.position).toInt()
                Text(
                    text = "Дистанция: $distM м • Азимут: $bearingToCam°",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8),
                )
            }
            camera.facingDeg?.let { facing ->
                val dirLabel = if (camera.dirCount > 1) "в оба направления" else "в лицо/спину"
                Text(
                    text = "Направление фиксации: ${facing.toInt()}° ($dirLabel)",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                )
            }
            Text(
                text =
                    "%.5f, %.5f • Источник: %s".format(
                        camera.position.lat,
                        camera.position.lon,
                        camera.source.name,
                    ),
                fontSize = 11.sp,
                color = Color(0xFF64748B),
            )
        }
    }
}

@Composable
private fun InspectorActionsRow(
    camera: Camera,
    actions: VectorMapActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { actions.onBlockCamera(camera) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
        ) {
            Icon(imageVector = Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Блокировать", fontSize = 12.sp)
        }

        Button(
            onClick = { actions.onReportCamera(camera) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
        ) {
            Icon(imageVector = Icons.Default.Report, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Ошибка / Инфо", fontSize = 12.sp)
        }
    }
}
