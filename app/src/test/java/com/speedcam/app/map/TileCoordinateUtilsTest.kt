package com.speedcam.app.map

import com.speedcam.core.geo.LatLon
import com.speedcam.core.geo.MapViewport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileCoordinateUtilsTest {
    private val moscow = LatLon(55.7558, 37.6173)

    @Test
    fun `zoom 0 always maps to single root tile 0 0`() {
        val xMoscow = TileCoordinateUtils.lonToTileX(moscow.lon, 0)
        val yMoscow = TileCoordinateUtils.latToTileY(moscow.lat, 0)
        assertEquals(0, xMoscow)
        assertEquals(0, yMoscow)

        val xSydney = TileCoordinateUtils.lonToTileX(151.2093, 0)
        val ySydney = TileCoordinateUtils.latToTileY(-33.8688, 0)
        assertEquals(0, xSydney)
        assertEquals(0, ySydney)
    }

    @Test
    fun `zoom 1 divides the world into 4 quadrants`() {
        // North-West (lon < 0, lat > 0) -> (0, 0)
        assertEquals(0, TileCoordinateUtils.lonToTileX(-50.0, 1))
        assertEquals(0, TileCoordinateUtils.latToTileY(50.0, 1))

        // North-East (lon > 0, lat > 0) -> (1, 0)
        assertEquals(1, TileCoordinateUtils.lonToTileX(50.0, 1))
        assertEquals(0, TileCoordinateUtils.latToTileY(50.0, 1))

        // South-West (lon < 0, lat < 0) -> (0, 1)
        assertEquals(0, TileCoordinateUtils.lonToTileX(-50.0, 1))
        assertEquals(1, TileCoordinateUtils.latToTileY(-50.0, 1))

        // South-East (lon > 0, lat < 0) -> (1, 1)
        assertEquals(1, TileCoordinateUtils.lonToTileX(50.0, 1))
        assertEquals(1, TileCoordinateUtils.latToTileY(-50.0, 1))
    }

    @Test
    fun `tms row conversion is symmetrical and invertible`() {
        for (zoom in 1..16) {
            val maxCoord = (1 shl zoom) - 1
            // Top row in OSM (y = 0) corresponds to bottom row in TMS (maxCoord)
            assertEquals(maxCoord, TileCoordinateUtils.toTmsRow(0, zoom))
            assertEquals(0, TileCoordinateUtils.fromTmsRow(maxCoord, zoom))

            // Bottom row in OSM (maxCoord) corresponds to top row in TMS (0)
            assertEquals(0, TileCoordinateUtils.toTmsRow(maxCoord, zoom))
            assertEquals(maxCoord, TileCoordinateUtils.fromTmsRow(0, zoom))

            // Check arbitrary mid coordinates
            val testY = maxCoord / 3
            val tms = TileCoordinateUtils.toTmsRow(testY, zoom)
            val restored = TileCoordinateUtils.fromTmsRow(tms, zoom)
            assertEquals(testY, restored)
        }
    }

    @Test
    fun `parent tile coordinates and quadrants are computed accurately`() {
        val root = TileCoord(0, 0, 0)
        assertNull(TileCoordinateUtils.getParentTile(root))

        // Quadrant (0, 0) - top-left
        val tl = TileCoord(zoom = 15, x = 10, y = 20)
        val tlParent = TileCoordinateUtils.getParentTile(tl)
        assertNotNull(tlParent)
        assertEquals(TileCoord(14, 5, 10), tlParent?.first)
        assertEquals(TileQuadrant(0, 0), tlParent?.second)

        // Quadrant (1, 0) - top-right
        val tr = TileCoord(zoom = 15, x = 11, y = 20)
        val trParent = TileCoordinateUtils.getParentTile(tr)
        assertNotNull(trParent)
        assertEquals(TileCoord(14, 5, 10), trParent?.first)
        assertEquals(TileQuadrant(1, 0), trParent?.second)

        // Quadrant (0, 1) - bottom-left
        val bl = TileCoord(zoom = 15, x = 10, y = 21)
        val blParent = TileCoordinateUtils.getParentTile(bl)
        assertNotNull(blParent)
        assertEquals(TileCoord(14, 5, 10), blParent?.first)
        assertEquals(TileQuadrant(0, 1), blParent?.second)

        // Quadrant (1, 1) - bottom-right
        val br = TileCoord(zoom = 15, x = 11, y = 21)
        val brParent = TileCoordinateUtils.getParentTile(br)
        assertNotNull(brParent)
        assertEquals(TileCoord(14, 5, 10), brParent?.first)
        assertEquals(TileQuadrant(1, 1), brParent?.second)
    }

    @Test
    fun `visible tile range contains the viewport center point`() {
        val viewport =
            MapViewport(
                center = moscow,
                zoom = 15.0,
                bearingDeg = 45.0,
                width = 1080.0,
                height = 1920.0,
            )

        val range = TileCoordinateUtils.getVisibleTileRange(viewport, marginFactor = 1.35)
        assertEquals(15, range.zoom)
        assertTrue(range.minX <= range.maxX)
        assertTrue(range.minY <= range.maxY)
        assertTrue(range.count > 0)

        val centerTileX = TileCoordinateUtils.lonToTileX(moscow.lon, 15)
        val centerTileY = TileCoordinateUtils.latToTileY(moscow.lat, 15)

        assertTrue(centerTileX in range.minX..range.maxX, "Center X $centerTileX must be within $range")
        assertTrue(centerTileY in range.minY..range.maxY, "Center Y $centerTileY must be within $range")
    }

    @Test
    fun `tile coordinate inverse conversion preserves proximity`() {
        val zoom = 12
        val tx = TileCoordinateUtils.lonToTileX(moscow.lon, zoom)
        val ty = TileCoordinateUtils.latToTileY(moscow.lat, zoom)

        val recoveredLon = TileCoordinateUtils.tileXToLongitude(tx, zoom)
        val recoveredLat = TileCoordinateUtils.tileYToLatitude(ty, zoom)

        // Coordinates should be within the same tile span (~0.08 degrees at zoom 12)
        assertTrue(kotlin.math.abs(recoveredLon - moscow.lon) < 0.1)
        assertTrue(kotlin.math.abs(recoveredLat - moscow.lat) < 0.1)
    }
}
