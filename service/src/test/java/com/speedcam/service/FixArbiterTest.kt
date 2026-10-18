package com.speedcam.service

import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixArbiterTest {
    private val here = LatLon(56.8587, 35.9176)

    private fun fix(
        accuracyM: Float,
        speedKmh: Double = 0.0,
        elapsedSeconds: Long = 0,
        providerTag: String? = "gps",
    ) = Fix(
        position = here,
        timestampMillis = 1_700_000_000_000L + elapsedSeconds * 1000,
        accuracyM = accuracyM,
        speedMs = (speedKmh / 3.6).toFloat(),
        headingDeg = 0.0,
        providerTag = providerTag,
    )

    @Test
    fun `a first fix is always adopted`() {
        val candidate = fix(accuracyM = 50f)
        assertSame(candidate, FixArbiter.better(null, candidate))
    }

    @Test
    fun `a more accurate fix replaces a less accurate one`() {
        val current = fix(accuracyM = 80f)
        val candidate = fix(accuracyM = 5f, elapsedSeconds = 1)
        assertSame(candidate, FixArbiter.better(current, candidate))
    }

    @Test
    fun `a barely better fix is rejected at low speed`() {
        // 1 second apart at rest: slack is the 5 m floor, so 12 m must not displace 10 m.
        val current = fix(accuracyM = 10f)
        val candidate = fix(accuracyM = 12f, elapsedSeconds = 1)
        assertSame(current, FixArbiter.better(current, candidate))
    }

    @Test
    fun `slack grows with elapsed time`() {
        // 10 seconds apart at 90 km/h: the vehicle could have covered 250 m, so a
        // marginally better accuracy is not enough to believe the new fix.
        val current = fix(accuracyM = 10f)
        val candidate = fix(accuracyM = 15f, speedKmh = 90.0, elapsedSeconds = 10)
        assertSame(current, FixArbiter.better(current, candidate))
    }

    @Test
    fun `the required accuracy improvement grows with speed`() {
        // Same incumbent, same candidate, same one-second gap. Only the speed differs.
        val incumbent = fix(accuracyM = 100f)
        val candidate = fix(accuracyM = 90f, speedKmh = 0.0, elapsedSeconds = 1)

        // At rest the allowance is the 5 m floor: 90 + 5 = 95 < 100, so the candidate wins.
        assertSame(candidate, FixArbiter.better(incumbent, candidate))

        // At 90 km/h the vehicle covers ~25 m in that second, so the allowance is 25 m:
        // 90 + 25 = 115, which is not an improvement over 100. The incumbent stands.
        val sameCandidateAtSpeed = fix(accuracyM = 90f, speedKmh = 90.0, elapsedSeconds = 1)
        assertSame(incumbent, FixArbiter.better(incumbent, sameCandidateAtSpeed))
    }

    @Test
    fun `a fused fix wins regardless of accuracy`() {
        val current = fix(accuracyM = 5f, providerTag = "gps")
        val fused = fix(accuracyM = 60f, elapsedSeconds = 1, providerTag = Fix.FUSED_TAG)
        assertSame(fused, FixArbiter.better(current, fused))
    }

    @Test
    fun `a raw fix does not displace a fused one`() {
        val fused = fix(accuracyM = 60f, providerTag = Fix.FUSED_TAG)
        val raw = fix(accuracyM = 5f, elapsedSeconds = 1, providerTag = "gps")
        assertSame(fused, FixArbiter.better(fused, raw))
    }

    @Test
    fun `an unusable candidate never displaces a usable fix`() {
        val current = fix(accuracyM = 50f)
        val garbage = fix(accuracyM = 5_000f, elapsedSeconds = 1)
        assertSame(current, FixArbiter.better(current, garbage))
    }

    @Test
    fun `a usable candidate replaces an unusable incumbent`() {
        val garbage = fix(accuracyM = 5_000f)
        val good = fix(accuracyM = 8f, elapsedSeconds = 1)
        assertSame(good, FixArbiter.better(garbage, good))
    }

    @Test
    fun `the usability threshold is enforced`() {
        assertTrue(fix(accuracyM = 0f).isUsable)
        assertTrue(fix(accuracyM = 199f).isUsable)
        assertTrue(!fix(accuracyM = 201f).isUsable)
        assertTrue(!fix(accuracyM = -1f).isUsable)
    }

    @Test
    fun `simultaneous fixes fall back to the minimum slack`() {
        // Same timestamp: no elapsed time, so only the 5 m floor applies.
        val current = fix(accuracyM = 10f, elapsedSeconds = 0)
        val candidate = fix(accuracyM = 12f, elapsedSeconds = 0)
        assertSame(current, FixArbiter.better(current, candidate))
        assertEquals(5.0, FixArbiter.MIN_SLACK_M, 0.0)
    }
}
