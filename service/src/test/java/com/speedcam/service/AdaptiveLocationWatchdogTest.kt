package com.speedcam.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdaptiveLocationWatchdogTest {
    private val origin = ServiceFixtures.ORIGIN

    @Test
    fun `initial polling rate is active`() {
        val watchdog = AdaptiveLocationWatchdog()
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)
    }

    @Test
    fun `driving above speed threshold keeps rate active`() {
        val watchdog = AdaptiveLocationWatchdog()
        val fix = ServiceFixtures.fix(speedKmh = 60.0)

        assertEquals(PollingRate.ACTIVE, watchdog.onFix(1_000L, fix))
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(65_000L, fix))
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)
    }

    @Test
    fun `stationary below threshold for less than delay remains active`() {
        val watchdog = AdaptiveLocationWatchdog(stationaryDelayMs = 60_000L)
        val stationaryFix = ServiceFixtures.fix(speedKmh = 0.0)

        // First fix at T=0
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(0L, stationaryFix))
        // Halfway through delay at T=30s
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(30_000L, stationaryFix))
        // Just before delay boundary at T=59.9s
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(59_999L, stationaryFix))
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)
    }

    @Test
    fun `stationary for 60 seconds or more throttles to stationary polling`() {
        val watchdog = AdaptiveLocationWatchdog(stationaryDelayMs = 60_000L)
        val stationaryFix = ServiceFixtures.fix(speedKmh = 1.0)

        assertEquals(PollingRate.ACTIVE, watchdog.onFix(0L, stationaryFix))
        // Exactly at 60 seconds
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(60_000L, stationaryFix))
        // Later fixes while still stationary
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(65_000L, stationaryFix))
        assertEquals(PollingRate.STATIONARY, watchdog.currentRate)
    }

    @Test
    fun `moving by speed immediately restores active polling`() {
        val watchdog = AdaptiveLocationWatchdog(stationaryDelayMs = 60_000L)
        val stationaryFix = ServiceFixtures.fix(speedKmh = 0.0)

        watchdog.onFix(0L, stationaryFix)
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(60_000L, stationaryFix))

        // Vehicle accelerates to 20 km/h
        val movingFix = ServiceFixtures.fix(speedKmh = 20.0)
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(65_000L, movingFix))
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)

        // Stopping again requires a fresh 60 seconds before throttling
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(70_000L, stationaryFix))
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(129_000L, stationaryFix))
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(130_000L, stationaryFix))
    }

    @Test
    fun `moving by displacement immediately restores active polling`() {
        val watchdog =
            AdaptiveLocationWatchdog(
                stationaryDelayMs = 60_000L,
                displacementThresholdM = 15.0,
            )
        val stationaryFix = ServiceFixtures.fix(position = origin, speedKmh = 0.0)

        watchdog.onFix(0L, stationaryFix)
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(60_000L, stationaryFix))

        // Vehicle moved 30 meters away, even if speed sensor reports 0
        val movedPosition = ServiceFixtures.offset(origin, metresNorth = 30.0, metresEast = 0.0)
        val displacedFix = ServiceFixtures.fix(position = movedPosition, speedKmh = 0.0)

        assertEquals(PollingRate.ACTIVE, watchdog.onFix(65_000L, displacedFix))
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)
    }

    @Test
    fun `small GPS jitter within displacement threshold does not abort stationary state`() {
        val watchdog =
            AdaptiveLocationWatchdog(
                stationaryDelayMs = 60_000L,
                displacementThresholdM = 15.0,
            )
        val fix1 = ServiceFixtures.fix(position = origin, speedKmh = 0.0)
        // 5 meters drift (less than 15 m threshold)
        val driftPosition = ServiceFixtures.offset(origin, metresNorth = 5.0, metresEast = 0.0)
        val fix2 = ServiceFixtures.fix(position = driftPosition, speedKmh = 1.0)

        watchdog.onFix(0L, fix1)
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(30_000L, fix2))
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(60_000L, fix2))
    }

    @Test
    fun `reset clears timers and restores active rate`() {
        val watchdog = AdaptiveLocationWatchdog(stationaryDelayMs = 60_000L)
        val stationaryFix = ServiceFixtures.fix(speedKmh = 0.0)

        watchdog.onFix(0L, stationaryFix)
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(60_000L, stationaryFix))

        watchdog.reset()
        assertEquals(PollingRate.ACTIVE, watchdog.currentRate)

        // Next stationary fix starts a brand new window
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(70_000L, stationaryFix))
        assertEquals(PollingRate.ACTIVE, watchdog.onFix(129_000L, stationaryFix))
        assertEquals(PollingRate.STATIONARY, watchdog.onFix(130_000L, stationaryFix))
    }

    @Test
    fun `default parameters match Phase 9_5 specification`() {
        assertEquals(3.0, AdaptiveLocationWatchdog.DEFAULT_STATIONARY_SPEED_KMH)
        assertEquals(60_000L, AdaptiveLocationWatchdog.DEFAULT_STATIONARY_DELAY_MS)
        assertEquals(15.0, AdaptiveLocationWatchdog.DEFAULT_DISPLACEMENT_THRESHOLD_M)
        assertEquals(1_000L, PollingRate.ACTIVE.intervalMs)
        assertEquals(500L, PollingRate.ACTIVE.minUpdateIntervalMs)
        assertEquals(5_000L, PollingRate.STATIONARY.intervalMs)
        assertEquals(2_500L, PollingRate.STATIONARY.minUpdateIntervalMs)
    }
}
