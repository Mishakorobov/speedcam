package com.speedcam.app.map

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.ui.components.CompactSpeedLimitBadge
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.util.toRussianTitle
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    var roadStatus by remember { mutableStateOf(RoadDataStatus.LOADED) }
    var showCameraSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val roadRepo = remember { VectorRoadRepository(context) }
    val tileManager =
        remember {
            com.speedcam.app.SpeedCamApp
                .offlineTileManager(context)
        }

    // Synchronize vehicle heading and coordinates with map viewport
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

    // Dynamic auto-centering on live GPS or camera cluster centroid
    LaunchedEffect(detectionState.currentLat, detectionState.currentLon, mapData.cameras.size) {
        val curLat = detectionState.currentLat
        val curLon = detectionState.currentLon
        if (!mapState.hasAutoCentered) {
            if (curLat != null && curLon != null) {
                mapState.centerOn(
                    target = LatLon(curLat, curLon),
                    targetZoom = 15.5f,
                    mode = MapFollowMode.FOLLOW_HEADING,
                )
                mapState.hasAutoCentered = true
            } else if (mapState.isAtDefaultCenter()) {
                val best =
                    MapCenteringHelper.findInitialCenter(
                        context = context,
                        uiLat = curLat,
                        uiLon = curLon,
                        cameras = mapData.cameras,
                    )
                if (best.second) {
                    mapState.centerOn(
                        target = best.first,
                        targetZoom = 13.5f,
                        mode = MapFollowMode.FREE_ROAM,
                    )
                    mapState.hasAutoCentered = true
                }
            }
        }
    }

    // High-performance offline map loading (prioritizes local MBTiles & SQLite cache)
    LaunchedEffect(mapState.centerLat, mapState.centerLon, mapState.zoom, mapState.showTiles) {
        if (mapState.showTiles) {
            // Cartography is actively rendered by offline tiles (MBTiles / SQLite cache)
            roadStatus = RoadDataStatus.LOADED
            val box = mapState.computeVisibleBoundingBox(1080f, 1920f)
            val cachedRoads = roadRepo.getRoadsForBox(box, mapState.zoom)
            if (cachedRoads.isNotEmpty()) {
                roads = cachedRoads
            }
        } else {
            delay(250)
            val box = mapState.computeVisibleBoundingBox(1080f, 1920f)
            val fetched = roadRepo.getRoadsForBox(box, mapState.zoom)
            if (fetched.isNotEmpty()) {
                roads = fetched
                roadStatus = RoadDataStatus.LOADED
            } else if (mapState.zoom >= VectorRoadRepository.MIN_ROAD_ZOOM) {
                roadStatus = if (roadRepo.isNetworkAvailable()) RoadDataStatus.LOADING else RoadDataStatus.OFFLINE
            }
        }
    }

    val completeMapData =
        remember(mapData.cameras, mapData.sections, mapData.blocked, roads) {
            val combinedRoads =
                if (roads.isEmpty() && mapData.sections.isNotEmpty()) {
                    SyntheticRoadCorridors.createSectionCorridors(mapData.sections, mapData.cameras)
                } else {
                    roads
                }
            VectorMapData(mapData.cameras, mapData.sections, combinedRoads, mapData.blocked)
        }

    VectorMapScreenLayout(
        mapState = mapState,
        detectionState = detectionState,
        mapData = completeMapData,
        actions = actions,
        tileManager = tileManager,
        roadStatus = roadStatus,
        showCameraSheet = showCameraSheet,
        onToggleCameraSheet = { showCameraSheet = it },
        modifier = modifier,
    )
}

@Composable
private fun VectorMapScreenLayout(
    mapState: VectorMapState,
    detectionState: DetectionUiState,
    mapData: VectorMapData,
    actions: VectorMapActions,
    tileManager: OfflineTileManager,
    roadStatus: RoadDataStatus,
    showCameraSheet: Boolean,
    onToggleCameraSheet: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        VectorMapCanvas(
            state = mapState,
            detectionState = detectionState,
            mapData = mapData,
            tileManager = tileManager,
            modifier = Modifier.fillMaxSize(),
            onCameraClick = { cam -> mapState.inspectCamera(cam) },
        )

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 10.dp),
        ) {
            MapTopHudHeader(detectionState, mapData.cameras.size, roadStatus)
            MapFilterChipsRow(mapState.filterKind) { mapState.filterKind = it }
        }

        MapControlsColumn(
            state = mapState,
            vehicleLat = detectionState.currentLat,
            vehicleLon = detectionState.currentLon,
            vehicleBearing = detectionState.currentBearingDeg,
            cameras = mapData.cameras,
            context = context,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
        )

        // 1-tap crowdsource reporting buttons and camera list trigger
        if (mapState.inspectedCamera == null) {
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapQuickHazardBar(onReportHazardType = actions.onReportHazardType)

                FloatingActionButton(
                    onClick = { onToggleCameraSheet(true) },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color(0xDD0F172A),
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Камеры",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Камеры (${mapData.cameras.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

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
                val isBlocked = mapData.blocked.any { Geo.haversineMeters(it, cam.position) <= 40.0 }
                CameraInspectorCard(
                    camera = cam,
                    isBlocked = isBlocked,
                    vehiclePoint = point,
                    actions = actions,
                    onClose = { mapState.inspectCamera(null) },
                )
            }
        }

        if (showCameraSheet) {
            val uLat = detectionState.currentLat
            val uLon = detectionState.currentLon
            val userLoc =
                if (uLat != null && uLon != null) {
                    LatLon(uLat, uLon)
                } else {
                    mapState.center
                }
            CameraListBottomSheet(
                cameras = mapData.cameras,
                blocked = mapData.blocked,
                userLocation = userLoc,
                onDismissRequest = { onToggleCameraSheet(false) },
                onSelectCamera = { cam ->
                    onToggleCameraSheet(false)
                    mapState.centerOn(
                        target = cam.position,
                        targetZoom = 16.5f,
                        mode = MapFollowMode.FREE_ROAM,
                    )
                    mapState.inspectCamera(cam)
                },
                onUnblockCamera = { row ->
                    val cam =
                        mapData.cameras.find { it.id == row.id } ?: Camera(
                            id = row.id,
                            position = LatLon(row.latitude, row.longitude),
                            limitKmh = row.limitKmh,
                            kind = row.kind,
                            source = row.source,
                            operator = row.operator,
                        )
                    actions.onUnblockCamera(cam)
                },
            )
        }
    }
}

// --------------------------------------------------------------------- Map Components

@Composable
private fun MapTopHudHeader(
    detectionState: DetectionUiState,
    camerasInViewCount: Int,
    roadStatus: RoadDataStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeedPill(detectionState)
        StatusPill(detectionState, camerasInViewCount, roadStatus)
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
    roadStatus: RoadDataStatus,
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
                    text = "⚡ ${nearest.kind.toRussianTitle()} (${nearest.distanceM.toInt()}м)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusText =
                    when (roadStatus) {
                        RoadDataStatus.OFFLINE -> "Автономный режим • $count камер"
                        RoadDataStatus.LOADING -> "Загрузка дорог • $count камер"
                        RoadDataStatus.LOADED -> "$count камер в базе"
                    }
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                )
            }
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
            CameraKind.BUS_LANE to "Полоса ОТ",
            CameraKind.ROAD_MARKING to "Разметка",
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
    cameras: List<Camera>,
    context: Context,
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
                contentDescription = "Ориентация по компасу",
                modifier = Modifier.rotate(-state.bearingDeg),
                tint = if (state.followMode == MapFollowMode.FOLLOW_HEADING) Color(0xFF00E5FF) else Color.White,
            )
        }

        val isFollowing = state.followMode != MapFollowMode.FREE_ROAM
        FloatingActionButton(
            onClick = {
                if (vehicleLat != null && vehicleLon != null) {
                    state.centerOn(LatLon(vehicleLat, vehicleLon), 16.0f, MapFollowMode.FOLLOW_HEADING)
                } else {
                    MapCenteringHelper.getLastKnownLocation(context)?.let {
                        state.centerOn(it, 16.0f, MapFollowMode.FOLLOW_NORTH)
                    } ?: MapCenteringHelper.findCameraClusterCentroid(cameras)?.let {
                        state.centerOn(it, 14.0f, MapFollowMode.FREE_ROAM)
                    }
                }
            },
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            containerColor = if (isFollowing) Color(0xFF0284C7) else Color(0xDD0F172A),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(4.dp),
        ) {
            Icon(imageVector = Icons.Default.MyLocation, contentDescription = "Центрировать на автомобиле", tint = Color.White)
        }

        MapSecondaryControls(state)
    }
}

@Composable
private fun MapSecondaryControls(state: VectorMapState) {
    RepeatingZoomButton(
        icon = Icons.Default.Add,
        contentDescription = "Приблизить",
        onStep = { state.zoomIn() },
        onContinuous = { delta -> state.zoomBy(delta) },
    )

    RepeatingZoomButton(
        icon = Icons.Default.Remove,
        contentDescription = "Отдалить",
        onStep = { state.zoomOut() },
        onContinuous = { delta -> state.zoomBy(-delta) },
    )

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
            contentDescription = "Тема карты",
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
            contentDescription = "Кольца дальности",
        )
    }
}

@Composable
private fun RepeatingZoomButton(
    icon: ImageVector,
    contentDescription: String,
    onStep: () -> Unit,
    onContinuous: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    Box(
        modifier =
            modifier
                .size(42.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xDD0F172A))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onStep()
                            val job =
                                coroutineScope.launch {
                                    delay(280) // Initial hold delay before continuous repeat
                                    while (isActive) {
                                        onContinuous(0.06f)
                                        delay(25) // Smooth 40 FPS continuous zooming
                                    }
                                }
                            tryAwaitRelease()
                            job.cancel()
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CameraInspectorCard(
    camera: Camera,
    isBlocked: Boolean,
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
            InspectorHeader(camera, isBlocked, onClose)
            Spacer(modifier = Modifier.height(8.dp))
            InspectorBody(camera, vehiclePoint)
            Spacer(modifier = Modifier.height(14.dp))
            InspectorActionsRow(camera, isBlocked, actions)
        }
    }
}

@Composable
private fun InspectorHeader(
    camera: Camera,
    isBlocked: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isBlocked) Color(0xFF64748B) else Color(0xFFEF4444)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${camera.kind.toRussianTitle()} #${camera.id}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (isBlocked) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(Заглушена)",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
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
    isBlocked: Boolean,
    actions: VectorMapActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isBlocked) {
            Button(
                onClick = { actions.onUnblockCamera(camera) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Разблокировать", fontSize = 12.sp)
            }
        } else {
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

@Composable
private fun MapQuickHazardBar(onReportHazardType: (HazardReportType) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xDD0F172A),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapQuickHazardButton(
                title = "ДПС",
                icon = Icons.Default.LocalPolice,
                accentColor = Color(0xFF38BDF8),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReportHazardType(HazardReportType.DPS_PATROL)
                },
            )
            MapQuickHazardButton(
                title = "Тренога",
                icon = Icons.Default.Videocam,
                accentColor = Color(0xFFF59E0B),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReportHazardType(HazardReportType.MOBILE_TRIPOD)
                },
            )
            MapQuickHazardButton(
                title = "ДТП",
                icon = Icons.Default.CarCrash,
                accentColor = Color(0xFFEF4444),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onReportHazardType(HazardReportType.ROAD_ACCIDENT)
                },
            )
        }
    }
}

@Composable
private fun MapQuickHazardButton(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0x22FFFFFF),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
