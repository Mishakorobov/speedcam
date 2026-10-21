package com.speedcam.core.detect

import com.speedcam.core.geo.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the SPECS average-speed corridor state machine from Plan.md §8.1.
 *
 * All time and geometry inputs arrive through deterministic [Fix] instances, so entry,
 * progress, exit arithmetic `v_avg = (s1 - s0) / (t1 - t0)`, and every abort condition are
 * checked exactly without a clock or device.
 */
class AverageSpeedTrackerTest {
    private val start = Fixtures.ORIGIN
    private val end = Fixtures.offset(start, metresNorth = 1_000.0, metresEast = 0.0)
    private val corridor = SectionRecord(from = start, to = end, limitKmh = 90)

    private fun trackerOver(vararg sections: SectionRecord) = AverageSpeedTracker(SectionQuery { sections.toList() })

    @Test
    fun `passing the entry gate along the corridor bearing records t0 and s0`() {
        val tracker = trackerOver(corridor)

        // Pre-corridor approach 200 m south so s0 is non-zero when entering.
        val approach =
            Fixtures.fix(
                position = Fixtures.offset(start, -200.0, 0.0),
                headingDeg = 0.0,
                timestampMillis = 1_000L,
            )
        assertNull(tracker.onFix(approach))
        assertFalse(tracker.isInCorridor)

        val entry = Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 9_000L)
        val event = tracker.onFix(entry) as? SectionEvent.Entered
        assertNotNull(event, "entering within the gate radius must emit Entered")
        assertTrue(tracker.isInCorridor)
        assertEquals(9_000L, event!!.entryTimeMillis)
        assertEquals(Geo.haversineMeters(approach.position, start), event.entryOdometerM, 0.5)
    }

    @Test
    fun `oncoming traffic passing the entry point in the opposite direction does not enter`() {
        val tracker = trackerOver(corridor)

        val oncoming = Fixtures.fix(position = start, headingDeg = 180.0, timestampMillis = 1_000L)
        assertNull(tracker.onFix(oncoming), "southbound traffic on a northbound corridor must not enter")
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun `intermediate fixes emit live progress and exit computes v_avg from s1 minus s0 over t1 minus t0`() {
        val tracker = trackerOver(corridor)
        val mid = Fixtures.offset(start, metresNorth = 500.0, metresEast = 0.0)

        val entered = tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L))
        assertTrue(entered is SectionEvent.Entered)

        // 500 m in 20 s -> 25 m/s = 90 km/h.
        val progress =
            tracker.onFix(
                Fixtures.fix(
                    position = mid,
                    headingDeg = 0.0,
                    timestampMillis = 20_000L,
                ),
            ) as? SectionEvent.Progress
        assertNotNull(progress)
        val expectedHalfM = Geo.haversineMeters(start, mid)
        val expectedHalfKmh = (expectedHalfM / 20.0) * 3.6
        assertEquals(expectedHalfKmh, progress!!.averageKmh, 0.2)
        assertTrue(tracker.isInCorridor)

        // 1000 m total in 36 s -> ~100 km/h (excess ~10 km/h over the 90 km/h limit).
        val exited =
            tracker.onFix(
                Fixtures.fix(
                    position = end,
                    headingDeg = 0.0,
                    timestampMillis = 36_000L,
                ),
            ) as? SectionEvent.Exited
        assertNotNull(exited, "reaching the exit point must emit Exited")
        assertFalse(tracker.isInCorridor, "corridor session clears on exit")

        val totalDistanceM = Geo.haversineMeters(start, mid) + Geo.haversineMeters(mid, end)
        val expectedAvgKmh = (totalDistanceM / 36.0) * 3.6
        assertEquals(36_000L, exited!!.durationMillis)
        assertEquals(totalDistanceM, exited.distanceM, 1e-6)
        assertEquals(expectedAvgKmh, exited.averageKmh, 0.01)
        assertEquals(expectedAvgKmh - 90.0, exited.excessKmh, 0.01)
    }

    @Test
    fun `unusable fixes are ignored without corrupting the odometer or aborting`() {
        val tracker = trackerOver(corridor)
        tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L))

        val noisy =
            Fixtures
                .fix(
                    position = Fixtures.offset(start, 50_000.0, 50_000.0),
                    headingDeg = 0.0,
                    timestampMillis = 10_000L,
                ).copy(accuracyM = 500f)
        assertNull(tracker.onFix(noisy), "an unusable fix must be ignored")
        assertTrue(tracker.isInCorridor, "corridor must remain active")

        val exited =
            tracker.onFix(
                Fixtures.fix(
                    position = end,
                    headingDeg = 0.0,
                    timestampMillis = 40_000L,
                ),
            ) as? SectionEvent.Exited
        assertNotNull(exited)
        assertEquals(Geo.haversineMeters(start, end), exited!!.distanceM, 1.0)
    }

    @Test
    fun `exceeding the maximum traversal time aborts with TIMEOUT`() {
        val tracker = trackerOver(corridor)
        tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L))

        val mid = Fixtures.offset(start, metresNorth = 300.0, metresEast = 0.0)
        val late =
            Fixtures.fix(
                position = mid,
                headingDeg = 0.0,
                timestampMillis = AverageSpeedTracker.MIN_TIMEOUT_MS + 1_000L,
            )
        val event = tracker.onFix(late) as? SectionEvent.Aborted

        assertNotNull(event)
        assertEquals(SectionAbortReason.TIMEOUT, event!!.reason)
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun `wandering far off the corridor aborts with OFF_CORRIDOR`() {
        val tracker = trackerOver(corridor)
        tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L))

        val farEast = Fixtures.offset(start, metresNorth = 500.0, metresEast = 3_000.0)
        val event =
            tracker.onFix(
                Fixtures.fix(
                    position = farEast,
                    headingDeg = 90.0,
                    timestampMillis = 30_000L,
                ),
            ) as? SectionEvent.Aborted

        assertNotNull(event)
        assertEquals(SectionAbortReason.OFF_CORRIDOR, event!!.reason)
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun `a backwards timestamp aborts with TIME_REVERSED`() {
        val tracker = trackerOver(corridor)
        tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 10_000L))

        val mid = Fixtures.offset(start, metresNorth = 200.0, metresEast = 0.0)
        val event =
            tracker.onFix(
                Fixtures.fix(
                    position = mid,
                    headingDeg = 0.0,
                    timestampMillis = 9_000L,
                ),
            ) as? SectionEvent.Aborted

        assertNotNull(event)
        assertEquals(SectionAbortReason.TIME_REVERSED, event!!.reason)
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun `GPS loss mid-corridor aborts with GPS_LOST`() {
        val tracker = trackerOver(corridor)
        assertNull(tracker.onGpsLost(), "nothing to abort when outside a corridor")

        tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L))
        val aborted = tracker.onGpsLost()

        assertNotNull(aborted)
        assertEquals(SectionAbortReason.GPS_LOST, aborted!!.reason)
        assertFalse(tracker.isInCorridor)
    }

    @Test
    fun `inactive sections are ignored`() {
        val inactive = corridor.copy(active = false)
        val tracker = trackerOver(inactive)

        assertNull(tracker.onFix(Fixtures.fix(position = start, headingDeg = 0.0, timestampMillis = 0L)))
        assertFalse(tracker.isInCorridor)
    }
}
