package com.speedcam.tools.import.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverpassMapperTest {
    @Test
    fun `parses a plain numeric maxspeed`() {
        assertEquals(60, OverpassMapper.parseLimit("60"))
        assertEquals(80, OverpassMapper.parseLimit("80 km/h"))
        assertEquals(80, OverpassMapper.parseLimit("80 KM/H"))
        assertEquals(60, OverpassMapper.parseLimit("60 км/ч"))
        assertEquals(60, OverpassMapper.parseLimit("60км/ч"))
        assertEquals(90, OverpassMapper.parseLimit("90 кмч"))
        assertEquals(110, OverpassMapper.parseLimit("110 kmh"))
        assertEquals(70, OverpassMapper.parseLimit("70 kph"))
    }

    @Test
    fun `rejects implausible limits`() {
        assertNull(OverpassMapper.parseLimit("0"))
        assertNull(OverpassMapper.parseLimit("500"))
        assertNull(OverpassMapper.parseLimit("-60"))
    }

    @Test
    fun `leaves a named zone unknown rather than guessing the policy`() {
        // RU:urban means "whatever the urban default currently is". Encoding that default
        // would embed a policy the source deliberately left open.
        assertNull(OverpassMapper.parseLimit("RU:urban"))
        assertNull(OverpassMapper.parseLimit("walk"))
        assertNull(OverpassMapper.parseLimit("none"))
        assertNull(OverpassMapper.parseLimit(null))
    }

    @Test
    fun `parses a numeric direction`() {
        assertEquals(180.0, OverpassMapper.parseDirection("180")!!, 1e-9)
        assertEquals(0.0, OverpassMapper.parseDirection("0")!!, 1e-9)
        assertEquals(359.0, OverpassMapper.parseDirection("359")!!, 1e-9)
    }

    @Test
    fun `rejects an out-of-range numeric direction`() {
        assertNull(OverpassMapper.parseDirection("361"))
        assertNull(OverpassMapper.parseDirection("-1"))
    }

    @Test
    fun `parses cardinal directions in both latin and cyrillic`() {
        assertEquals(0.0, OverpassMapper.parseDirection("N")!!, 1e-9)
        assertEquals(0.0, OverpassMapper.parseDirection("север")!!, 1e-9)
        assertEquals(0.0, OverpassMapper.parseDirection("с")!!, 1e-9)
        assertEquals(22.5, OverpassMapper.parseDirection("NNE")!!, 1e-9)
        assertEquals(45.0, OverpassMapper.parseDirection("NE")!!, 1e-9)
        assertEquals(45.0, OverpassMapper.parseDirection("северо-восток")!!, 1e-9)
        assertEquals(45.0, OverpassMapper.parseDirection("св")!!, 1e-9)
        assertEquals(90.0, OverpassMapper.parseDirection("E")!!, 1e-9)
        assertEquals(90.0, OverpassMapper.parseDirection("в")!!, 1e-9)
        assertEquals(135.0, OverpassMapper.parseDirection("SE")!!, 1e-9)
        assertEquals(135.0, OverpassMapper.parseDirection("юго-восток")!!, 1e-9)
        assertEquals(135.0, OverpassMapper.parseDirection("юв")!!, 1e-9)
        assertEquals(180.0, OverpassMapper.parseDirection("south")!!, 1e-9)
        assertEquals(180.0, OverpassMapper.parseDirection("ю")!!, 1e-9)
        assertEquals(225.0, OverpassMapper.parseDirection("юз")!!, 1e-9)
        assertEquals(270.0, OverpassMapper.parseDirection("запад")!!, 1e-9)
        assertEquals(270.0, OverpassMapper.parseDirection("з")!!, 1e-9)
        assertEquals(315.0, OverpassMapper.parseDirection("NW")!!, 1e-9)
        assertEquals(315.0, OverpassMapper.parseDirection("сз")!!, 1e-9)
        assertEquals(315.0, OverpassMapper.parseDirection("северо-запад")!!, 1e-9)
    }

    @Test
    fun `detects camera kind including dummy housings`() {
        assertEquals(
            com.speedcam.core.detect.CameraKind.DUMMY,
            OverpassMapper.kindFrom(mapOf("dummy" to "yes")),
        )
        assertEquals(
            com.speedcam.core.detect.CameraKind.DUMMY,
            OverpassMapper.kindFrom(mapOf("enforcement" to "dummy")),
        )
        assertEquals(
            com.speedcam.core.detect.CameraKind.AVERAGE_ENTRY,
            OverpassMapper.kindFrom(mapOf("enforcement" to "average_speed")),
        )
        assertEquals(
            com.speedcam.core.detect.CameraKind.RED_LIGHT,
            OverpassMapper.kindFrom(mapOf("enforcement" to "traffic_signals")),
        )
        assertEquals(
            com.speedcam.core.detect.CameraKind.COMPLEX,
            OverpassMapper.kindFrom(mapOf("man_made" to "surveillance")),
        )
        assertEquals(
            com.speedcam.core.detect.CameraKind.FIXED,
            OverpassMapper.kindFrom(mapOf("highway" to "speed_camera")),
        )
    }

    @Test
    fun `an absent direction yields null`() {
        assertNull(OverpassMapper.parseDirection(null))
        assertNull(OverpassMapper.parseDirection("sideways"))
    }
}

class BoundingBoxTest {
    @Test
    fun `tiling produces the expected cell count`() {
        val tiles = BoundingBox(0.0, 0.0, 4.0, 10.0).tiles(BoundingBox(0.0, 0.0, 2.0, 5.0))

        // 4 degrees of latitude / 2 = 2 rows, by 10 degrees of longitude / 5 = 2 columns.
        assertEquals(4, tiles.size)
    }

    @Test
    fun `every tile lies within the requested area`() {
        val area = BoundingBox(41.0, 19.0, 82.0, 179.99)
        val tiles = area.tiles(BoundingBox(0.0, 0.0, 2.0, 5.0))

        for (t in tiles) {
            assertTrue(t.south >= area.south - 1e-9, "tile ${t.describe()} escapes south")
            assertTrue(t.north <= area.north + 1e-9, "tile ${t.describe()} escapes north")
            assertTrue(t.west >= area.west - 1e-9, "tile ${t.describe()} escapes west")
            assertTrue(t.east <= area.east + 1e-9, "tile ${t.describe()} escapes east")
        }
    }

    @Test
    fun `tiling of russia produces a workable number of requests`() {
        // The whole point of tiling: a single query this size times out on public instances,
        // but hundreds of small ones would be inconsiderate against donated infrastructure.
        val tiles = BoundingBox.RUSSIA.tiles(OverpassClient.TILE)

        assertTrue(tiles.size > 1, "expected the area to be split")
        assertTrue(tiles.size <= 200, "expected a manageable request count, got ${tiles.size}")
        assertTrue(tiles.all { it.north - it.south <= 5.0 + 1e-9 })
        assertTrue(tiles.all { it.east - it.west <= 10.0 + 1e-9 })
    }

    @Test
    fun `a partial final tile is produced for ragged edges`() {
        val tiles = BoundingBox(0.0, 0.0, 3.0, 7.0).tiles(BoundingBox(0.0, 0.0, 2.0, 5.0))

        // 3/2 rounds up to 2 rows, 7/5 rounds up to 2 columns, with partial cells.
        assertEquals(4, tiles.size)
        assertTrue(tiles.any { it.north - it.south < 2.0 }, "expected a short final row")
        assertTrue(tiles.any { it.east - it.west < 5.0 }, "expected a short final column")
    }

    @Test
    fun `the russia area covers kaliningrad`() {
        // Kaliningrad is an exclave in the west but lies inside this bounding box, so it
        // needs no separate query. If this ever fails, RUSSIA must be widened.
        val russia = BoundingBox.RUSSIA
        val kaliningrad = BoundingBox(54.3, 19.5, 54.8, 23.0)

        assertTrue(russia.south < kaliningrad.north && russia.north > kaliningrad.south, "latitude")
        assertTrue(russia.west < kaliningrad.west && russia.east > kaliningrad.east, "longitude")
    }

    @Test
    fun `an inverted box is rejected`() {
        var threw = false
        try {
            BoundingBox(10.0, 0.0, 0.0, 10.0)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "south must be below north")
    }
}
