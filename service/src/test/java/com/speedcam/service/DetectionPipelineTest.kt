package com.speedcam.service

import com.speedcam.core.detect.AlertChannel
import com.speedcam.core.detect.AlertContext
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.SectionEvent
import com.speedcam.core.detect.SectionQuery
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.detect.WarnTime
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that the three pipeline stages are wired to each other correctly.
 *
 * Each stage is individually pure and individually tested elsewhere; what is easy to get wrong
 * is the order they run in, and that is what this class covers.
 */
class DetectionPipelineTest {
    private val camera = ServiceFixtures.camera(id = 1, metresNorth = 400.0, limitKmh = 60)
    private val pipeline = DetectionPipeline(CameraQuery { listOf(camera) })
    private val profile = AlertProfile(captureDistanceKm = 1.0)

    @Test
    fun `a camera inside the radius produces a decision`() {
        val result = pipeline.process(ServiceFixtures.fix(speedKmh = 90.0), profile, previous = null)

        assertEquals(1, result.hazards.size)
        assertEquals(1, result.decisions.size)
        assertTrue(result.hasAlerts)
        assertTrue(AlertChannel.BEEP in result.decisions.single().channels, "90 against a 60 is speeding")
    }

    @Test
    fun `the result carries the arbitrated fix rather than the raw one`() {
        val good = ServiceFixtures.fix(speedKmh = 90.0)
        assertSame(good, pipeline.process(good, profile, previous = null).fix)
    }

    @Test
    fun `a rejected candidate is never detected against`() {
        // An unusable fix several hundred kilometres off. Detecting from it would place the
        // vehicle far from the truth, so arbitration has to happen before the engine sees it.
        val good = ServiceFixtures.fix(speedKmh = 90.0)
        val junk =
            ServiceFixtures
                .fix(position = LatLon(70.0, 40.0), speedKmh = 90.0, timestampMillis = good.timestampMillis + 1_000)
                .copy(accuracyM = 5_000f)

        val result = pipeline.process(junk, profile, previous = good)
        assertSame(good, result.fix, "the incumbent must survive an unusable candidate")
        assertEquals(1, result.hazards.size)
        assertTrue(result.hazards.single().distanceM < 1_000.0, "detected against the accepted position")
    }

    @Test
    fun `a muted profile still detects the camera but says nothing about it`() {
        val muted =
            AlertProfile(
                voice = false,
                soundEnabled = false,
                beepEnabled = false,
                vibro = false,
                localNotifications = false,
            )
        val result = pipeline.process(ServiceFixtures.fix(speedKmh = 90.0), muted, previous = null)

        assertEquals(1, result.hazards.size, "the camera is still detected")
        assertTrue(result.decisions.isEmpty(), "but there is nothing to be said about it")
        assertFalse(result.hasAlerts)
    }

    @Test
    fun `a time-of-day gate removes every decision but keeps the hazards`() {
        val nightOnly = profile.copy(warnTime = WarnTime.NIGHT)
        val result =
            pipeline.process(
                ServiceFixtures.fix(speedKmh = 90.0),
                nightOnly,
                previous = null,
                context = AlertContext(isNight = false),
            )

        assertEquals(1, result.hazards.size)
        assertTrue(result.decisions.isEmpty())
    }

    @Test
    fun `the same fix at night is allowed by a night-only profile`() {
        val nightOnly = profile.copy(warnTime = WarnTime.NIGHT)
        val result =
            pipeline.process(
                ServiceFixtures.fix(speedKmh = 90.0),
                nightOnly,
                previous = null,
                context = AlertContext(isNight = true),
            )
        assertEquals(1, result.decisions.size)
    }

    @Test
    fun `no cameras nearby means no hazards and no decisions`() {
        val empty = DetectionPipeline(CameraQuery { emptyList() })
        val result = empty.process(ServiceFixtures.fix(speedKmh = 90.0), profile, previous = null)

        assertTrue(result.hazards.isEmpty())
        assertTrue(result.decisions.isEmpty())
        assertFalse(result.hasAlerts)
    }

    @Test
    fun `a camera beyond the capture distance is neither detected nor decided`() {
        val far = DetectionPipeline(CameraQuery { listOf(ServiceFixtures.camera(id = 2, metresNorth = 3_000.0)) })
        val result = far.process(ServiceFixtures.fix(speedKmh = 90.0), profile, previous = null)

        assertTrue(result.hazards.isEmpty())
        assertTrue(result.decisions.isEmpty())
    }

    @Test
    fun `an average-speed corridor emits Entered and Exited through the pipeline`() {
        val start = ServiceFixtures.ORIGIN
        val end = ServiceFixtures.offset(start, metresNorth = 900.0, metresEast = 0.0)
        val section = SectionRecord(from = start, to = end, limitKmh = 90)
        val specsPipeline =
            DetectionPipeline(
                query = CameraQuery { emptyList() },
                sections = SectionQuery { listOf(section) },
            )

        val approachFix =
            ServiceFixtures.fix(
                position = ServiceFixtures.offset(start, metresNorth = -200.0, metresEast = 0.0),
                headingDeg = 0.0,
                speedKmh = 90.0,
                timestampMillis = 0L,
            )
        val initial = specsPipeline.process(approachFix, profile, previous = null)
        assertNull(initial.sectionEvent)

        val enterFix =
            ServiceFixtures.fix(
                position = start,
                headingDeg = 0.0,
                speedKmh = 90.0,
                timestampMillis = 8_000L,
            )
        val first = specsPipeline.process(enterFix, profile, previous = initial.fix)
        assertTrue(first.sectionEvent is SectionEvent.Entered)
        assertTrue(first.hasAlerts)

        val exitFix =
            ServiceFixtures.fix(
                position = end,
                headingDeg = 0.0,
                speedKmh = 90.0,
                timestampMillis = 44_000L,
            )
        val second = specsPipeline.process(exitFix, profile, previous = first.fix)
        assertTrue(second.sectionEvent is SectionEvent.Exited)
        assertTrue(second.hasAlerts)
    }
}
