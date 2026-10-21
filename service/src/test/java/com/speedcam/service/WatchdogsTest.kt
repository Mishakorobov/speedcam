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

    // ------------------------------------------------------- background abuse / inactivity

    @Test
    fun `moving in the foreground is never suspicious`() {
        val watchdog = BackgroundAbuseWatchdog()
        assertFalse(watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 5_000.0, 0.0)))
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `moving while backgrounded resets the anchor and timer`() {
        val watchdog = BackgroundAbuseWatchdog(windowMs = 900_000, thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)

        // At 10 minutes, car moves 200 m (exceeds 100 m threshold)
        val pos200 = ServiceFixtures.offset(origin, 200.0, 0.0)
        assertFalse(
            watchdog.onMoved(600_000, pos200),
            "moving resets the timer and keeps service running",
        )

        // At 20 minutes (10 minutes after move), total time is 20 min but time since reset is only 10 min (< 15 min)
        assertFalse(
            watchdog.onMoved(1_200_000, pos200),
            "timer was reset so service must stay alive",
        )

        // At 26 minutes (16 minutes after move without further movement): trips watchdog
        assertTrue(
            watchdog.onMoved(1_560_001, pos200),
            "16 minutes stationary after reset trips watchdog",
        )
    }

    @Test
    fun `remaining stationary while backgrounded past the window trips the watchdog`() {
        val watchdog = BackgroundAbuseWatchdog(windowMs = 900_000, thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)

        assertFalse(
            watchdog.onMoved(60_000, ServiceFixtures.offset(origin, 50.0, 0.0)),
            "50 m is under threshold, within window",
        )
        assertFalse(
            watchdog.onMoved(900_000, ServiceFixtures.offset(origin, 50.0, 0.0)),
            "exactly at windowMs has not yet exceeded",
        )
        assertTrue(
            watchdog.onMoved(900_001, ServiceFixtures.offset(origin, 50.0, 0.0)),
            "stationary for > 15 min trips the watchdog",
        )
    }

    @Test
    fun `the threshold is strict`() {
        val watchdog = BackgroundAbuseWatchdog(windowMs = 900_000, thresholdM = 100.0)
        watchdog.onBackgrounded(0, origin)

        // Moving 99 m does not reset the anchor
        assertFalse(
            watchdog.onMoved(600_000, ServiceFixtures.offset(origin, 99.0, 0.0)),
            "99 m is under threshold",
        )
        // So at 900_001 ms from start, it trips!
        assertTrue(
            watchdog.onMoved(900_001, ServiceFixtures.offset(origin, 99.0, 0.0)),
            "anchor was not reset by 99 m displacement",
        )
    }

    @Test
    fun `coming back to the foreground clears the watchdog`() {
        val watchdog = BackgroundAbuseWatchdog()
        watchdog.onBackgrounded(0, origin)
        watchdog.onForegrounded()

        assertFalse(watchdog.onMoved(1_000, ServiceFixtures.offset(origin, 5_000.0, 0.0)))
        assertFalse(watchdog.isArmed)
    }

    @Test
    fun `entering background without a known starting position initializes on first fix`() {
        val watchdog = BackgroundAbuseWatchdog(windowMs = 900_000, thresholdM = 100.0)
        watchdog.onBackgrounded(0, position = null)
        assertTrue(watchdog.isArmed)

        // First fix at 1,000 ms initializes anchor
        assertFalse(watchdog.onMoved(1_000, origin))
        // Remains stationary for 900,000 ms after anchor initialization
        assertFalse(watchdog.onMoved(901_000, origin))
        assertTrue(watchdog.onMoved(901_001, origin))
    }

    @Test
    fun `the defaults are the fifteen minutes and hundred metres the plan specifies`() {
        assertEquals(15 * 60 * 1000L, BackgroundAbuseWatchdog.DEFAULT_WINDOW_MS)
        assertEquals(100.0, BackgroundAbuseWatchdog.DEFAULT_THRESHOLD_M)
    }
}
