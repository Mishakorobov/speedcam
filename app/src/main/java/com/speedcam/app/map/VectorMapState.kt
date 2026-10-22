package com.speedcam.app.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon
import com.speedcam.core.geo.MercatorProjection

/** Map following behavior relative to vehicle position and heading. */
enum class MapFollowMode {
    /** Map rotates dynamically to align with vehicle heading, vehicle centered. */
    FOLLOW_HEADING,

    /** Map is oriented North-up, vehicle centered. */
    FOLLOW_NORTH,

    /** Free panning and zooming by user. */
    FREE_ROAM,
}

/** Visual appearance mode for the vector map canvas. */
enum class MapThemeMode {
    /** OLED high-contrast dark theme designed for automotive night driving and head units. */
    AUTOMOTIVE_DARK,

    /** High-contrast daylight theme for daytime driving. */
    DAY_LIGHT,
}

/**
 * Observable UI state and interaction controller for the lightweight vector map.
 */
class VectorMapState(
    initialCenter: LatLon = LatLon(55.7558, 37.6173),
    initialZoom: Float = 15.5f,
    initialBearing: Float = 0.0f,
    initialFollowMode: MapFollowMode = MapFollowMode.FOLLOW_HEADING,
    initialTheme: MapThemeMode = MapThemeMode.AUTOMOTIVE_DARK,
) {
    var centerLat by mutableDoubleStateOf(initialCenter.lat)
    var centerLon by mutableDoubleStateOf(initialCenter.lon)
    var zoom by mutableFloatStateOf(initialZoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
    var bearingDeg by mutableFloatStateOf(initialBearing)
    var followMode by mutableStateOf(initialFollowMode)
    var themeMode by mutableStateOf(initialTheme)

    // Visual layer toggles
    var showTiles by mutableStateOf(true)
    var showDistanceRings by mutableStateOf(true)
    var showRoads by mutableStateOf(true)
    var showCameraCones by mutableStateOf(true)

    // Filtering
    var filterKind by mutableStateOf<CameraKind?>(null)

    // Selection
    var inspectedCamera by mutableStateOf<Camera?>(null)

    // Dynamic auto-centering tracking
    var hasAutoCentered by mutableStateOf(false)

    val center: LatLon get() = LatLon(centerLat, centerLon)

    fun isAtDefaultCenter(): Boolean {
        val latDiff = kotlin.math.abs(centerLat - 55.7558)
        val lonDiff = kotlin.math.abs(centerLon - 37.6173)
        return latDiff < 1e-4 && lonDiff < 1e-4
    }

    /**
     * Updates vehicle telemetry and adjusts camera position if follow mode is active.
     */
    fun updateVehicle(
        lat: Double?,
        lon: Double?,
        vehicleBearingDeg: Double?,
    ) {
        if (lat == null || lon == null) return
        when (followMode) {
            MapFollowMode.FOLLOW_HEADING -> {
                centerLat = lat
                centerLon = lon
                bearingDeg = (vehicleBearingDeg ?: 0.0).toFloat()
            }

            MapFollowMode.FOLLOW_NORTH -> {
                centerLat = lat
                centerLon = lon
                bearingDeg = 0.0f
            }

            MapFollowMode.FREE_ROAM -> {
                // Do not reposition in free roam
            }
        }
    }

    /**
     * Shifts the map center by screen pixel offsets [dx] and [dy].
     * Automatically disengages follow mode into [MapFollowMode.FREE_ROAM].
     */
    fun pan(
        dx: Float,
        dy: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        followMode = MapFollowMode.FREE_ROAM
        val currentCenter = center
        // Center projected to screen is (viewportWidth/2, viewportHeight/2)
        val targetScreenX = (viewportWidth / 2.0) - dx
        val targetScreenY = (viewportHeight / 2.0) - dy

        val viewport =
            com.speedcam.core.geo.MapViewport(
                center = currentCenter,
                zoom = zoom.toDouble(),
                bearingDeg = bearingDeg.toDouble(),
                width = viewportWidth.toDouble(),
                height = viewportHeight.toDouble(),
            )
        val newCenter =
            MercatorProjection.unproject(
                screenX = targetScreenX,
                screenY = targetScreenY,
                viewport = viewport,
            )
        centerLat = newCenter.lat
        centerLon = newCenter.lon
    }

    /**
     * Zooms the map by multiplying scale factor.
     */
    fun zoomBy(delta: Float) {
        zoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun zoomIn() {
        zoomBy(1.0f)
    }

    fun zoomOut() {
        zoomBy(-1.0f)
    }

    /**
     * Snaps the camera directly to [target] coordinate and initiates follow mode.
     */
    fun centerOn(
        target: LatLon,
        targetZoom: Float? = null,
        mode: MapFollowMode = MapFollowMode.FOLLOW_NORTH,
    ) {
        centerLat = target.lat
        centerLon = target.lon
        targetZoom?.let { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) }
        followMode = mode
        if (mode == MapFollowMode.FOLLOW_NORTH) {
            bearingDeg = 0.0f
        }
    }

    /**
     * Toggles orientation mode between Heading-Up and North-Up.
     */
    fun toggleOrientation(currentVehicleBearing: Double? = null) {
        when (followMode) {
            MapFollowMode.FOLLOW_HEADING -> {
                followMode = MapFollowMode.FOLLOW_NORTH
                bearingDeg = 0.0f
            }

            MapFollowMode.FOLLOW_NORTH -> {
                followMode = MapFollowMode.FOLLOW_HEADING
                bearingDeg = (currentVehicleBearing ?: 0.0).toFloat()
            }

            MapFollowMode.FREE_ROAM -> {
                bearingDeg = 0.0f
            }
        }
    }

    fun inspectCamera(camera: Camera?) {
        inspectedCamera = camera
    }

    fun computeVisibleBoundingBox(
        viewportWidth: Float,
        viewportHeight: Float,
    ): BoundingBox {
        val viewport =
            com.speedcam.core.geo.MapViewport(
                center = center,
                zoom = zoom.toDouble(),
                bearingDeg = bearingDeg.toDouble(),
                width = viewportWidth.toDouble(),
                height = viewportHeight.toDouble(),
            )
        return MercatorProjection.viewportBoundingBox(viewport)
    }

    companion object {
        const val MIN_ZOOM = 3.0f
        const val MAX_ZOOM = 19.0f
    }
}
