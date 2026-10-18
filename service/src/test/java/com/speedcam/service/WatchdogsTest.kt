package com.speedcam.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the two watchdogs from Plan.md §8.1.
 *
 * Both take their clock as a parameter, so the exact 30-second and 100-metre boundaries are
 * checked directly rather than approximated by waiting for them.
 */
class WatchdogsTest {
    private val origin = ServiceFixtures.ORIGIN

    // --------------------------------------------------------------- GPS loss

    @Test
    fun `a fresh signal is healthy`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.start(nowMs = 0)
        assertEquals(GpsStatus.HEALTHY, watchdog.evaluate(1_000))
    }

    @Test
    fun `the boundary is strictly greater than the timeout`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.onFix(0)

        assertEquals(GpsStatus.HEALTHY, watchdog.evaluate(30_000), "exactly at the timeout is not lost")
        assertEquals(GpsStatus.LOST, watchdog.evaluate(30_001))
    }

    @Test
    fun `loss is announced once and then stays quiet`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.onFix(0)

        assertEquals(GpsStatus.LOST, watchdog.evaluate(31_000))
        assertEquals(GpsStatus.LOST, watchdog.evaluate(32_000), "not a new edge")
        assertEquals(GpsStatus.LOST, watchdog.evaluate(60_000))
    }

    @Test
    fun `recovery is announced exactly once`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.onFix(0)
        assertEquals(GpsStatus.LOST, watchdog.evaluate(31_000))

        watchdog.onFix(40_000)
        assertEquals(GpsStatus.RECOVERED, watchdog.evaluate(41_000))
        assertEquals(GpsStatus.HEALTHY, watchdog.evaluate(42_000), "not a new edge")
        assertEquals(GpsStatus.HEALTHY, watchdog.evaluate(50_000))
    }

    @Test
    fun `a second loss is announced again`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.onFix(0)
        assertEquals(GpsStatus.LOST, watchdog.evaluate(31_000))
        watchdog.onFix(40_000)
        assertEquals(GpsStatus.RECOVERED, watchdog.evaluate(41_000))

        watchdog.onFix(50_000)
        assertEquals(GpsStatus.LOST, watchdog.evaluate(81_000), "a fresh loss must speak up again")
    }

    @Test
    fun `a provider that never delivers is reported after the timeout`() {
        val watchdog = GpsLostWatchdog(timeoutMs = 30_000)
        watchdog.start(nowMs = 1_000)

        assertEquals(GpsStatus.HEALTHY, watchdog.evaluate(31_000))
        assertEquals(GpsStatus.LOST, watchdog.evaluate(31_001))
    }

    @Test
    fun `the default timeout is the thirty seconds the plan specifies`() {
        assertEquals(30_000L, GpsLostWatchdog.DEFAULT_TIMEOUT_MS)
    }

    // ------------------------------------------------------- background abuse

    @Test
    fun `moving in the foreground is never suspicious`() {
        val watchdog = BackgroundAbuseWatchdog()
        assertFalse(watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 5_000.0, 0.0)))
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `moving while backgrounded past the threshold trips the watchdog`() {
        val watchdog = BackgroundAbuseWatchdog(thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)

        assertFalse(
            watchdog.onMoved(60_000, ServiceFixtures.offset(origin, 50.0, 0.0)),
            "50 m is under the threshold",
        )
        assertTrue(
            watchdog.onMoved(120_000, ServiceFixtures.offset(origin, 400.0, 0.0)),
            "400 m while backgrounded must trip it",
        )
    }

    @Test
    fun `the threshold is strict`() {
        val watchdog = BackgroundAbuseWatchdog(thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)
        assertFalse(
            watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 99.0, 0.0)),
            "just under the threshold is not enough",
        )
    }

    @Test
    fun `movement after the window has expired disarms the watchdog`() {
        val watchdog = BackgroundAbuseWatchdog(windowMs = 900_000, thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)

        assertTrue(watchdog.isArmed)
        assertFalse(
            watchdog.onMoved(900_001, ServiceFixtures.offset(origin, 5_000.0, 0.0)),
            "beyond the window the rule no longer applies",
        )
        assertFalse(watchdog.isArmed, "and the watchdog disarms itself")
    }

    @Test
    fun `coming back to the foreground clears the suspicion`() {
        val watchdog = BackgroundAbuseWatchdog()
        watchdog.onBackgrounded(0, origin)
        watchdog.onForegrounded()

        assertFalse(watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 5_000.0, 0.0)))
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `movement without a known starting position cannot trip it`() {
        // No fix means no origin, and inventing one would produce nonsense distances.
        val watchdog = BackgroundAbuseWatchdog()
        watchdog.onBackgrounded(0, position = null)

        assertFalse(
            watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 5_000.0, 0.0)),
            "without an origin position there is no displacement to measure",
        )
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `the defaults are the fifteen minutes and hundred metres the plan specifies`() {
        assertEquals(15 * 60 * 1000L, BackgroundAbuseWatchdog.DEFAULT_WINDOW_MS)
        assertEquals(100.0, BackgroundAbuseWatchdog.DEFAULT_THRESHOLD_M)
    }
}
