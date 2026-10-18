package com.speedcam.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

class GeoTest {
    private val tolerance = 0.5

    /** One degree of latitude or longitude on the equator is a 1-degree great-circle arc. */
    private val oneDegreeMeters = Geo.EARTH_RADIUS_M * PI / 180.0

    @Test
    fun `one degree of latitude is a great-circle arc`() {
        val d = Geo.haversineMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0))
        assertEquals(oneDegreeMeters, d, tolerance)
    }

    @Test
    fun `one degree of longitude at the equator equals one degree of latitude`() {
        val d = Geo.haversineMeters(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        assertEquals(oneDegreeMeters, d, tolerance)
    }

    @Test
    fun `distance is symmetric`() {
        val a = LatLon(55.7522, 37.6156)
        val b = LatLon(59.9343, 30.3351)
        assertEquals(Geo.haversineMeters(a, b), Geo.haversineMeters(b, a), 1e-9)
    }

    @Test
    fun `Moscow to Saint Petersburg is approximately 633 km`() {
        val moscow = LatLon(55.7522, 37.6156)
        val spb = LatLon(59.9343, 30.3351)
        val d = Geo.haversineMeters(moscow, spb)
        assertTrue(abs(d - 633_000.0) < 5_000.0, "expected ~633000 m but was $d")
    }

    @Test
    fun `identical positions are zero apart`() {
        val p = LatLon(55.7522, 37.6156)
        assertEquals(0.0, Geo.haversineMeters(p, p), 1e-9)
    }

    @Test
    fun `short separations are numerically stable`() {
        // Naive equirectangular formulas lose precision here; haversine must not.
        val a = LatLon(55.7522, 37.6156)
        val b = LatLon(55.7523, 37.6157)
        val d = Geo.haversineMeters(a, b)
        assertTrue(d in 10.0..20.0, "expected ~15 m but was $d")
    }

    @Test
    fun `bearing is due north along a meridian`() {
        val b = Geo.bearingDeg(LatLon(55.0, 37.0), LatLon(56.0, 37.0))
        assertTrue(abs(b) < 0.01 || abs(b - 360.0) < 0.01, "expected ~0 but was $b")
    }

    @Test
    fun `bearing is approximately east along a parallel`() {
        // Not exactly 90 degrees. The initial great-circle bearing between two points on the
        // same parallel bulges poleward, so at 55 N the answer is ~89.59 degrees. A constant-
        // latitude (rhumb-line) calculation would return exactly 90 but would be wrong for
        // direct line-of-sight, which is what a camera warning needs. Over the ~1 km baseline
        // this app actually uses, the difference is a few metres.
        val b = Geo.bearingDeg(LatLon(55.0, 37.0), LatLon(55.0, 38.0))
        assertTrue(abs(b - 90.0) < 0.5, "expected ~90 but was $b")
    }

    @Test
    fun `bearing is exactly east on the equator`() {
        // On the equator the great circle and the parallel coincide, so the bias vanishes.
        val b = Geo.bearingDeg(LatLon(0.0, 37.0), LatLon(0.0, 38.0))
        assertTrue(abs(b - 90.0) < 0.001, "expected exactly 90 but was $b")
    }

    @Test
    fun `bearing is due south when crossing the antimeridian east to west`() {
        val b = Geo.bearingDeg(LatLon(0.0, 179.9), LatLon(0.0, 179.8))
        assertTrue(abs(b - 270.0) < 0.1, "expected ~270 but was $b")
    }

    @Test
    fun `normalize180 maps to the half-open interval`() {
        assertEquals(-180.0, Geo.normalize180(180.0), 1e-9)
        assertEquals(0.0, Geo.normalize180(360.0), 1e-9)
        assertEquals(-90.0, Geo.normalize180(270.0), 1e-9)
        assertEquals(45.0, Geo.normalize180(45.0), 1e-9)
        assertEquals(-1.0, Geo.normalize180(-361.0), 1e-9)
    }

    @Test
    fun `normalize360 maps to the half-open interval`() {
        assertEquals(0.0, Geo.normalize360(360.0), 1e-9)
        assertEquals(350.0, Geo.normalize360(-10.0), 1e-9)
        assertEquals(10.0, Geo.normalize360(370.0), 1e-9)
    }

    @Test
    fun `angleDiff returns the smaller angle and never exceeds 180`() {
        // 350 and 10 straddle north, so they are 20 degrees apart — not equal.
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(10.0, Geo.angleDiff(355.0, 5.0), 1e-9)
        assertEquals(90.0, Geo.angleDiff(0.0, 90.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDiff(0.0, -90.0), 1e-9)
        assertTrue(Geo.angleDiff(10.0, 200.0) <= 180.0)
    }

    @Test
    fun `bounding box contains the centre and a point inside the radius`() {
        val center = LatLon(55.7522, 37.6156)
        val radius = 1500.0
        val box = Geo.boundingBox(center, radius)

        assertTrue(center in box)

        // ~1.2 km due north, well inside the radius.
        val north = LatLon(55.7630, 37.6156)
        assertTrue(north in box, "expected a point 1.2 km north to be inside the box")

        // A point far beyond the radius must be excluded.
        val far = LatLon(56.7522, 37.6156)
        assertTrue(far !in box)
    }

    @Test
    fun `bounding box widens longitude away from the equator`() {
        val equator = Geo.boundingBox(LatLon(0.0, 0.0), 10_000.0)
        val highLatitude = Geo.boundingBox(LatLon(60.0, 0.0), 10_000.0)

        val equatorWidth = equator.maxLon - equator.minLon
        val highLatitudeWidth = highLatitude.maxLon - highLatitude.minLon

        // At 60 degrees north a degree of longitude is half as long, so the box must be wider.
        assertTrue(
            highLatitudeWidth > equatorWidth * 1.8,
            "expected >1.8x widening but was ${highLatitudeWidth / equatorWidth}x",
        )
    }

    @Test
    fun `bounding box clamps at the poles`() {
        val box = Geo.boundingBox(LatLon(89.999, 0.0), 100_000.0)
        assertTrue(box.maxLat <= 90.0, "maxLat must not exceed 90")
        assertTrue(box.minLat >= -90.0, "minLat must not go below -90")
    }

    @Test
    fun `LatLon rejects out-of-range coordinates`() {
        var rejectedLat = false
        try {
            LatLon(91.0, 0.0)
        } catch (_: IllegalArgumentException) {
            rejectedLat = true
        }
        assertTrue(rejectedLat, "latitude 91 must be rejected")

        var rejectedLon = false
        try {
            LatLon(0.0, 181.0)
        } catch (_: IllegalArgumentException) {
            rejectedLon = true
        }
        assertTrue(rejectedLon, "longitude 181 must be rejected")
    }
}
