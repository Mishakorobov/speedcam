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
    fun `a noisy jump is rejected at low speed`() {
        // 1 second apart at rest: expansion is 5 m (5 m/s * 1 s), so candidate must be < 10 + 5 = 15 m.
        // A jump to 20 m must be rejected.
        val current = fix(accuracyM = 10f)
        val candidate = fix(accuracyM = 20f, elapsedSeconds = 1)
        assertSame(current, FixArbiter.better(current, candidate))
    }

    @Test
    fun `a fresh fix within expanded uncertainty is accepted as time elapses`() {
        // 1 second apart at rest: candidate at 12 m is within 10 + 5 = 15 m bound.
        val current = fix(accuracyM = 10f)
        val candidate = fix(accuracyM = 12f, elapsedSeconds = 1)
        assertSame(candidate, FixArbiter.better(current, candidate))
    }

    @Test
    fun `uncertainty expansion grows with elapsed time and speed`() {
        // 10 seconds apart at 90 km/h (25 m/s): the vehicle has covered ~250 m.
        // The incumbent's position uncertainty has expanded by 250 m, so even a 30 m fix
        // represents the current position far better than the 10-second-old fix.
        val current = fix(accuracyM = 10f)
        val candidate = fix(accuracyM = 30f, speedKmh = 90.0, elapsedSeconds = 10)
        assertSame(candidate, FixArbiter.better(current, candidate))

        // But an extreme outlier (e.g. 300 m > 10 + 250 = 260 m) is still rejected.
        val wildCandidate = fix(accuracyM = 300f, speedKmh = 90.0, elapsedSeconds = 10)
        assertSame(current, FixArbiter.better(current, wildCandidate))
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
    fun `simultaneous fixes require strictly better accuracy`() {
        // Same timestamp: no elapsed time, so expansion is 0. Candidate must be more accurate.
        val current = fix(accuracyM = 10f, elapsedSeconds = 0)
        val worseCandidate = fix(accuracyM = 12f, elapsedSeconds = 0)
        assertSame(current, FixArbiter.better(current, worseCandidate))

        val betterCandidate = fix(accuracyM = 8f, elapsedSeconds = 0)
        assertSame(betterCandidate, FixArbiter.better(current, betterCandidate))
        assertEquals(5.0, FixArbiter.MIN_SLACK_M, 0.0)
    }
}
