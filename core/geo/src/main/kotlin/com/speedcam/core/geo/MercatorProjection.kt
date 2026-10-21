package com.speedcam.core.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 2D map viewport descriptor for projection calculations.
 */
data class MapViewport(
    val center: LatLon,
    val zoom: Double,
    val bearingDeg: Double = 0.0,
    val width: Double,
    val height: Double,
)

/**
 * Spherical Web Mercator projection mathematics (EPSG:3857).
 *
 * Maps WGS-84 coordinates ([LatLon]) into a normalized [0.0, 1.0] coordinate space
 * and projects onto world/screen pixel coordinates at arbitrary zoom levels with
 * arbitrary heading rotation and viewport dimensions.
 *
 * Pure Kotlin implementation: zero Android dependencies, fully testable on JVM.
 */
object MercatorProjection {
    /** Maximum latitude cut-off for standard Web Mercator projection. */
    const val MAX_LATITUDE = 85.05112878

    /** Base tile size in pixels at zoom level 0. */
    const val TILE_SIZE = 256.0

    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    /**
     * Converts a longitude in degrees [-180, 180] into normalized Mercator X coordinate in [0.0, 1.0].
     */
    fun longitudeToX(lon: Double): Double {
        val clampedLon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return (clampedLon + 180.0) / 360.0
    }

    /**
     * Converts a latitude in degrees [-90, 90] into normalized Mercator Y coordinate in [0.0, 1.0].
     */
    fun latitudeToY(lat: Double): Double {
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val rad = clampedLat * DEG_TO_RAD
        val sinVal = sin(rad)
        return 0.5 - ln((1.0 + sinVal) / (1.0 - sinVal)) / (4.0 * PI)
    }

    /**
     * Converts a normalized Mercator X coordinate in [0.0, 1.0] back to longitude in degrees [-180, 180].
     */
    fun xToLongitude(x: Double): Double {
        val wrappedX = ((x % 1.0) + 1.0) % 1.0
        return wrappedX * 360.0 - 180.0
    }

    /**
     * Converts a normalized Mercator Y coordinate in [0.0, 1.0] back to latitude in degrees [-MAX_LAT, MAX_LAT].
     */
    fun yToLatitude(y: Double): Double {
        val clampedY = y.coerceIn(0.0, 1.0)
        val latRad = 2.0 * atan(exp(PI * (1.0 - 2.0 * clampedY))) - PI / 2.0
        return (latRad * RAD_TO_DEG).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    }

    /**
     * Total world dimension in pixels at [zoom].
     */
    fun worldSize(zoom: Double): Double = TILE_SIZE * 2.0.pow(zoom)

    /**
     * Ground resolution in metres per pixel at a given [lat] and [zoom].
     */
    fun metersPerPixel(
        lat: Double,
        zoom: Double,
    ): Double {
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val latCos = cos(clampedLat * DEG_TO_RAD)
        return (2.0 * PI * Geo.EARTH_RADIUS_M * latCos) / worldSize(zoom)
    }

    /**
     * Converts physical distance in [meters] to screen pixels at given [lat] and [zoom].
     */
    fun metersToPixels(
        meters: Double,
        lat: Double,
        zoom: Double,
    ): Double {
        val mpp = metersPerPixel(lat, zoom)
        return if (mpp > 0.0) meters / mpp else 0.0
    }

    /**
     * Projects a geographic [point] to 2D screen coordinates (px, py).
     */
    fun project(
        point: LatLon,
        viewport: MapViewport,
    ): Pair<Double, Double> {
        val world = worldSize(viewport.zoom)
        val cx = longitudeToX(viewport.center.lon) * world
        val cy = latitudeToY(viewport.center.lat) * world
        val px = longitudeToX(point.lon) * world
        val py = latitudeToY(point.lat) * world

        val dx = px - cx
        val dy = py - cy

        val theta = -viewport.bearingDeg * DEG_TO_RAD
        val cosT = cos(theta)
        val sinT = sin(theta)

        val rotX = dx * cosT - dy * sinT
        val rotY = dx * sinT + dy * cosT

        val screenX = viewport.width / 2.0 + rotX
        val screenY = viewport.height / 2.0 + rotY
        return Pair(screenX, screenY)
    }

    /**
     * Unprojects 2D screen coordinates (screenX, screenY) back to a geographic [LatLon].
     */
    fun unproject(
        screenX: Double,
        screenY: Double,
        viewport: MapViewport,
    ): LatLon {
        val rotX = screenX - viewport.width / 2.0
        val rotY = screenY - viewport.height / 2.0

        val theta = -viewport.bearingDeg * DEG_TO_RAD
        val cosT = cos(-theta)
        val sinT = sin(-theta)

        val dx = rotX * cosT - rotY * sinT
        val dy = rotX * sinT + rotY * cosT

        val world = worldSize(viewport.zoom)
        val cx = longitudeToX(viewport.center.lon) * world
        val cy = latitudeToY(viewport.center.lat) * world

        val px = cx + dx
        val py = cy + dy

        val normX = px / world
        val normY = py / world

        return LatLon(
            lat = yToLatitude(normY),
            lon = xToLongitude(normX),
        )
    }

    /**
     * Computes the geographic bounding box enclosing the entire visible screen viewport.
     */
    fun viewportBoundingBox(
        viewport: MapViewport,
        marginFactor: Double = 1.15,
    ): BoundingBox {
        val padW = (viewport.width * (marginFactor - 1.0)) / 2.0
        val padH = (viewport.height * (marginFactor - 1.0)) / 2.0

        val corners =
            listOf(
                unproject(-padW, -padH, viewport),
                unproject(viewport.width + padW, -padH, viewport),
                unproject(viewport.width + padW, viewport.height + padH, viewport),
                unproject(-padW, viewport.height + padH, viewport),
            )

        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0

        for (c in corners) {
            minLat = min(minLat, c.lat)
            maxLat = max(maxLat, c.lat)
            minLon = min(minLon, c.lon)
            maxLon = max(maxLon, c.lon)
        }

        return BoundingBox(
            minLat = minLat.coerceIn(-90.0, 90.0),
            minLon = minLon.coerceIn(-180.0, 180.0),
            maxLat = maxLat.coerceIn(-90.0, 90.0),
            maxLon = maxLon.coerceIn(-180.0, 180.0),
        )
    }
}
