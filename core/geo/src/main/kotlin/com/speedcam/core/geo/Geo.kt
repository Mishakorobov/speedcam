package com.speedcam.core.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS-84 geodetic position. */
data class LatLon(
    val lat: Double,
    val lon: Double,
) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
    }
}

/**
 * Geodesic primitives used by the detection engine.
 *
 * Everything here is pure: no Android types, no clock, no I/O. That is deliberate —
 * the original application's detection core lived in a 9.5 MB native library and had no
 * test coverage whatsoever. Confining it to pure Kotlin makes it fully unit-testable.
 */
object Geo {
    /** Mean Earth radius in metres (IUGG). */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Great-circle distance between two positions, in metres.
     *
     * Uses the haversine formula, which is numerically stable for the small separations
     * (metres to low kilometres) that dominate the warning radius.
     */
    fun haversineMeters(
        a: LatLon,
        b: LatLon,
    ): Double {
        val dLat = (b.lat - a.lat) * DEG_TO_RAD
        val dLon = (b.lon - a.lon) * DEG_TO_RAD
        val lat1 = a.lat * DEG_TO_RAD
        val lat2 = b.lat * DEG_TO_RAD

        val h =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /**
     * Initial bearing from [a] to [b], in degrees clockwise from true north, normalised to [0, 360).
     */
    fun bearingDeg(
        a: LatLon,
        b: LatLon,
    ): Double {
        val lat1 = a.lat * DEG_TO_RAD
        val lat2 = b.lat * DEG_TO_RAD
        val dLon = (b.lon - a.lon) * DEG_TO_RAD

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalize360(atan2(y, x) * RAD_TO_DEG)
    }

    /** Normalises an angle in degrees to the half-open interval [-180, 180). */
    fun normalize180(x: Double): Double {
        var v = (x + 180.0) % 360.0
        if (v < 0) v += 360.0
        return v - 180.0
    }

    /** Normalises an angle in degrees to the half-open interval [0, 360). */
    fun normalize360(x: Double): Double {
        val v = x % 360.0
        return if (v < 0) v + 360.0 else v
    }

    /** Smallest absolute angle between two headings, in degrees, within [0, 180]. */
    fun angleDiff(
        a: Double,
        b: Double,
    ): Double = abs(normalize180(a - b))

    /**
     * Axis-aligned bounding box that fully contains [center] within [radiusMeters].
     *
     * Latitude degrees are near-constant in length, longitude degrees shrink with latitude.
     * The latitude term is widened by a small safety factor so that the box never clips a
     * candidate that the subsequent haversine test would have accepted.
     */
    fun boundingBox(
        center: LatLon,
        radiusMeters: Double,
    ): BoundingBox {
        val latDelta = (radiusMeters / EARTH_RADIUS_M) * RAD_TO_DEG * 1.001
        val cosLat = kotlin.math.cos(center.lat * DEG_TO_RAD)
        val lonDelta = if (abs(cosLat) < 1e-9) 180.0 else latDelta / abs(cosLat)

        val minLat = maxOf(-90.0, center.lat - latDelta)
        val maxLat = minOf(90.0, center.lat + latDelta)

        val minLon: Double
        val maxLon: Double
        if (lonDelta >= 180.0) {
            minLon = -180.0
            maxLon = 180.0
        } else {
            val rawMin = center.lon - lonDelta
            val rawMax = center.lon + lonDelta
            minLon = if (rawMin < -180.0) rawMin + 360.0 else rawMin
            maxLon = if (rawMax > 180.0) rawMax - 360.0 else rawMax
        }

        return BoundingBox(minLat, minLon, maxLat, maxLon)
    }
}

/**
 * An axis-aligned latitude/longitude window.
 *
 * When [minLon] > [maxLon], the window straddles the 180° antimeridian (e.g. Chukotka).
 */
data class BoundingBox(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    /** True when this box crosses the 180° antimeridian. */
    val crossesAntimeridian: Boolean
        get() = minLon > maxLon

    operator fun contains(p: LatLon): Boolean {
        if (p.lat !in minLat..maxLat) return false
        return if (crossesAntimeridian) {
            p.lon >= minLon || p.lon <= maxLon
        } else {
            p.lon in minLon..maxLon
        }
    }
}
