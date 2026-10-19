package com.speedcam.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the latch that stops an alert repeating on every location fix.
 *
 * The failure this guards against is not subtle in production: at the 1 Hz cadence the fused
 * provider delivers, a driver approaching one camera would hear the same alert dozens of
 * times. Everything here is a pure function of time, so the whole 1200 ms window is checked
 * exactly rather than approximated by waiting.
 */
class AlertCadenceTest {
    private fun request(
        sound: Boolean = false,
        beep: Boolean = true,
        speech: Boolean = false,
        playbackMillis: Long = 800L,
    ) = AlertRequest(
        wantsSound = sound,
        wantsBeep = beep,
        wantsSpeech = speech,
        playbackMillis = playbackMillis,
    )

    @Test
    fun `the latch starts open so the first alert is heard`() {
        val outcome = AlertCadence.advance(AlertCadence.initial(), 0L, request())
        assertTrue(outcome.beep)
        assertTrue(outcome.speech.not() || outcome.speech)
    }

    @Test
    fun `the first alert plays everything it asks for`() {
        val outcome =
            AlertCadence.advance(
                AlertCadence.initial(),
                1_000L,
                request(sound = true, beep = true, speech = true),
            )
        assertTrue(outcome.sound)
        assertTrue(outcome.beep)
        assertTrue(outcome.speech)
    }

    @Test
    fun `a second alert inside the window drops the beep and the speech`() {
        val first = AlertCadence.advance(AlertCadence.initial(), 0L, request(speech = true))
        val second = AlertCadence.advance(first.next, 400L, request(speech = true))

        assertFalse(second.beep, "the tone must not retrigger on every fix")
        assertFalse(second.speech, "speech would stutter mid-phrase")
    }

    @Test
    fun `a bank sound still plays while the latch is closed`() {
        // The original's one exception: while latched low, a bare sound id still gets through.
        val first = AlertCadence.advance(AlertCadence.initial(), 0L, request(sound = true, beep = true))
        val second = AlertCadence.advance(first.next, 200L, request(sound = true, beep = true))

        assertTrue(second.sound)
        assertFalse(second.beep)
    }

    @Test
    fun `the latch reopens once the interval has elapsed`() {
        // A tone-only alert is stretched to the floor, which is 1200 ms and not the tone's own
        // length: a 40 ms beep must not open a 40 ms window at a 1 Hz fix rate.
        val first = AlertCadence.advance(AlertCadence.initial(), 0L, request(playbackMillis = 800L))
        assertEquals(AlertCadence.MIN_INTERVAL_MS, first.next.reopensAtMillis)

        val early = AlertCadence.advance(first.next, 1_199L, request())
        assertFalse(early.beep)

        val onTime = AlertCadence.advance(first.next, 1_200L, request())
        assertTrue(onTime.beep, "the window must end when it says it does")
    }

    @Test
    fun `a bank sound uses the original's twelve hundred millisecond window`() {
        val first = AlertCadence.advance(AlertCadence.initial(), 0L, request(sound = true, playbackMillis = 200L))
        assertEquals(AlertCadence.SOUND_INTERVAL_MS, first.next.reopensAtMillis)

        // The tone length is irrelevant when a bank sound plays: the original uses the fixed
        // 1200 ms for that path.
        val at1199 = AlertCadence.advance(first.next, 1_199L, request(sound = true))
        assertFalse(at1199.beep)

        val at1200 = AlertCadence.advance(first.next, 1_200L, request(sound = true))
        assertTrue(at1200.beep)
    }

    @Test
    fun `a playback shorter than the floor is stretched to it`() {
        // A 40 ms tone must not open a 40 ms window; that would defeat the latch at 1 Hz.
        val first = AlertCadence.advance(AlertCadence.initial(), 0L, request(playbackMillis = 40L))
        assertEquals(AlertCadence.MIN_INTERVAL_MS, first.next.reopensAtMillis)
    }

    @Test
    fun `a profile that wants nothing still advances nothing audible`() {
        val outcome =
            AlertCadence.advance(AlertCadence.initial(), 0L, request(sound = false, beep = false, speech = false))
        assertFalse(outcome.sound)
        assertFalse(outcome.beep)
        assertFalse(outcome.speech)
    }

    @Test
    fun `a long stream of fixes at one hertz drops at least every other alert`() {
        // The property that actually matters, stated as a bound rather than a vibe. At 1 Hz
        // with a 1200 ms window the driver can be told at most every second fix, so a single
        // camera is announced about twice a second on approach instead of continuously.
        var state = AlertCadence.initial()
        var audible = 0

        var second = 0
        while (second <= 20) {
            val outcome = AlertCadence.advance(state, second * 1_000L, request(speech = true))
            state = outcome.next
            if (outcome.beep || outcome.speech) audible++
            second++
        }

        assertEquals(21, second, "twenty-one fixes were fed in")
        assertTrue(
            audible <= 11,
            "twenty-one fixes produced $audible announcements; at most every other one may be heard",
        )
    }

    @Test
    fun `a fast fix stream is throttled hard`() {
        // At 10 Hz, which is what a fused provider delivers outdoors on a good signal, the
        // window drops ninety per cent of the alerts. Without this the driver hears a
        // continuous tone for the whole approach.
        var state = AlertCadence.initial()
        var audible = 0

        repeat(200) { tick ->
            val outcome = AlertCadence.advance(state, tick * 100L, request())
            state = outcome.next
            if (outcome.beep) audible++
        }

        assertTrue(
            audible <= 17,
            "200 fixes at 10 Hz produced $audible announcements; expected about 16",
        )
    }
}
