package com.speedcam.app.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.Surface
import androidx.car.app.SurfaceContainer
import com.speedcam.app.SpeedCamApp
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import com.speedcam.core.geo.MapViewport
import com.speedcam.core.geo.MercatorProjection
import com.speedcam.service.DetectionUiState
import kotlin.math.roundToInt

/**
 * State machine managing map zoom, pan offsets, and interaction mode on automotive head units.
 *
 * Separated from Android Canvas rendering primitives so viewport geometry and interaction math
 * can be tested 100% on standard JVM without mocking Android Skia graphic objects.
 */
class CarMapViewportState(
    initialZoom: Float = DEFAULT_ZOOM,
) {
    var zoom: Float = initialZoom
        private set
    var panOffsetX: Float = 0f
        private set
    var panOffsetY: Float = 0f
        private set
    var isPanMode: Boolean = false
        private set

    fun setPanMode(enabled: Boolean) {
        isPanMode = enabled
        if (!enabled) {
            panOffsetX = 0f
            panOffsetY = 0f
        }
    }

    fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) {
        if (!isPanMode) isPanMode = true
        panOffsetX -= distanceX
        panOffsetY -= distanceY
    }

    fun onScale(scaleFactor: Float) {
        zoom = (zoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun zoomIn() {
        zoom = (zoom + 0.5f).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun zoomOut() {
        zoom = (zoom - 0.5f).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun resetCenter() {
        isPanMode = false
        panOffsetX = 0f
        panOffsetY = 0f
        zoom = DEFAULT_ZOOM
    }

    companion object {
        const val DEFAULT_ZOOM = 15.5f
        const val MIN_ZOOM = 12.0f
        const val MAX_ZOOM = 18.5f
    }
}

/**
 * High-performance hardware Canvas surface renderer for Android Auto Head Unit displays.
 *
 * Renders full automotive dark cartography, concentric radar range rings, road network,
 * camera enforcement installations, live warning cones, and the vehicle puck directly onto
 * the [Surface] provided by the car host.
 */
class CarMapSurfaceRenderer(
    private val context: Context,
    val viewportState: CarMapViewportState = CarMapViewportState(),
) {
    private val lock = Any()
    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect = Rect()

    val zoom: Float get() = viewportState.zoom
    val panOffsetX: Float get() = viewportState.panOffsetX
    val panOffsetY: Float get() = viewportState.panOffsetY
    val isPanMode: Boolean get() = viewportState.isPanMode

    // Graphic objects initialized lazily to avoid JVM test initialization errors
    private val bgPaint by lazy { Paint().apply { color = COLOR_BG } }
    private val gridPaint by lazy {
        Paint().apply {
            color = COLOR_GRID
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
    }
    private val ringPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_RADAR_RING
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(16f, 16f), 0f)
        }
    }
    private val ringTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_RADAR_TEXT
            textSize = 18f
            isFakeBoldText = true
        }
    }
    private val vehicleFillPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_VEHICLE_NORMAL
        }
    }
    private val vehicleStrokePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
    }
    private val vehicleHaloPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(40, 56, 189, 248)
        }
    }
    private val cameraBgPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }
    private val cameraBorderPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
    }
    private val cameraTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    private val warningHaloPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(80, 239, 68, 68)
        }
    }
    private val sectionPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            color = COLOR_SECTION
        }
    }
    private val badgeBgPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(200, 15, 23, 42)
        }
    }
    private val badgeTextPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }
    private val arrowPath by lazy { Path() }

    fun attachSurface(container: SurfaceContainer) {
        synchronized(lock) {
            surfaceContainer = container
            visibleArea = Rect(0, 0, container.width, container.height)
        }
    }

    fun detachSurface() {
        synchronized(lock) {
            surfaceContainer = null
        }
    }

    fun updateVisibleArea(rect: Rect) {
        synchronized(lock) {
            visibleArea = rect
        }
    }

    fun setPanMode(enabled: Boolean) = viewportState.setPanMode(enabled)

    fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) = viewportState.onScroll(distanceX, distanceY)

    fun onScale(scaleFactor: Float) = viewportState.onScale(scaleFactor)

    fun zoomIn() = viewportState.zoomIn()

    fun zoomOut() = viewportState.zoomOut()

    fun resetCenter() = viewportState.resetCenter()

    /**
     * Renders a full frame onto the automotive Head Unit display surface.
     */
    fun render(state: DetectionUiState) {
        val container = surfaceContainer ?: return
        val surface = container.surface
        if (surface == null || !surface.isValid) return

        synchronized(lock) {
            val canvas: Canvas =
                try {
                    surface.lockCanvas(null) ?: return
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot lock canvas for rendering: ${e.message}")
                    return
                }

            try {
                drawFrame(canvas, container.width, container.height, state)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot unlock and post canvas: ${e.message}")
                }
            }
        }
    }

    private fun drawFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        state: DetectionUiState,
    ) {
        // 1. Automotive Dark Map Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val vehicleLat = state.currentLat ?: DEFAULT_LAT
        val vehicleLon = state.currentLon ?: DEFAULT_LON
        val bearing = state.currentBearingDeg ?: 0.0

        // Viewport setup
        val center = LatLon(vehicleLat, vehicleLon)
        val viewport =
            MapViewport(
                center = center,
                zoom = zoom.toDouble(),
                bearingDeg = 0.0,
                width = width.toDouble(),
                height = height.toDouble(),
            )

        val vehicleScreenX = (width / 2f) + panOffsetX
        val vehicleScreenY = (height * 0.62f) + panOffsetY

        // 2. Subtle Coordinate Grid
        drawGrid(canvas, width, height)

        // 3. Average Speed Corridors (SPECS)
        drawSectionCorridors(canvas, viewport, center, vehicleScreenX, vehicleScreenY)

        // 4. Concentric Radar Range Rings
        drawRadarRings(canvas, vehicleLat, vehicleScreenX, vehicleScreenY)

        // 5. Cameras and Enforcements from Database
        drawCameras(canvas, viewport, center, vehicleScreenX, vehicleScreenY, state)

        // 6. Vehicle Navigation Puck
        drawVehiclePuck(canvas, vehicleScreenX, vehicleScreenY, bearing, state)

        // 7. Pan Mode / Scale Watermark
        drawStatusOverlays(canvas, width, height)
    }

    private fun drawGrid(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {
        val step = 100f
        var x = 0f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
    }

    private fun drawRadarRings(
        canvas: Canvas,
        lat: Double,
        cx: Float,
        cy: Float,
    ) {
        val rangesMeters = floatArrayOf(250f, 500f, 1000f)
        for (range in rangesMeters) {
            val rPx = MercatorProjection.metersToPixels(range.toDouble(), lat, zoom.toDouble()).toFloat()
            if (rPx in 10f..1500f) {
                canvas.drawCircle(cx, cy, rPx, ringPaint)
                val label = if (range >= 1000f) "${(range / 1000).roundToInt()} км" else "${range.roundToInt()} м"
                canvas.drawText(label, cx + 8f, cy - rPx + 20f, ringTextPaint)
            }
        }
    }

    private fun drawSectionCorridors(
        canvas: Canvas,
        viewport: MapViewport,
        center: LatLon,
        vehicleScreenX: Float,
        vehicleScreenY: Float,
    ) {
        val store = runCatching { SpeedCamApp.cameraStore(context) }.getOrNull() ?: return
        val sections = store.sections
        if (sections.isEmpty()) return

        for (section in sections) {
            val (x1, y1) = projectRel(section.from, center, viewport, vehicleScreenX, vehicleScreenY)
            val (x2, y2) = projectRel(section.to, center, viewport, vehicleScreenX, vehicleScreenY)
            canvas.drawLine(x1, y1, x2, y2, sectionPaint)
        }
    }

    private fun drawCameras(
        canvas: Canvas,
        viewport: MapViewport,
        center: LatLon,
        vehicleScreenX: Float,
        vehicleScreenY: Float,
        state: DetectionUiState,
    ) {
        val store = runCatching { SpeedCamApp.cameraStore(context) }.getOrNull() ?: return
        val box = Geo.boundingBox(center, 3000.0)
        val cameras = runCatching { store.cameraDao.inBox(box) }.getOrDefault(emptyList())

        val nearestId = state.nearest?.cameraId

        for (cam in cameras) {
            val (px, py) = projectRel(LatLon(cam.lat, cam.lon), center, viewport, vehicleScreenX, vehicleScreenY)
            if (px < -50 || px > viewport.width + 50 || py < -50 || py > viewport.height + 50) continue

            val isNearest = cam.id == nearestId

            if (isNearest) {
                warningHaloPaint.color = if (state.overLimit) Color.argb(120, 239, 68, 68) else Color.argb(90, 245, 158, 11)
                canvas.drawCircle(px, py, 32f, warningHaloPaint)
                canvas.drawCircle(px, py, 24f, warningHaloPaint)
            }

            val badgeColor =
                when (cam.kind) {
                    CameraKind.MOBILE.ordinal -> COLOR_CAMERA_MOBILE
                    CameraKind.RED_LIGHT.ordinal -> COLOR_CAMERA_RED_LIGHT
                    CameraKind.BUS_LANE.ordinal -> COLOR_CAMERA_BUS
                    else -> COLOR_CAMERA_FIXED
                }

            cameraBgPaint.color = badgeColor
            canvas.drawCircle(px, py, 16f, cameraBgPaint)

            cameraBorderPaint.color = if (cam.limitKmh != null) Color.rgb(239, 68, 68) else Color.WHITE
            canvas.drawCircle(px, py, 16f, cameraBorderPaint)

            val text = cam.limitKmh?.toString() ?: "CAM"
            canvas.drawText(text, px, py + 6f, cameraTextPaint)
        }
    }

    private fun drawVehiclePuck(
        canvas: Canvas,
        vx: Float,
        vy: Float,
        bearingDeg: Double,
        state: DetectionUiState,
    ) {
        // Glowing halo around vehicle
        vehicleHaloPaint.color = if (state.overLimit) Color.argb(50, 239, 68, 68) else Color.argb(40, 56, 189, 248)
        canvas.drawCircle(vx, vy, 38f, vehicleHaloPaint)

        // Arrow shape
        arrowPath.reset()
        arrowPath.moveTo(0f, -24f)
        arrowPath.lineTo(16f, 18f)
        arrowPath.lineTo(0f, 9f)
        arrowPath.lineTo(-16f, 18f)
        arrowPath.close()

        canvas.save()
        canvas.translate(vx, vy)
        canvas.rotate(bearingDeg.toFloat())

        vehicleFillPaint.color = if (state.overLimit) COLOR_VEHICLE_DANGER else COLOR_VEHICLE_NORMAL
        canvas.drawPath(arrowPath, vehicleFillPaint)
        canvas.drawPath(arrowPath, vehicleStrokePaint)
        canvas.restore()

        // Speed readout badge under the vehicle puck
        val speedText = "${state.speedKmh.roundToInt()} км/ч"
        val badgeRect = RectF(vx - 50f, vy + 26f, vx + 50f, vy + 60f)
        badgeBgPaint.color = if (state.overLimit) Color.argb(220, 220, 38, 38) else Color.argb(200, 15, 23, 42)
        canvas.drawRoundRect(badgeRect, 12f, 12f, badgeBgPaint)
        canvas.drawText(speedText, vx, vy + 50f, badgeTextPaint)
    }

    private fun drawStatusOverlays(
        canvas: Canvas,
        width: Int,
        height: Int,
    ) {
        if (isPanMode) {
            val panRect = RectF((width / 2f) - 130f, 20f, (width / 2f) + 130f, 62f)
            badgeBgPaint.color = Color.argb(230, 2, 132, 199)
            canvas.drawRoundRect(panRect, 16f, 16f, badgeBgPaint)
            badgeTextPaint.textSize = 17f
            canvas.drawText("РЕЖИМ ОБЗОРА КАРТЫ", width / 2f, 48f, badgeTextPaint)
            badgeTextPaint.textSize = 22f
        }
    }

    private fun projectRel(
        point: LatLon,
        center: LatLon,
        viewport: MapViewport,
        screenCenterX: Float,
        screenCenterY: Float,
    ): Pair<Float, Float> {
        val (projX, projY) = MercatorProjection.project(point, viewport)
        val (cProjX, cProjY) = MercatorProjection.project(center, viewport)
        val relX = (projX - cProjX).toFloat() + screenCenterX
        val relY = (projY - cProjY).toFloat() + screenCenterY
        return Pair(relX, relY)
    }

    companion object {
        private const val TAG = "CarMapSurfaceRenderer"

        const val DEFAULT_ZOOM = 15.5f
        const val MIN_ZOOM = 12.0f
        const val MAX_ZOOM = 18.5f

        const val DEFAULT_LAT = 55.7558
        const val DEFAULT_LON = 37.6173

        val COLOR_BG = Color.rgb(11, 19, 43)
        val COLOR_GRID = Color.argb(20, 56, 189, 248)
        val COLOR_RADAR_RING = Color.argb(60, 56, 189, 248)
        val COLOR_RADAR_TEXT = Color.argb(140, 56, 189, 248)
        val COLOR_VEHICLE_NORMAL = Color.rgb(56, 189, 248)
        val COLOR_VEHICLE_DANGER = Color.rgb(239, 68, 68)
        val COLOR_CAMERA_FIXED = Color.rgb(30, 41, 59)
        val COLOR_CAMERA_MOBILE = Color.rgb(245, 158, 11)
        val COLOR_CAMERA_RED_LIGHT = Color.rgb(220, 38, 38)
        val COLOR_CAMERA_BUS = Color.rgb(14, 165, 233)
        val COLOR_SECTION = Color.argb(180, 245, 158, 11)
    }
}
