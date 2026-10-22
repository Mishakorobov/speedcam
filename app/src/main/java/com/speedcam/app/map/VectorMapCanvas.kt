package com.speedcam.app.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import com.speedcam.core.geo.MapViewport
import com.speedcam.core.geo.MercatorProjection
import com.speedcam.service.DetectionUiState
import com.speedcam.service.HazardView
import com.speedcam.service.SectionUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private data class RenderContext(
    val state: VectorMapState,
    val detectionState: DetectionUiState,
    val mapData: VectorMapData,
    val palette: MapPalette,
    val pulseProgress: Float,
    val tileManager: OfflineTileManager?,
)

private data class RadarDistanceParams(
    val centerX: Float,
    val centerY: Float,
    val palette: MapPalette,
    val pulseProgress: Float,
)

private data class CameraLayerParams(
    val cameras: List<Camera>,
    val selectedCamera: Camera?,
    val nearestWarningHazard: HazardView?,
    val showCones: Boolean,
    val filterKind: CameraKind?,
    val pulseProgress: Float,
)

private data class AzimuthConeParams(
    val cx: Float,
    val cy: Float,
    val lat: Double,
    val facingDeg: Double,
    val color: Color,
    val isWarning: Boolean,
)

private data class VehiclePuckParams(
    val screenX: Float,
    val screenY: Float,
    val vehicleBearingDeg: Double,
    val mapBearingDeg: Double,
    val speedKmh: Double,
    val overLimit: Boolean,
    val hasFix: Boolean,
)

/**
 * High-performance Jetpack Compose Canvas vector map renderer.
 */
@Composable
fun VectorMapCanvas(
    state: VectorMapState,
    detectionState: DetectionUiState,
    mapData: VectorMapData,
    tileManager: OfflineTileManager? = null,
    modifier: Modifier = Modifier,
    onCameraClick: (Camera) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pulseProgress by rememberPulseProgress()

    // Trigger canvas recomposition when background tiles are loaded
    val tileRevision = tileManager?.revision?.longValue ?: 0L

    val palette =
        remember(state.themeMode) {
            when (state.themeMode) {
                MapThemeMode.AUTOMOTIVE_DARK -> DarkRadarPalette
                MapThemeMode.DAY_LIGHT -> DayRoadPalette
            }
        }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .mapTransformInput(state)
                .mapTapInput(state, mapData.cameras, density.density, onCameraClick),
    ) {
        val viewport =
            MapViewport(
                center = state.center,
                zoom = state.zoom.toDouble(),
                bearingDeg = state.bearingDeg.toDouble(),
                width = size.width.toDouble(),
                height = size.height.toDouble(),
            )
        val ctx = RenderContext(state, detectionState, mapData, palette, pulseProgress, tileManager)
        renderMapLayers(ctx, viewport, textMeasurer)
    }
}

private fun DrawScope.renderMapLayers(
    ctx: RenderContext,
    viewport: MapViewport,
    textMeasurer: TextMeasurer,
) {
    drawRect(color = ctx.palette.background)

    if (ctx.state.showTiles && ctx.tileManager != null) {
        drawTileLayer(ctx.tileManager, viewport, ctx.palette)
    } else {
        drawSpatialGrid(viewport.width, viewport.height, ctx.palette)
    }

    val vehicleLat = ctx.detectionState.currentLat
    val vehicleLon = ctx.detectionState.currentLon
    val vehiclePoint = if (vehicleLat != null && vehicleLon != null) LatLon(vehicleLat, vehicleLon) else null

    if (ctx.state.showDistanceRings) {
        val ringCenter = vehiclePoint ?: viewport.center
        val (rcx, rcy) = MercatorProjection.project(ringCenter, viewport)
        val ringParams = RadarDistanceParams(rcx.toFloat(), rcy.toFloat(), ctx.palette, ctx.pulseProgress)
        drawRadarDistanceRings(ringParams, viewport, textMeasurer)
    }

    if (ctx.state.showRoads && ctx.mapData.roads.isNotEmpty()) {
        drawVectorRoads(ctx.mapData.roads, viewport, ctx.palette)
    }

    drawSpecsSections(ctx.mapData.sections, ctx.detectionState.activeSection, viewport, textMeasurer)

    val cameraParams =
        CameraLayerParams(
            cameras = ctx.mapData.cameras,
            selectedCamera = ctx.state.inspectedCamera,
            nearestWarningHazard = ctx.detectionState.nearest,
            showCones = ctx.state.showCameraCones,
            filterKind = ctx.state.filterKind,
            pulseProgress = ctx.pulseProgress,
        )
    drawCameras(cameraParams, viewport, textMeasurer)

    val nearest = ctx.detectionState.nearest
    if (vehiclePoint != null && nearest != null) {
        drawActiveHazardRay(vehiclePoint, nearest, viewport, ctx.pulseProgress, textMeasurer)
    }

    if (vehiclePoint != null) {
        val (vx, vy) = MercatorProjection.project(vehiclePoint, viewport)
        val puckParams =
            VehiclePuckParams(
                screenX = vx.toFloat(),
                screenY = vy.toFloat(),
                vehicleBearingDeg = ctx.detectionState.currentBearingDeg ?: 0.0,
                mapBearingDeg = viewport.bearingDeg,
                speedKmh = ctx.detectionState.speedKmh,
                overLimit = ctx.detectionState.overLimit,
                hasFix = ctx.detectionState.hasFix,
            )
        drawVehiclePuck(puckParams, textMeasurer)
    }
}

// --------------------------------------------------------------------- Gestures & Modifiers

@Composable
private fun rememberPulseProgress(): androidx.compose.runtime.State<Float> {
    val transition = rememberInfiniteTransition(label = "RadarPulse")
    return transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "RadarPulseAnim",
    )
}

private fun Modifier.mapTransformInput(state: VectorMapState): Modifier =
    pointerInput(state.zoom, state.bearingDeg, state.centerLat, state.centerLon) {
        detectTransformGestures { _, pan, zoomFactor, rotationFactor ->
            if (zoomFactor != 1.0f) {
                state.zoomBy((zoomFactor - 1.0f) * 1.5f)
            }
            if (pan != Offset.Zero) {
                state.pan(pan.x, pan.y, size.width.toFloat(), size.height.toFloat())
            }
            if (rotationFactor != 0.0f && state.followMode == MapFollowMode.FREE_ROAM) {
                state.bearingDeg = (state.bearingDeg + rotationFactor) % 360.0f
            }
        }
    }

private fun Modifier.mapTapInput(
    state: VectorMapState,
    cameras: List<Camera>,
    densityScale: Float,
    onCameraClick: (Camera) -> Unit,
): Modifier =
    pointerInput(cameras, state.zoom, state.bearingDeg, state.centerLat, state.centerLon) {
        detectTapGestures(
            onDoubleTap = { state.zoomIn() },
            onTap = { tapOffset ->
                val hitRadiusPx = 32f * densityScale
                val viewport =
                    MapViewport(
                        center = state.center,
                        zoom = state.zoom.toDouble(),
                        bearingDeg = state.bearingDeg.toDouble(),
                        width = size.width.toDouble(),
                        height = size.height.toDouble(),
                    )
                val hit = findTappedCamera(tapOffset, cameras, viewport, hitRadiusPx)
                if (hit != null) {
                    onCameraClick(hit)
                } else {
                    state.inspectCamera(null)
                }
            },
        )
    }

private fun findTappedCamera(
    tap: Offset,
    cameras: List<Camera>,
    viewport: MapViewport,
    hitRadius: Float,
): Camera? {
    var nearest: Camera? = null
    var minDistance = Double.MAX_VALUE
    for (cam in cameras) {
        val (px, py) = MercatorProjection.project(cam.position, viewport)
        val dx = tap.x - px
        val dy = tap.y - py
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= hitRadius && dist < minDistance) {
            minDistance = dist
            nearest = cam
        }
    }
    return nearest
}

// --------------------------------------------------------------------- Canvas Draw Helpers

private fun DrawScope.drawTileLayer(
    tileManager: OfflineTileManager,
    viewport: MapViewport,
    palette: MapPalette,
) {
    val intZoom = viewport.zoom.toInt().coerceIn(0, 18)
    val scaleFactor = 2.0.pow(viewport.zoom - intZoom)
    val tileSizePx = MercatorProjection.TILE_SIZE * scaleFactor
    val world = MercatorProjection.worldSize(viewport.zoom)
    val cx = MercatorProjection.longitudeToX(viewport.center.lon) * world
    val cy = MercatorProjection.latitudeToY(viewport.center.lat) * world

    val tileRange = TileCoordinateUtils.getVisibleTileRange(viewport, marginFactor = 1.35)

    withTransform({
        if (viewport.bearingDeg != 0.0) {
            rotate(
                degrees = -viewport.bearingDeg.toFloat(),
                pivot = Offset(viewport.width.toFloat() / 2f, viewport.height.toFloat() / 2f),
            )
        }
    }) {
        val destSize = tileSizePx.roundToInt()
        for (x in tileRange.minX..tileRange.maxX) {
            for (y in tileRange.minY..tileRange.maxY) {
                val tileWorldX = x.toDouble() * tileSizePx
                val tileWorldY = y.toDouble() * tileSizePx
                val ux = (viewport.width / 2.0) + (tileWorldX - cx)
                val uy = (viewport.height / 2.0) + (tileWorldY - cy)

                val destLeft = ux.roundToInt()
                val destTop = uy.roundToInt()

                val bitmap = tileManager.getTileBitmap(intZoom, x, y)
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(destLeft, destTop),
                        dstSize = IntSize(destSize, destSize),
                    )
                } else {
                    val parentFallback = tileManager.getParentTileFallback(intZoom, x, y)
                    if (parentFallback != null) {
                        val (parentBitmap, quadX, quadY) = parentFallback
                        val halfW = parentBitmap.width / 2
                        val halfH = parentBitmap.height / 2
                        drawImage(
                            image = parentBitmap,
                            srcOffset = IntOffset(quadX * halfW, quadY * halfH),
                            srcSize = IntSize(halfW, halfH),
                            dstOffset = IntOffset(destLeft, destTop),
                            dstSize = IntSize(destSize, destSize),
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSpatialGrid(
    width: Double,
    height: Double,
    palette: MapPalette,
) {
    val step = 100f
    var x = step
    while (x < width) {
        drawLine(palette.gridLine, Offset(x, 0f), Offset(x, height.toFloat()), 1f)
        x += step
    }
    var y = step
    while (y < height) {
        drawLine(palette.gridLine, Offset(0f, y), Offset(width.toFloat(), y), 1f)
        y += step
    }
}

private fun DrawScope.drawRadarDistanceRings(
    params: RadarDistanceParams,
    viewport: MapViewport,
    textMeasurer: TextMeasurer,
) {
    val distancesM = listOf(250.0, 500.0, 1000.0, 2000.0)
    val labels = listOf("250 м", "500 м", "1 км", "2 км")
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)

    for (i in distancesM.indices) {
        val distM = distancesM[i]
        val radiusPx = MercatorProjection.metersToPixels(distM, viewport.center.lat, viewport.zoom).toFloat()
        if (radiusPx in 30f..4000f) {
            drawCircle(
                color = params.palette.radarRing,
                radius = radiusPx,
                center = Offset(params.centerX, params.centerY),
                style = Stroke(width = 1.5f, pathEffect = dashEffect),
            )
            val layout =
                textMeasurer.measure(
                    text = labels[i],
                    style =
                        TextStyle(
                            fontSize = 10.sp,
                            color = params.palette.radarText,
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            drawText(
                textLayoutResult = layout,
                topLeft =
                    Offset(
                        x = params.centerX - layout.size.width / 2f,
                        y = params.centerY - radiusPx - layout.size.height - 2f,
                    ),
            )
        }
    }

    val pulseMaxPx = MercatorProjection.metersToPixels(1000.0, viewport.center.lat, viewport.zoom).toFloat()
    val pulseRadius = pulseMaxPx * params.pulseProgress
    val pulseAlpha = (1.0f - params.pulseProgress).coerceIn(0.0f, 0.4f)
    if (pulseRadius > 10f) {
        drawCircle(
            color = Color(0xFF00E5FF).copy(alpha = pulseAlpha),
            radius = pulseRadius,
            center = Offset(params.centerX, params.centerY),
            style = Stroke(width = 2.5f),
        )
    }
}

private fun DrawScope.drawVectorRoads(
    roads: List<VectorRoad>,
    viewport: MapViewport,
    palette: MapPalette,
) {
    for (road in roads) {
        if (road.points.size < 2) continue
        val path = Path()
        var first = true

        for (pt in road.points) {
            val (px, py) = MercatorProjection.project(pt, viewport)
            if (first) {
                path.moveTo(px.toFloat(), py.toFloat())
                first = false
            } else {
                path.lineTo(px.toFloat(), py.toFloat())
            }
        }

        val strokeColor =
            when (road.kind) {
                RoadClass.MOTORWAY -> palette.roadMotorway
                RoadClass.TRUNK -> palette.roadTrunk
                RoadClass.PRIMARY -> palette.roadPrimary
                RoadClass.SECONDARY -> palette.roadSecondary
                RoadClass.RESIDENTIAL -> palette.roadResidential
            }

        drawPath(path, strokeColor, style = Stroke(width = road.kind.strokeWidthDp * 1.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawSpecsSections(
    sections: List<SectionRecord>,
    activeSection: SectionUiState?,
    viewport: MapViewport,
    textMeasurer: TextMeasurer,
) {
    for (sec in sections) {
        val (x1, y1) = MercatorProjection.project(sec.from, viewport)
        val (x2, y2) = MercatorProjection.project(sec.to, viewport)

        val isActive = activeSection != null
        val corridorColor =
            if (isActive) {
                if (activeSection.overLimit) Color(0xFFEF4444) else Color(0xFF10B981)
            } else {
                Color(0xFF38BDF8)
            }

        drawLine(
            color = corridorColor.copy(alpha = 0.45f),
            start = Offset(x1.toFloat(), y1.toFloat()),
            end = Offset(x2.toFloat(), y2.toFloat()),
            strokeWidth = 14f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = corridorColor,
            start = Offset(x1.toFloat(), y1.toFloat()),
            end = Offset(x2.toFloat(), y2.toFloat()),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
        )

        val midX = (x1 + x2) / 2.0
        val midY = (y1 + y2) / 2.0
        drawCircle(Color.White, 16f, Offset(midX.toFloat(), midY.toFloat()))
        drawCircle(Color(0xFFEF4444), 16f, Offset(midX.toFloat(), midY.toFloat()), style = Stroke(width = 3.5f))
        val textLayout =
            textMeasurer.measure(
                text = sec.limitKmh.toString(),
                style = TextStyle(fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold),
            )
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(midX.toFloat() - textLayout.size.width / 2f, midY.toFloat() - textLayout.size.height / 2f),
        )
    }
}

private fun DrawScope.drawCameras(
    params: CameraLayerParams,
    viewport: MapViewport,
    textMeasurer: TextMeasurer,
) {
    val visible =
        params.cameras.filter { cam ->
            params.filterKind == null || cam.kind == params.filterKind
        }

    for (cam in visible) {
        val (cx, cy) = MercatorProjection.project(cam.position, viewport)
        if (!isOutsideViewport(cx, cy, viewport.width, viewport.height)) {
            renderCameraMarker(cam, Offset(cx.toFloat(), cy.toFloat()), params, viewport, textMeasurer)
        }
    }
}

private fun DrawScope.renderCameraMarker(
    cam: Camera,
    pos: Offset,
    params: CameraLayerParams,
    viewport: MapViewport,
    textMeasurer: TextMeasurer,
) {
    val cx = pos.x
    val cy = pos.y
    val isSelected = params.selectedCamera?.id == cam.id
    val isWarning = params.nearestWarningHazard?.cameraId == cam.id
    val color = getCameraColor(cam.kind)

    val facing = cam.facingDeg
    if (params.showCones && facing != null) {
        val coneParams = AzimuthConeParams(cx, cy, cam.position.lat, facing, color, isWarning)
        drawCameraAzimuthCone(coneParams, viewport)
    }

    if (isWarning || isSelected) {
        val auraColor = if (isWarning) Color(0xFFEF4444) else Color(0xFF38BDF8)
        val auraRadius = 24f + params.pulseProgress * 22f
        drawCircle(
            color = auraColor.copy(alpha = (1.0f - params.pulseProgress) * 0.7f),
            radius = auraRadius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.5f),
        )
    }

    drawCameraMarkerPin(cx, cy, color)

    val limit = cam.limitKmh
    if (limit != null && limit > 0) {
        drawCameraSpeedLimitBadge(cx, cy, limit, textMeasurer)
    }
}

private fun isOutsideViewport(
    cx: Double,
    cy: Double,
    w: Double,
    h: Double,
    pad: Double = 100.0,
): Boolean = cx !in -pad..(w + pad) || cy !in -pad..(h + pad)

private fun DrawScope.drawCameraAzimuthCone(
    params: AzimuthConeParams,
    viewport: MapViewport,
) {
    val coneRadiusPx =
        MercatorProjection
            .metersToPixels(180.0, params.lat, viewport.zoom)
            .toFloat()
            .coerceIn(35f, 250f)
    val angleRad = (params.facingDeg - viewport.bearingDeg - 90.0) * (PI / 180.0)
    val halfSpanRad = 22.0 * (PI / 180.0)

    val conePath = Path()
    conePath.moveTo(params.cx, params.cy)
    val numSteps = 8
    for (step in 0..numSteps) {
        val t = -halfSpanRad + (2.0 * halfSpanRad * step / numSteps)
        val curAngle = angleRad + t
        val px = params.cx + coneRadiusPx * cos(curAngle).toFloat()
        val py = params.cy + coneRadiusPx * sin(curAngle).toFloat()
        conePath.lineTo(px, py)
    }
    conePath.close()

    val coneAlpha = if (params.isWarning) 0.55f else 0.25f
    drawPath(path = conePath, color = params.color.copy(alpha = coneAlpha), style = Fill)
    drawPath(path = conePath, color = params.color.copy(alpha = coneAlpha + 0.2f), style = Stroke(width = 1.5f))
}

private fun DrawScope.drawCameraMarkerPin(
    cx: Float,
    cy: Float,
    color: Color,
) {
    drawCircle(Color.White, 16f, Offset(cx, cy))
    drawCircle(color, 14f, Offset(cx, cy))
    drawCircle(Color.White, 6f, Offset(cx, cy))
}

private fun DrawScope.drawCameraSpeedLimitBadge(
    cx: Float,
    cy: Float,
    limit: Int,
    textMeasurer: TextMeasurer,
) {
    val bx = cx + 18f
    val by = cy - 18f
    drawCircle(Color.White, 13f, Offset(bx, by))
    drawCircle(Color(0xFFDC2626), 13f, Offset(bx, by), style = Stroke(width = 3f))
    val textLayout =
        textMeasurer.measure(
            text = limit.toString(),
            style = TextStyle(fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.ExtraBold),
        )
    drawText(
        textLayoutResult = textLayout,
        topLeft = Offset(bx - textLayout.size.width / 2f, by - textLayout.size.height / 2f),
    )
}

private fun DrawScope.drawActiveHazardRay(
    vehiclePoint: LatLon,
    nearestHazard: HazardView,
    viewport: MapViewport,
    pulseProgress: Float,
    textMeasurer: TextMeasurer,
) {
    val (vx, vy) = MercatorProjection.project(vehiclePoint, viewport)
    val (hx, hy) = MercatorProjection.project(LatLon(nearestHazard.latitude, nearestHazard.longitude), viewport)

    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), pulseProgress * 25f)
    drawLine(
        color = Color(0xFFEF4444).copy(alpha = 0.85f),
        start = Offset(vx.toFloat(), vy.toFloat()),
        end = Offset(hx.toFloat(), hy.toFloat()),
        strokeWidth = 3f,
        pathEffect = dashEffect,
    )

    val midX = (vx + hx) / 2.0
    val midY = (vy + hy) / 2.0
    val distText = "⚡ ${nearestHazard.distanceM.toInt()} м"
    val textLayout =
        textMeasurer.measure(
            text = distText,
            style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold),
        )
    val pad = 8f
    val rectLeft = midX.toFloat() - textLayout.size.width / 2f - pad
    val rectTop = midY.toFloat() - textLayout.size.height / 2f - pad / 2f
    val rectWidth = textLayout.size.width + pad * 2f
    val rectHeight = textLayout.size.height + pad

    drawRoundRect(
        color = Color(0xFFDC2626),
        topLeft = Offset(rectLeft, rectTop),
        size = Size(rectWidth, rectHeight),
        cornerRadius = CornerRadius(12f, 12f),
    )
    drawText(
        textLayoutResult = textLayout,
        topLeft = Offset(midX.toFloat() - textLayout.size.width / 2f, midY.toFloat() - textLayout.size.height / 2f),
    )
}

private fun DrawScope.drawVehiclePuck(
    params: VehiclePuckParams,
    textMeasurer: TextMeasurer,
) {
    val accuracyColor = if (params.hasFix) Color(0xFF00E5FF) else Color(0xFF64748B)
    drawCircle(accuracyColor.copy(alpha = 0.18f), 36f, Offset(params.screenX, params.screenY))

    val relativeHeadingDeg = params.vehicleBearingDeg - params.mapBearingDeg
    val angleRad = (relativeHeadingDeg - 90.0) * (PI / 180.0)

    val puckPath = Path()
    val tipLength = 26f
    val wingLength = 22f
    val indentLength = 8f
    val wingAngle = 145.0 * (PI / 180.0)

    val tipX = params.screenX + tipLength * cos(angleRad).toFloat()
    val tipY = params.screenY + tipLength * sin(angleRad).toFloat()
    val leftX = params.screenX + wingLength * cos(angleRad + wingAngle).toFloat()
    val leftY = params.screenY + wingLength * sin(angleRad + wingAngle).toFloat()
    val indentX = params.screenX + indentLength * cos(angleRad + PI).toFloat()
    val indentY = params.screenY + indentLength * sin(angleRad + PI).toFloat()
    val rightX = params.screenX + wingLength * cos(angleRad - wingAngle).toFloat()
    val rightY = params.screenY + wingLength * sin(angleRad - wingAngle).toFloat()

    puckPath.moveTo(tipX, tipY)
    puckPath.lineTo(leftX, leftY)
    puckPath.lineTo(indentX, indentY)
    puckPath.lineTo(rightX, rightY)
    puckPath.close()

    val puckColor = if (params.overLimit) Color(0xFFEF4444) else Color(0xFF00E5FF)
    drawPath(puckPath, puckColor, style = Fill)
    drawPath(puckPath, Color.White, style = Stroke(width = 3.5f))

    if (params.speedKmh > 1.0) {
        val speedStr = "${params.speedKmh.toInt()}"
        val textLayout =
            textMeasurer.measure(
                text = speedStr,
                style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.ExtraBold),
            )
        val pad = 8f
        val badgeW = textLayout.size.width + pad * 2f
        val badgeH = textLayout.size.height + pad / 2f
        val bx = params.screenX - badgeW / 2f
        val by = params.screenY - 50f

        drawRoundRect(
            color = if (params.overLimit) Color(0xFFDC2626) else Color(0xCC0F172A),
            topLeft = Offset(bx, by),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(10f, 10f),
        )
        val textTopLeft =
            Offset(
                x = params.screenX - textLayout.size.width / 2f,
                y = by + (badgeH - textLayout.size.height) / 2f,
            )
        drawText(
            textLayoutResult = textLayout,
            topLeft = textTopLeft,
        )
    }
}

private fun getCameraColor(kind: CameraKind): Color =
    when (kind) {
        CameraKind.FIXED -> Color(0xFFEF4444)
        CameraKind.RED_LIGHT -> Color(0xFFF97316)
        CameraKind.AVERAGE_ENTRY, CameraKind.AVERAGE_EXIT -> Color(0xFF06B6D4)
        CameraKind.COMPLEX -> Color(0xFF8B5CF6)
        CameraKind.MOBILE -> Color(0xFFEAB308)
        CameraKind.DUMMY -> Color(0xFF64748B)
        CameraKind.BUS_LANE -> Color(0xFF10B981)
        CameraKind.ROAD_MARKING -> Color(0xFFEC4899)
        CameraKind.UNKNOWN -> Color(0xFF3B82F6)
        else -> Color(0xFF3B82F6)
    }

// --------------------------------------------------------------------- Map Theme Palettes

private data class MapPalette(
    val background: Color,
    val gridLine: Color,
    val radarRing: Color,
    val radarText: Color,
    val roadMotorway: Color,
    val roadTrunk: Color,
    val roadPrimary: Color,
    val roadSecondary: Color,
    val roadResidential: Color,
)

private val DarkRadarPalette =
    MapPalette(
        background = Color(0xFF0B101D),
        gridLine = Color(0x1838BDF8),
        radarRing = Color(0x3500E5FF),
        radarText = Color(0x9900E5FF),
        roadMotorway = Color(0xFF3B82F6),
        roadTrunk = Color(0xFF2563EB),
        roadPrimary = Color(0xFF1E293B),
        roadSecondary = Color(0xFF192231),
        roadResidential = Color(0xFF131B27),
    )

private val DayRoadPalette =
    MapPalette(
        background = Color(0xFFF8FAFC),
        gridLine = Color(0x150F172A),
        radarRing = Color(0x330284C7),
        radarText = Color(0xAA0369A1),
        roadMotorway = Color(0xFFFB923C),
        roadTrunk = Color(0xFFF59E0B),
        roadPrimary = Color(0xFFCBD5E1),
        roadSecondary = Color(0xFFE2E8F0),
        roadResidential = Color(0xFFF1F5F9),
    )
