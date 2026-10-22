package com.speedcam.core.detect

import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SpeedFilterTest {
    private lateinit var filter: SpeedFilter

    @BeforeEach
    fun setUp() {
        filter = SpeedFilter()
    }

    // ---------------------------------------------------------------- deadband threshold

    @Test
    fun `stationary noise below deadband threshold returns strictly 0`() {
        // 0.1 m/s = 0.36 km/h (< 2.0 km/h)
        val speed01 = filter.filter(0.1f)
        assertEquals(0.0f, speed01)
        assertEquals(0.0, filter.currentSpeedKmh)

        // 0.4 m/s = 1.44 km/h (< 2.0 km/h)
        val speed04 = filter.filter(0.4f)
        assertEquals(0.0f, speed04)
        assertEquals(0.0, filter.currentSpeedKmh)

        // 0.55 m/s = 1.98 km/h (< 2.0 km/h, right below the 2.0 km/h threshold)
        val speed055 = filter.filter(0.55f)
        assertEquals(0.0f, speed055)
        assertEquals(0.0, filter.currentSpeedKmh)
    }

    @Test
    fun `stationary GPS jitter sequence remains strictly 0`() {
        val jitterSeries = listOf(0.1f, 0.35f, 0.4f, 0.2f, 0.5f, 0.0f, 0.55f)
        for (sample in jitterSeries) {
            val result = filter.filter(sample)
            assertEquals(0.0f, result, "Jitter sample $sample m/s should clamp to 0.0")
            assertEquals(0.0, filter.currentSpeedKmh)
        }
    }

    @Test
    fun `invalid or negative speeds are clamped to 0`() {
        assertEquals(0.0f, filter.filter(-1.0f))
        assertEquals(0.0f, filter.filter(-0.01f))
        assertEquals(0.0f, filter.filter(Float.NaN))
        assertEquals(0.0, filter.currentSpeedKmh)
    }

    // ---------------------------------------------------------------- takeoff & EMA smoothing

    @Test
    fun `speed exceeding deadband initiates takeoff with EMA smoothing`() {
        // Just above threshold: 0.56 m/s = 2.016 km/h (>= 2.0 km/h)
        val takeoff = filter.filter(0.56f)
        assertTrue(takeoff > 0.0f, "Speed >= 2.0 km/h must begin movement")
        assertEquals(0.28f, takeoff, 0.01f) // 0.5 * 0.56 = 0.28 m/s (~1.0 km/h)
    }

    @Test
    fun `takeoff at 0_8 m per s smoothly ramps up without erratic jumps`() {
        // 0.8 m/s = 2.88 km/h. Default alpha = 0.5f
        val step1 = filter.filter(0.8f) // 0.5 * 0.8 + 0.5 * 0.0 = 0.400 m/s (1.44 km/h)
        assertEquals(0.4f, step1, 0.001f)
        assertEquals(1.44, filter.currentSpeedKmh, 0.01)

        val step2 = filter.filter(0.8f) // 0.5 * 0.8 + 0.5 * 0.4 = 0.600 m/s (2.16 km/h)
        assertEquals(0.6f, step2, 0.001f)
        assertEquals(2.16, filter.currentSpeedKmh, 0.01)

        val step3 = filter.filter(0.8f) // 0.5 * 0.8 + 0.5 * 0.6 = 0.700 m/s (2.52 km/h)
        assertEquals(0.7f, step3, 0.001f)
        assertEquals(2.52, filter.currentSpeedKmh, 0.01)

        val step4 = filter.filter(0.8f) // 0.5 * 0.8 + 0.5 * 0.7 = 0.750 m/s (2.70 km/h)
        assertEquals(0.75f, step4, 0.001f)
        assertEquals(2.70, filter.currentSpeedKmh, 0.01)

        val step5 = filter.filter(0.8f) // 0.5 * 0.8 + 0.5 * 0.75 = 0.775 m/s (2.79 km/h)
        assertEquals(0.775f, step5, 0.001f)
        assertEquals(2.79, filter.currentSpeedKmh, 0.01)
    }

    @Test
    fun `cruising speed at 2_5 m per s converges smoothly`() {
        // 2.5 m/s = 9.0 km/h
        val step1 = filter.filter(2.5f) // 0.5 * 2.5 + 0.5 * 0.0 = 1.25 m/s (4.5 km/h)
        assertEquals(1.25f, step1, 0.001f)

        val step2 = filter.filter(2.5f) // 0.5 * 2.5 + 0.5 * 1.25 = 1.875 m/s (6.75 km/h)
        assertEquals(1.875f, step2, 0.001f)

        val step3 = filter.filter(2.5f) // 0.5 * 2.5 + 0.5 * 1.875 = 2.1875 m/s
        assertEquals(2.1875f, step3, 0.001f)

        // After repeated fixes, it converges accurately to target speed
        repeat(5) { filter.filter(2.5f) }
        assertEquals(2.5f, filter.currentSpeedMs, 0.02f)
        assertEquals(9.0, filter.currentSpeedKmh, 0.1)
    }

    // ---------------------------------------------------------------- deceleration & stopping

    @Test
    fun `stopping immediately snaps to 0 without dragging`() {
        // Accelerate to cruising speed
        repeat(6) { filter.filter(2.5f) }
        assertTrue(filter.currentSpeedMs > 2.4f)

        // Vehicle stops: raw speed drops below 2.0 km/h (e.g. 0.4 m/s)
        val stopped = filter.filter(0.4f)
        assertEquals(0.0f, stopped)
        assertEquals(0.0f, filter.currentSpeedMs)
        assertEquals(0.0, filter.currentSpeedKmh)
    }

    // ---------------------------------------------------------------- Fix filtering & reset

    @Test
    fun `filter Fix object updates speedMs and speedKmh`() {
        val position = LatLon(55.75, 37.61)
        val stationaryFix = Fix(position = position, timestampMillis = 1_000L, speedMs = 0.3f)
        val filteredStationary = filter.filter(stationaryFix)

        assertEquals(0.0f, filteredStationary.speedMs)
        assertEquals(0.0, filteredStationary.speedKmh)
        assertEquals(position, filteredStationary.position)

        val movingFix = Fix(position = position, timestampMillis = 2_000L, speedMs = 0.8f)
        val filteredMoving = filter.filter(movingFix)

        assertEquals(0.4f, filteredMoving.speedMs, 0.001f)
        assertEquals(1.44, filteredMoving.speedKmh, 0.01)
    }

    @Test
    fun `reset clears filter state back to stationary 0`() {
        repeat(4) { filter.filter(2.5f) }
        assertTrue(filter.currentSpeedMs > 2.0f)

        filter.reset()
        assertEquals(0.0f, filter.currentSpeedMs)
        assertEquals(0.0, filter.currentSpeedKmh)
    }
}
