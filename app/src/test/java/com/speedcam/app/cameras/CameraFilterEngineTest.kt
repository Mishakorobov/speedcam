package com.speedcam.app.cameras

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the list screen's behaviour without Compose, Room or a device.
 *
 * The filter is the only logic the camera list has, and getting it wrong is quiet: a filter
 * that silently returns nothing looks exactly like an empty database.
 */
class CameraFilterEngineTest {
    private val origin = LatLon(55.7522, 37.6156)

    private fun camera(
        id: Long,
        lat: Double = 55.7522,
        lon: Double = 37.6156,
        limitKmh: Int? = 60,
        kind: CameraKind = CameraKind.FIXED,
        source: CameraSource = CameraSource.OSM,
        operator: String? = null,
    ) = Camera(
        id = id,
        position = LatLon(lat, lon),
        source = source,
        externalId = "node/$id",
        limitKmh = limitKmh,
        kind = kind,
        operator = operator,
    )

    /**
     * Distinct positions, deliberately. Five cameras stacked on one point would make the
     * blocking test meaningless: blocking that point marks all of them, and the ordering
     * assertion would be testing coincidental equality rather than the block.
     */
    private val sample =
        listOf(
            camera(1, lat = 55.7522, lon = 37.6156, limitKmh = 60),
            camera(2, lat = 55.7622, lon = 37.6256, limitKmh = 90, kind = CameraKind.RED_LIGHT),
            camera(3, lat = 55.7722, lon = 37.6356, limitKmh = null),
            camera(
                4,
                lat = 55.7822,
                lon = 37.6456,
                limitKmh = 20,
                kind = CameraKind.MOBILE,
                source = CameraSource.SPEEDCAM,
            ),
            camera(5, lat = 55.7922, lon = 37.6556, limitKmh = 0),
        )

    @Test
    fun `an empty filter returns everything`() {
        val rows = CameraFilterEngine.apply(sample, CameraFilter())
        assertEquals(5, rows.size)
    }

    @Test
    fun `a filter by kind keeps only that kind`() {
        val rows = CameraFilterEngine.apply(sample, CameraFilter(kinds = setOf(CameraKind.RED_LIGHT)))
        assertEquals(listOf(2L), rows.map { it.id })
    }

    @Test
    fun `a filter by limit range keeps only what is inside it`() {
        val rows = CameraFilterEngine.apply(sample, CameraFilter(minLimitKmh = 50, maxLimitKmh = 80))
        assertEquals(listOf(1L), rows.map { it.id }, "only the 60 km/h camera is in 50..80")
    }

    @Test
    fun `cameras without a recorded limit are excluded from a limit range`() {
        val rows = CameraFilterEngine.apply(sample, CameraFilter(minLimitKmh = 10))
        assertFalse(rows.any { it.limitKmh == null }, "an unknown limit cannot satisfy a minimum")
        assertFalse(rows.any { it.id == 5L }, "a limit of zero is not a limit")
    }

    @Test
    fun `requireLimit and onlyUnknownLimit are mutually exclusive in practice`() {
        val hasLimit = CameraFilterEngine.apply(sample, CameraFilter(requireLimit = true))
        val noLimit = CameraFilterEngine.apply(sample, CameraFilter(onlyUnknownLimit = true))

        assertEquals(setOf(1L, 2L, 4L), hasLimit.map { it.id }.toSet())
        assertEquals(setOf(3L, 5L), noLimit.map { it.id }.toSet())
        assertTrue(hasLimit.none { it.id in noLimit.map { r -> r.id } })
    }

    @Test
    fun `the search matches kind, source, operator and id`() {
        assertEquals(
            setOf(2L),
            CameraFilterEngine.apply(sample, CameraFilter(query = "red")).map { it.id }.toSet(),
        )
        assertEquals(
            setOf(4L),
            CameraFilterEngine.apply(sample, CameraFilter(query = "speedcam")).map { it.id }.toSet(),
        )
        assertEquals(
            setOf(3L),
            CameraFilterEngine.apply(sample, CameraFilter(query = "node/3")).map { it.id }.toSet(),
        )
    }

    @Test
    fun `the search is case insensitive and tolerates whitespace`() {
        assertEquals(listOf(2L), CameraFilterEngine.apply(sample, CameraFilter(query = "  RED ")).map { it.id })
    }

    @Test
    fun `a search that matches nothing returns nothing`() {
        assertTrue(CameraFilterEngine.apply(sample, CameraFilter(query = "zzzz")).isEmpty())
    }

    @Test
    fun `filters combine rather than replace one another`() {
        val rows =
            CameraFilterEngine.apply(
                sample,
                CameraFilter(kinds = setOf(CameraKind.RED_LIGHT), minLimitKmh = 80),
            )
        assertEquals(listOf(2L), rows.map { it.id })
    }

    @Test
    fun `rows are ordered by limit so the ones being scanned for group together`() {
        val rows = CameraFilterEngine.apply(sample, CameraFilter())
        val limits = rows.map { it.limitKmh ?: Int.MAX_VALUE }
        assertEquals(limits.sorted(), limits, "unknown limits sort last")
    }

    @Test
    fun `blocked cameras are marked and sorted to the top so they can be reached`() {
        val blockedAt = sample[0].position
        val rows =
            CameraFilterEngine.apply(
                sample,
                CameraFilter(),
                blocked = listOf(blockedAt.lat to blockedAt.lon),
            )

        assertTrue(rows.first().blocked, "the blocked camera leads the list")
        assertEquals(1L, rows.first().id)
    }

    @Test
    fun `a block does not match a camera outside the radius`() {
        // Roughly 1.1 km away in latitude, well beyond the 40 m match radius.
        val rows =
            CameraFilterEngine.apply(
                sample,
                CameraFilter(),
                blocked = listOf(origin.lat to origin.lon + 0.01),
            )
        assertFalse(rows.any { it.blocked }, "a block must not take out its neighbour")
    }

    @Test
    fun `an absent limit is shown as unknown and never as zero`() {
        val row = CameraFilterEngine.apply(sample, CameraFilter()).first { it.limitKmh == null }
        assertEquals("—", row.limitLabel, "missing data is not a measurement of zero")
    }

    @Test
    fun `excludeBlocked hides blocked cameras from the list`() {
        val blockedAt = sample[0].position
        val rows =
            CameraFilterEngine.apply(
                sample,
                CameraFilter(excludeBlocked = true),
                blocked = listOf(blockedAt.lat to blockedAt.lon),
            )

        assertFalse(rows.any { it.blocked }, "blocked cameras are excluded when requested")
        assertEquals(4, rows.size)
        assertEquals(setOf(2L, 3L, 4L, 5L), rows.map { it.id }.toSet())
    }

    @Test
    fun `a filter reports whether it is narrowing anything`() {
        assertFalse(CameraFilter().isActive)
        assertTrue(CameraFilter(query = "x").isActive)
        assertTrue(CameraFilter(kinds = setOf(CameraKind.MOBILE)).isActive)
        assertTrue(CameraFilter(minLimitKmh = 10).isActive)
        assertTrue(CameraFilter(excludeBlocked = true).isActive)
    }
}
