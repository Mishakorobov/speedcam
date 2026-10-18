package com.speedcam.core.detect

import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectionEngineTest {
    /** Engine backed by a fixed candidate set, ignoring the bounding-box query. */
    private fun engineOver(vararg cameras: Camera) = DetectionEngine { cameras.toList() }

    private val wideProfile = AlertProfile(captureDistanceKm = 2.0)

    // ---------------------------------------------------------------- distance

    @Test
    fun `a camera ahead within range produces a hazard`() {
        val c = Fixtures.camera(id = 1, metresNorth = 800.0)
        val hazards = engineOver(c).detect(Fixtures.fix(headingDeg = 0.0), wideProfile)

        assertEquals(1, hazards.size)
        assertEquals(800.0, hazards[0].distanceM, 20.0)
        assertTrue(kotlin.math.abs(hazards[0].relativeBearingDeg) < 0.5)
    }

    @Test
    fun `a camera beyond the capture distance produces nothing`() {
        val c = Fixtures.camera(id = 1, metresNorth = 2_500.0)
        assertTrue(engineOver(c).detect(Fixtures.fix(), wideProfile).isEmpty())
    }

    @Test
    fun `hazards are sorted nearest first`() {
        val far = Fixtures.camera(id = 1, metresNorth = 1_800.0)
        val near = Fixtures.camera(id = 2, metresNorth = 200.0)
        val mid = Fixtures.camera(id = 3, metresNorth = 900.0)

        val hazards = engineOver(far, mid, near).detect(Fixtures.fix(), wideProfile)
        assertEquals(listOf(2L, 3L, 1L), hazards.map { it.camera.id })
    }

    @Test
    fun `the bounding box prefilter does not drop a hazard the exact test accepts`() {
        // A camera at 995 m against a 1000 m capture distance. A box that is even slightly
        // too tight would silently lose this camera, which is the failure mode being guarded.
        val profile = AlertProfile(captureDistanceKm = 1.0)
        val c = Fixtures.camera(id = 1, metresNorth = 995.0)

        val engine = DetectionEngine { box -> if (c.position in box) listOf(c) else emptyList() }
        assertEquals(1, engine.detect(Fixtures.fix(), profile).size)
    }

    // ---------------------------------------------------------------- bearing

    @Test
    fun `a camera due east is 90 degrees right of a northbound vehicle`() {
        val c = Fixtures.camera(id = 1, metresNorth = 0.0, metresEast = 500.0)
        val h = engineOver(c).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).single()
        assertTrue(kotlin.math.abs(h.relativeBearingDeg - 90.0) < 1.0, "got ${h.relativeBearingDeg}")
    }

    @Test
    fun `a camera behind a southbound vehicle reports as behind`() {
        val c = Fixtures.camera(id = 1, metresNorth = 500.0)
        val h = engineOver(c).detect(Fixtures.fix(headingDeg = 180.0), wideProfile).single()
        assertTrue(kotlin.math.abs(kotlin.math.abs(h.relativeBearingDeg) - 180.0) < 1.0)
        assertTrue(h.isBehind)
    }

    // --------------------------------------------------------- facing (rule 1)

    @Test
    fun `a camera facing away from us is suppressed`() {
        // 500 m ahead, device pointing north. We approach from the south heading north,
        // so it photographs traffic travelling away from us.
        val away = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = 0.0)
        assertTrue(engineOver(away).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).isEmpty())
    }

    @Test
    fun `a camera facing towards us warns`() {
        // 500 m ahead, device pointing south, back down the road at the approaching traffic.
        val towards = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = 180.0)
        assertEquals(1, engineOver(towards).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).size)
    }

    @Test
    fun `a two-direction camera warns regardless of approach`() {
        val both = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = 0.0, dirCount = 2)
        assertEquals(1, engineOver(both).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).size)
    }

    @Test
    fun `a camera with no recorded direction warns`() {
        val unknown = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = null)
        assertEquals(1, engineOver(unknown).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).size)
    }

    @Test
    fun `facing tolerance absorbs a few degrees of survey error`() {
        // Surveyed at 170 degrees when due-south is 180: a 10 degree annotation error.
        val off = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = 170.0)
        assertEquals(1, engineOver(off).detect(Fixtures.fix(headingDeg = 0.0), wideProfile).size)
    }

    // ------------------------------------------------------- back-shot (rule 2)

    @Test
    fun `backshotOnly suppresses a camera more than 90 degrees off heading`() {
        // Placed 120 degrees off the nose (250 m behind, 433 m to the right), facing unknown
        // so that this exercises the back-shot rule and not the facing rule.
        val offAxis = Fixtures.camera(id = 1, metresNorth = -250.0, metresEast = 433.0, facingDeg = null)

        val suppressed =
            engineOver(offAxis).detect(
                Fixtures.fix(headingDeg = 0.0),
                wideProfile.copy(backshotOnly = true),
            )
        assertTrue(suppressed.isEmpty(), "off-axis camera must be suppressed under backshotOnly")

        val allowed = engineOver(offAxis).detect(Fixtures.fix(headingDeg = 0.0), wideProfile)
        assertEquals(1, allowed.size, "the same camera warns when backshotOnly is off")
    }

    @Test
    fun `backshotOnly keeps a camera dead ahead`() {
        val ahead = Fixtures.camera(id = 1, metresNorth = 500.0, facingDeg = null)
        val hazards =
            engineOver(ahead).detect(
                Fixtures.fix(headingDeg = 0.0),
                wideProfile.copy(backshotOnly = true),
            )
        assertEquals(1, hazards.size)
    }

    // ------------------------------------------------------------ speed gate

    @Test
    fun `under-speed suppression silences a camera`() {
        val c = Fixtures.camera(id = 1, metresNorth = 400.0, limitKmh = 60)
        val profile = wideProfile.copy(warnSpeedExcessKmh = 20)

        // Travelling at exactly the limit: excess is zero, so nothing is worth warning about.
        assertTrue(engineOver(c).detect(Fixtures.fix(speedKmh = 60.0), profile).isEmpty())
    }

    @Test
    fun `exceeding the limit defeats excess suppression`() {
        val c = Fixtures.camera(id = 1, metresNorth = 400.0, limitKmh = 60)
        val profile = wideProfile.copy(warnSpeedExcessKmh = 20)
        assertEquals(1, engineOver(c).detect(Fixtures.fix(speedKmh = 100.0), profile).size)
    }

    @Test
    fun `a camera with no limit is never speed-suppressed`() {
        val c = Fixtures.camera(id = 1, metresNorth = 400.0, limitKmh = null)
        val profile = wideProfile.copy(warnSpeedLimitKmh = 50, warnSpeedExcessKmh = 20)
        assertEquals(1, engineOver(c).detect(Fixtures.fix(speedKmh = 10.0), profile).size)
    }

    @Test
    fun `excess is computed against the camera limit`() {
        val c = Fixtures.camera(id = 1, metresNorth = 400.0, limitKmh = 60)
        val h = engineOver(c).detect(Fixtures.fix(speedKmh = 90.0), wideProfile).single()
        assertTrue(kotlin.math.abs(h.excessKmh!! - 30.0) < 0.5, "got ${h.excessKmh}")
    }

    // ------------------------------------------------------------- auto radius

    @Test
    fun `auto distance scales with speed`() {
        val profile = AlertProfile(captureDistanceKm = 0.0)
        // 400 m floor + 3 s of travel.
        assertEquals(400.0, profile.effectiveDistanceM(0.0), 0.1)
        assertEquals(425.0, profile.effectiveDistanceM(8.333), 0.1) // 30 km/h
        assertEquals(450.0, profile.effectiveDistanceM(16.667), 0.1) // 60 km/h
        assertEquals(475.0, profile.effectiveDistanceM(25.0), 0.1) // 90 km/h
        assertEquals(525.0, profile.effectiveDistanceM(41.667), 0.1) // 150 km/h
    }

    @Test
    fun `auto distance is capped by the safety guard`() {
        val profile = AlertProfile(captureDistanceKm = 0.0)
        // 400 + 3 * 400 = 1600, past the 1500 m guard.
        assertEquals(AlertProfile.MAX_AUTO_M, profile.effectiveDistanceM(400.0), 0.1)
        assertEquals(AlertProfile.MAX_AUTO_M, profile.effectiveDistanceM(100_000.0), 0.1)
    }

    @Test
    fun `an explicit capture distance overrides auto`() {
        val profile = AlertProfile(captureDistanceKm = 0.75)
        assertEquals(750.0, profile.effectiveDistanceM(0.0), 0.1)
        assertEquals(750.0, profile.effectiveDistanceM(30.0), 0.1)
    }

    @Test
    fun `stationary vehicles get the look-ahead only`() {
        val profile = AlertProfile(captureDistanceKm = 0.0)
        val c = Fixtures.camera(id = 1, metresNorth = 250.0)
        assertEquals(1, engineOver(c).detect(Fixtures.fix(speedKmh = 0.0), profile).size)
    }

    // ------------------------------------------------------------ edge cases

    @Test
    fun `the same position is handled without dividing by zero`() {
        val here = Fixtures.camera(id = 1, position = Fixtures.ORIGIN)
        val h = engineOver(here).detect(Fixtures.fix(), wideProfile).single()
        assertEquals(0.0, h.distanceM, 1e-6)
    }

    @Test
    fun `great-circle distance is correct across the antimeridian`() {
        // Two points 0.0002 degrees apart straddling 180 degrees longitude, about 22 m.
        // Verifies the haversine sin(dLon/2) term is periodic and does not blow up here.
        val a = LatLon(0.0, 179.9999)
        val b = LatLon(0.0, -179.9999)
        val d = Geo.haversineMeters(a, b)
        assertTrue(d in 15.0..30.0, "expected ~22 m but was $d")
    }

    @Test
    fun `an empty candidate set yields no hazards`() {
        assertTrue(engineOver().detect(Fixtures.fix(), wideProfile).isEmpty())
    }

    @Test
    fun `heading normalisation keeps bearings in range`() {
        assertFalse(Fixtures.fix(headingDeg = 370.0).headingDeg > 360.0)
        assertFalse(Fixtures.fix(headingDeg = -90.0).headingDeg < 0.0)
    }
}
