package com.speedcam.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GagerTonesTest {
    @Test
    fun `the step index matches the original formula`() {
        // floor(km * 16.0934), clamped.
        assertEquals(0, GagerTones.stepIndex(0.0))
        assertEquals(1, GagerTones.stepIndex(0.1))
        assertEquals(3, GagerTones.stepIndex(0.2))
        assertEquals(6, GagerTones.stepIndex(0.4))
        assertEquals(12, GagerTones.stepIndex(0.8))
    }

    @Test
    fun `the index saturates at both ends`() {
        assertEquals(0, GagerTones.stepIndex(-5.0))
        assertEquals(GagerTones.MAX_INDEX, GagerTones.stepIndex(50.0))
    }

    @Test
    fun `pitch rises as the camera gets closer`() {
        // The whole point of the gager: a rising tone means "getting closer".
        val far = GagerTones.frequencyHz(1.0)
        val mid = GagerTones.frequencyHz(0.3)
        val near = GagerTones.frequencyHz(0.02)

        assertTrue(near > mid, "near ($near) must be higher pitched than mid ($mid)")
        assertTrue(mid > far, "mid ($mid) must be higher pitched than far ($far)")
    }

    @Test
    fun `the frequency ladder matches the original`() {
        assertEquals(1028, GagerTones.frequencyHz(1.0))
        assertEquals(1088, GagerTones.frequencyHz(0.4))
        assertEquals(1168, GagerTones.frequencyHz(0.25))
        assertEquals(1248, GagerTones.frequencyHz(0.15))
        assertEquals(1328, GagerTones.frequencyHz(0.03))
    }

    @Test
    fun `duration matches the original`() {
        assertEquals(800, GagerTones.durationMs(1.0))
        assertEquals(800, GagerTones.durationMs(0.4))
        assertEquals(700, GagerTones.durationMs(0.25))
        assertEquals(600, GagerTones.durationMs(0.15))
        assertEquals(500, GagerTones.durationMs(0.03))
    }

    @Test
    fun `rendered length matches the requested duration`() {
        val pcm = GagerTones.render(0.03)
        val expected = GagerTones.SAMPLE_RATE_HZ * GagerTones.durationMs(0.03) / 1000
        assertEquals(expected, pcm.size)
    }

    @Test
    fun `rendered samples stay inside 16-bit range`() {
        val pcm = GagerTones.render(0.03)
        assertTrue(pcm.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
    }

    @Test
    fun `the envelope fades in and out to avoid clicks`() {
        val pcm = GagerTones.render(0.03)
        val head = kotlin.math.abs(pcm.first().toInt())
        val tail = kotlin.math.abs(pcm.last().toInt())
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }

        assertTrue(head < peak / 4, "first sample ($head) must be far below the peak ($peak)")
        assertTrue(tail < peak / 4, "last sample ($tail) must be far below the peak ($peak)")
    }

    @Test
    fun `the buffer carries real signal`() {
        val pcm = GagerTones.render(0.03)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak > 10_000, "expected a substantial signal, peak was $peak")
        assertTrue(peak <= 32_767, "must not clip, peak was $peak")
    }
}
