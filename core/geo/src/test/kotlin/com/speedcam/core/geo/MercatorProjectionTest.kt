package com.speedcam.core.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MercatorProjectionTest {
    private val moscow = LatLon(55.7558, 37.6173)
    private val spb = LatLon(59.9343, 30.3351)

    @Test
    fun `equator and prime meridian map to center 0_5`() {
        val x = MercatorProjection.longitudeToX(0.0)
        val y = MercatorProjection.latitudeToY(0.0)
        assertEquals(0.5, x, 1e-6)
        assertEquals(0.5, y, 1e-6)

        val lon = MercatorProjection.xToLongitude(0.5)
        val lat = MercatorProjection.yToLatitude(0.5)
        assertEquals(0.0, lon, 1e-6)
        assertEquals(0.0, lat, 1e-6)
    }

    @Test
    fun `round trip conversion preserves coordinates`() {
        val coords = listOf(moscow, spb, LatLon(0.0, 0.0), LatLon(-33.8688, 151.2093), LatLon(70.0, -170.0))
        for (c in coords) {
            val x = MercatorProjection.longitudeToX(c.lon)
            val y = MercatorProjection.latitudeToY(c.lat)
            val recoveredLon = MercatorProjection.xToLongitude(x)
            val recoveredLat = MercatorProjection.yToLatitude(y)
            assertEquals(c.lon, recoveredLon, 1e-5, "longitude mismatch for $c")
            assertEquals(c.lat, recoveredLat, 1e-5, "latitude mismatch for $c")
        }
    }

    @Test
    fun `project and unproject round trip without rotation`() {
        val viewport = MapViewport(center = moscow, zoom = 15.0, bearingDeg = 0.0, width = 1080.0, height = 1920.0)

        val (sx, sy) = MercatorProjection.project(spb, viewport)
        val recovered = MercatorProjection.unproject(sx, sy, viewport)

        assertEquals(spb.lat, recovered.lat, 1e-4)
        assertEquals(spb.lon, recovered.lon, 1e-4)
    }

    @Test
    fun `project and unproject round trip with 45 degree rotation`() {
        val viewport = MapViewport(center = moscow, zoom = 14.0, bearingDeg = 45.0, width = 800.0, height = 600.0)

        val (sx, sy) = MercatorProjection.project(spb, viewport)
        val recovered = MercatorProjection.unproject(sx, sy, viewport)

        assertEquals(spb.lat, recovered.lat, 1e-4)
        assertEquals(spb.lon, recovered.lon, 1e-4)
    }

    @Test
    fun `center projects exactly to center of viewport`() {
        val viewport = MapViewport(center = moscow, zoom = 16.0, bearingDeg = 90.0, width = 1000.0, height = 800.0)
        val (sx, sy) = MercatorProjection.project(moscow, viewport)
        assertEquals(500.0, sx, 1e-6)
        assertEquals(400.0, sy, 1e-6)
    }

    @Test
    fun `meters per pixel is positive and decreases with zoom`() {
        val mpp10 = MercatorProjection.metersPerPixel(moscow.lat, 10.0)
        val mpp15 = MercatorProjection.metersPerPixel(moscow.lat, 15.0)
        val mpp16 = MercatorProjection.metersPerPixel(moscow.lat, 16.0)

        assertTrue(mpp10 > mpp15)
        assertTrue(mpp15 > mpp16)
        assertEquals(mpp15 / 2.0, mpp16, 0.01)
    }

    @Test
    fun `viewport bounding box contains center`() {
        val viewport = MapViewport(center = moscow, zoom = 15.0, bearingDeg = 30.0, width = 1000.0, height = 1000.0)
        val box = MercatorProjection.viewportBoundingBox(viewport)
        assertTrue(box.contains(moscow))
        assertTrue(box.minLat < moscow.lat)
        assertTrue(box.maxLat > moscow.lat)
        assertTrue(box.minLon < moscow.lon)
        assertTrue(box.maxLon > moscow.lon)
    }
}
