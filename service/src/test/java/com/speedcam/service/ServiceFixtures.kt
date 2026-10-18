package com.speedcam.service

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos

/**
 * Minimal fixtures for the service tests.
 *
 * `core:detect` keeps a richer set for itself, but test sources are not published to
 * consumers, so the handful of builders the pipeline and watchdog tests need are repeated
 * here rather than the module being reconfigured to publish them.
 */
object ServiceFixtures {
    /** A straight, fast road outside Tver, as used by the detection tests. */
    val ORIGIN = LatLon(56.8587, 35.9176)

    fun fix(
        position: LatLon = ORIGIN,
        headingDeg: Double = 0.0,
        speedKmh: Double = 0.0,
        timestampMillis: Long = 1_700_000_000_000L,
        accuracyM: Float = 5f,
    ) = Fix(
        position = position,
        timestampMillis = timestampMillis,
        accuracyM = accuracyM,
        speedMs = (speedKmh / 3.6).toFloat(),
        headingDeg = headingDeg,
    )

    fun camera(
        id: Long,
        metresNorth: Double = 0.0,
        metresEast: Double = 0.0,
        limitKmh: Int? = 60,
        facingDeg: Double? = null,
    ) = Camera(
        id = id,
        position = offset(ORIGIN, metresNorth, metresEast),
        source = CameraSource.OSM,
        externalId = "node/$id",
        limitKmh = limitKmh,
        kind = CameraKind.FIXED,
        facingDeg = facingDeg,
    )

    /** Offset from [origin] by roughly [metresNorth] north and [metresEast] east. */
    fun offset(
        origin: LatLon,
        metresNorth: Double,
        metresEast: Double,
    ): LatLon {
        val dLat = metresNorth / 111_320.0
        val cosLat = cos(origin.lat * Math.PI / 180.0)
        val dLon = if (abs(cosLat) < 1e-9) 0.0 else metresEast / (111_320.0 * cosLat)
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }
}
