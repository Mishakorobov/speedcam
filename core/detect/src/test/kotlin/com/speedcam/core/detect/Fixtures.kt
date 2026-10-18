package com.speedcam.core.detect

import com.speedcam.core.geo.LatLon

/** Builds fixes and cameras around a reference point for deterministic tests. */
object Fixtures {
    /** Somewhere on the M10 highway outside Tver — a straight, fast, well-mapped road. */
    val ORIGIN = LatLon(56.8587, 35.9176)

    fun fix(
        position: LatLon = ORIGIN,
        headingDeg: Double = 0.0,
        speedKmh: Double = 0.0,
        timestampMillis: Long = 1_700_000_000_000L,
    ) = Fix(
        position = position,
        timestampMillis = timestampMillis,
        accuracyM = 5f,
        speedMs = (speedKmh / 3.6).toFloat(),
        headingDeg = Geo180.normalize(headingDeg),
    )

    /** Offset by [metresNorth] / [metresEast] from [origin], approximately. */
    fun offset(
        origin: LatLon,
        metresNorth: Double,
        metresEast: Double,
    ): LatLon {
        val dLat = metresNorth / 111_320.0
        val cosLat = kotlin.math.cos(origin.lat * Math.PI / 180.0)
        val dLon = if (kotlin.math.abs(cosLat) < 1e-9) 0.0 else metresEast / (111_320.0 * cosLat)
        return LatLon(origin.lat + dLat, origin.lon + dLon)
    }

    fun camera(
        id: Long,
        metresNorth: Double = 0.0,
        metresEast: Double = 0.0,
        limitKmh: Int? = 60,
        facingDeg: Double? = null,
        dirCount: Int = 1,
        kind: CameraKind = CameraKind.FIXED,
        position: LatLon = offset(ORIGIN, metresNorth, metresEast),
    ) = Camera(
        id = id,
        position = position,
        source = CameraSource.OSM,
        externalId = "node/$id",
        limitKmh = limitKmh,
        kind = kind,
        facingDeg = facingDeg,
        dirCount = dirCount,
    )

    private object Geo180 {
        fun normalize(x: Double): Double {
            val v = x % 360.0
            return if (v < 0) v + 360.0 else v
        }
    }
}
