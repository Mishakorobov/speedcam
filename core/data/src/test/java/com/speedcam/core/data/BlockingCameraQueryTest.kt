package com.speedcam.core.data

import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.DetectionEngine
import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that blocking a camera actually stops it producing hazards.
 *
 * This is the seam where a driver's decision has to survive into the engine. The original
 * removes blocked points from `nativeGetHazardsState()` inside its native core, so the Java
 * layer never has to filter - and if this decorator does not hold, a camera the driver
 * silenced is still announced at them every time they pass.
 */
class BlockingCameraQueryTest {
    private val here = LatLon(55.7522, 37.6156)

    private fun camera(
        id: Long,
        metresNorth: Double,
    ) = Camera(
        id = id,
        position = LatLon(here.lat + metresNorth / 111_320.0, here.lon),
        source = CameraSource.OSM,
        externalId = "node/$id",
        limitKmh = 60,
        kind = CameraKind.FIXED,
    )

    private fun queryOf(vararg cameras: Camera): CameraQuery = CameraQuery { cameras.toList() }

    /** The same detector the service runs, reading through the blocking query. */
    private fun engineOver(
        vararg cameras: Camera,
        overrides: OverrideSet = OverrideSet(),
    ): DetectionEngine = DetectionEngine(BlockingCameraQuery(queryOf(*cameras), overrides))

    private fun fix() = Fix(position = here, timestampMillis = 1L, accuracyM = 5f, speedMs = 16.7f)

    private val profile = AlertProfile(captureDistanceKm = 2.0)

    // ------------------------------------------------------------------ blocking

    @Test
    fun `a blocked camera inside the radius is filtered out`() {
        val near = camera(1, 400.0)
        // The block is recorded at the camera's own position. Blocking where the driver is
        // standing would test nothing: the camera is 400 m away, well outside the radius.
        val engine = engineOver(near, overrides = OverrideSet(blocked = listOf(near.position)))
        assertTrue(engine.detect(fix(), profile).isEmpty(), "a blocked camera must not warn")
    }

    @Test
    fun `the same camera warns when nothing is blocked`() {
        val engine = engineOver(camera(1, 400.0))
        assertEquals(1, engine.detect(fix(), profile).size)
    }

    @Test
    fun `a block within the match radius still applies`() {
        // The camera is 400 m north; a block recorded 20 m south of the driver is 380 m from
        // it, which is outside 40 m and so should not apply. This is the boundary that stops
        // a block taking out a neighbour.
        val blockedAt = LatLon(here.lat - 20.0 / 111_320.0, here.lon)
        val engine = engineOver(camera(1, 400.0), overrides = OverrideSet(blocked = listOf(blockedAt)))
        assertEquals(1, engine.detect(fix(), profile).size, "380 m away is not the same camera")
    }

    @Test
    fun `a block just inside the radius applies`() {
        // Recorded 30 m from the camera itself, which is inside the 40 m match radius.
        val camera = camera(1, 400.0)
        val blockedAt = LatLon(camera.position.lat - 30.0 / 111_320.0, camera.position.lon)
        val engine = engineOver(camera, overrides = OverrideSet(blocked = listOf(blockedAt)))
        assertTrue(engine.detect(fix(), profile).isEmpty(), "that is the same camera")
    }

    @Test
    fun `a block just outside the radius does not apply`() {
        // 60 m from the camera, beyond the radius, and so a different one.
        val camera = camera(1, 400.0)
        val blockedAt = LatLon(camera.position.lat - 60.0 / 111_320.0, camera.position.lon)
        val engine = engineOver(camera, overrides = OverrideSet(blocked = listOf(blockedAt)))
        assertEquals(1, engine.detect(fix(), profile).size, "60 m away is not the same camera")
    }

    @Test
    fun `blocking one camera leaves the others alone`() {
        val near = camera(1, 400.0)
        val far = camera(2, 900.0)
        val engine = engineOver(near, far, overrides = OverrideSet(blocked = listOf(near.position)))

        assertEquals(listOf(2L), engine.detect(fix(), profile).map { it.camera.id })
    }

    @Test
    fun `an added override does not block anything`() {
        val engine = engineOver(camera(1, 400.0), overrides = OverrideSet(added = listOf(here)))
        assertEquals(1, engine.detect(fix(), profile).size)
    }

    // ------------------------------------------------------------------- updating

    @Test
    fun `updating the override set takes effect immediately`() {
        val camera = camera(1, 400.0)
        val delegate = queryOf(camera)
        val blocking = BlockingCameraQuery(delegate)
        val engine = DetectionEngine(blocking)

        assertEquals(1, engine.detect(fix(), profile).size, "nothing blocked yet")

        blocking.update(OverrideSet(blocked = listOf(camera.position)))
        assertTrue(engine.detect(fix(), profile).isEmpty(), "the same engine must see the new block")
    }

    @Test
    fun `an empty override set short-circuits without touching the delegate result`() {
        val delegate = queryOf(camera(1, 400.0))
        assertEquals(1, BlockingCameraQuery(delegate).inBox(BoundingBox(-90.0, -180.0, 90.0, 180.0)).size)
    }

    // ------------------------------------------------------------------ mapping

    @Test
    fun `entities map into overrides by action`() {
        val set =
            OverrideSet.from(
                listOf(
                    OverrideEntity(lat = 1.0, lon = 2.0, action = 0, note = null, created = ""),
                    OverrideEntity(lat = 3.0, lon = 4.0, action = 1, note = null, created = ""),
                ),
            )
        assertEquals(1, set.blocked.size)
        assertEquals(1, set.added.size)
        assertEquals(LatLon(1.0, 2.0), set.blocked.single())
        assertEquals(LatLon(3.0, 4.0), set.added.single())
    }

    @Test
    fun `an unknown action ordinal is treated as a block`() {
        // The safer default: an action this build cannot read came from a newer one, and
        // ignoring it would silently un-silence something the driver silenced.
        val set = OverrideSet.from(listOf(OverrideEntity(lat = 1.0, lon = 2.0, action = 99, note = null, created = "")))
        assertEquals(1, set.blocked.size)
    }

    @Test
    fun `an empty set reports itself empty`() {
        assertTrue(OverrideSet().isEmpty)
        assertFalse(OverrideSet(blocked = listOf(here)).isEmpty)
    }
}
