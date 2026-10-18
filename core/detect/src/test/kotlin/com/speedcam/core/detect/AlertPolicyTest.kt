package com.speedcam.core.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tests for the alert policy: which channels a hazard should raise, and when.
 *
 * The exhaustive sweep over all sixteen enable/disable combinations is the point of this
 * class. Individually asserting sixteen cases invites the sixteenth to be quietly wrong, and
 * the failure mode is a driver who is warned in a way they did not ask for, or not warned in
 * a way they relied on.
 */
class AlertPolicyTest {
    /** 90 km/h past a 60 km/h camera: excess 30, comfortably over the default threshold. */
    private val speeding = Fixtures.hazard(id = 1, limitKmh = 60, speedKmh = 90.0)

    /** Exactly at the limit: excess 0, so below any positive threshold. */
    private val atLimit = Fixtures.hazard(id = 1, limitKmh = 60, speedKmh = 60.0)

    /** No recorded limit, so no excess exists to exceed. */
    private val unknownLimit = Fixtures.hazard(id = 1, limitKmh = null, speedKmh = 90.0)

    private val everythingOff =
        AlertProfile(
            voice = false,
            soundEnabled = false,
            beepEnabled = false,
            vibro = false,
            localNotifications = false,
        )

    // --------------------------------------------------- all sixteen combinations

    @Test
    fun `every combination of the five channel switches produces exactly those channels`() {
        val switches = listOf("voice", "soundEnabled", "beepEnabled", "vibro", "localNotifications")
        // Explicitly typed: an untyped `1 shl n` here resolves to java.math.BigInteger through the
        // overloaded assertEquals signatures, and that poisons every `bits and ...` below.
        val combinations: Int = 1 shl switches.size
        assertEquals(32, combinations, "five switches give 32 combinations")

        var checked = 0
        for (bits in 0 until combinations) {
            val profile =
                AlertProfile(
                    voice = bits and 0b00001 != 0,
                    soundEnabled = bits and 0b00010 != 0,
                    beepEnabled = bits and 0b00100 != 0,
                    vibro = bits and 0b01000 != 0,
                    localNotifications = bits and 0b10000 != 0,
                )

            val expected =
                buildSet {
                    if (profile.voice) add(AlertChannel.VOICE)
                    if (profile.soundEnabled) add(AlertChannel.SOUND)
                    if (profile.beepEnabled) add(AlertChannel.BEEP)
                    if (profile.vibro) add(AlertChannel.VIBRATION)
                    if (profile.localNotifications) add(AlertChannel.NOTIFICATION)
                }

            val decision = AlertPolicy.evaluate(speeding, profile)

            if (expected.isEmpty()) {
                assertNull(decision, "bits=$bits had every channel off but still produced $decision")
            } else {
                assertNotNull(decision, "bits=$bits should have produced a decision")
                assertEquals(
                    expected,
                    decision!!.channels,
                    "bits=$bits (${switches.filterIndexed { i, _ -> bits and (1 shl i) != 0 }}) " +
                        "produced the wrong channels",
                )
            }
            checked++
        }
        assertEquals(32, checked, "all combinations must be checked")
    }

    // ---------------------------------------------------------- time of day gate

    @Test
    fun `warn time always permits regardless of the hour`() {
        assertTrue(AlertPolicy.isWarnTimeSatisfied(WarnTime.ALWAYS, AlertContext(isNight = true)))
        assertTrue(AlertPolicy.isWarnTimeSatisfied(WarnTime.ALWAYS, AlertContext(isNight = false)))
    }

    @Test
    fun `warn time day suppresses at night and warns in daylight`() {
        assertTrue(AlertPolicy.isWarnTimeSatisfied(WarnTime.DAY, AlertContext(isNight = false)))
        assertFalse(AlertPolicy.isWarnTimeSatisfied(WarnTime.DAY, AlertContext(isNight = true)))
    }

    @Test
    fun `warn time night suppresses in daylight and warns at night`() {
        assertTrue(AlertPolicy.isWarnTimeSatisfied(WarnTime.NIGHT, AlertContext(isNight = true)))
        assertFalse(AlertPolicy.isWarnTimeSatisfied(WarnTime.NIGHT, AlertContext(isNight = false)))
    }

    @Test
    fun `a blocked time of day silences every channel including the beep`() {
        // The gate applies to the whole alert, not to the audible channels only: a night-only
        // profile that fired its beep in daylight would be worse than useless.
        val profile = AlertProfile(warnTime = WarnTime.NIGHT)
        assertNull(AlertPolicy.evaluate(speeding, profile, AlertContext(isNight = false)))
        assertNotNull(AlertPolicy.evaluate(speeding, profile, AlertContext(isNight = true)))
    }

    @Test
    fun `the warn time enum matches the original stored values`() {
        // warn_time_array_values in the original is literally 0/1/2.
        assertEquals(0, WarnTime.ALWAYS.ordinal)
        assertEquals(1, WarnTime.DAY.ordinal)
        assertEquals(2, WarnTime.NIGHT.ordinal)
    }

    // -------------------------------------------------------- beep excess gate

    @Test
    fun `the beep threshold is an excess over the limit and not an absolute speed`() {
        // 90 km/h against a 60 km/h camera: excess 30 clears the default threshold of 5, but
        // an absolute-speed reading would not. This is the distinction that decides whether
        // the beeper means "you are speeding" or "you are getting close".
        assertTrue(AlertPolicy.isBeepThresholdSatisfied(speeding, AlertProfile()))
        assertFalse(AlertPolicy.isBeepThresholdSatisfied(atLimit, AlertProfile()))
    }

    @Test
    fun `a driver at the limit is not beeped at`() {
        val profile = AlertProfile(beepEnabled = true, beepExcessThresholdKmh = 5, voice = false)
        val decision = AlertPolicy.evaluate(atLimit, profile)
        assertNotNull(decision, "the vibration channel is still on")
        assertFalse(
            AlertChannel.BEEP in decision!!.channels,
            "excess is zero, so the beeper must stay quiet",
        )
    }

    @Test
    fun `a threshold of zero means beep always`() {
        // The original's beep_excess_values entry 0 is the "Beeper always" case.
        val profile = AlertProfile(beepExcessThresholdKmh = 0, voice = false)
        assertTrue(
            AlertChannel.BEEP in AlertPolicy.evaluate(atLimit, profile)!!.channels,
            "threshold 0 must beep regardless of excess",
        )
    }

    @Test
    fun `a camera with no recorded limit is beeped rather than silenced`() {
        // No limit means no excess to compare against. Withholding the warning because the
        // data is missing would be the dangerous direction to be wrong in.
        val profile = AlertProfile(beepExcessThresholdKmh = 5)
        assertTrue(AlertPolicy.isBeepThresholdSatisfied(unknownLimit, profile))
    }

    @Test
    fun `the threshold is strict so that excess exactly equal to it does not beep`() {
        val hazard = Fixtures.hazard(id = 1, limitKmh = 60, speedKmh = 80.0) // excess 20
        val profile = AlertProfile(beepExcessThresholdKmh = 20)
        assertFalse(
            AlertPolicy.isBeepThresholdSatisfied(hazard, profile),
            "excess equal to the threshold must not satisfy it",
        )
        assertTrue(
            AlertPolicy.isBeepThresholdSatisfied(hazard, profile.copy(beepExcessThresholdKmh = 19)),
        )
    }

    @Test
    fun `the beep is gated independently of the other channels`() {
        val profile =
            AlertProfile(
                voice = true,
                vibro = true,
                beepEnabled = true,
                beepExcessThresholdKmh = 5,
            )
        val decision = AlertPolicy.evaluate(atLimit, profile)!!
        assertTrue(AlertChannel.VOICE in decision.channels)
        assertTrue(AlertChannel.VIBRATION in decision.channels)
        assertFalse(AlertChannel.BEEP in decision.channels, "only the beep is gated")
    }

    // --------------------------------------------------------------- sound bank

    @Test
    fun `the sound bank holds thirty-eight clips and an out-of-range id is ignored`() {
        assertEquals(38, AlertPolicy.SOUND_BANK_SIZE)

        // Everything else off, so that SOUND is the only thing that can produce a decision.
        val valid =
            AlertProfile(
                voice = false,
                vibro = false,
                soundEnabled = true,
                soundId = 37,
                beepEnabled = false,
                localNotifications = false,
            )
        assertTrue(AlertChannel.SOUND in AlertPolicy.evaluate(speeding, valid)!!.channels)

        val invalid = valid.copy(soundId = 38)
        val decision = AlertPolicy.evaluate(speeding, invalid)
        assertNull(decision, "sound 38 does not exist, and nothing else is enabled")
    }

    @Test
    fun `a sound id out of range does not stop the other channels`() {
        val profile = AlertProfile(voice = true, vibro = true, soundId = 99)
        val decision = AlertPolicy.evaluate(speeding, profile)!!
        assertTrue(AlertChannel.VOICE in decision.channels)
        assertTrue(AlertChannel.VIBRATION in decision.channels)
        assertFalse(AlertChannel.SOUND in decision.channels)
    }

    @Test
    fun `a muted bank is distinct from playing sound zero`() {
        // The original disables the bank by clearing a flag and leaves the id behind, so
        // soundId == 0 must not imply audible.
        val muted =
            AlertProfile(
                voice = false,
                vibro = false,
                soundEnabled = false,
                soundId = 0,
                beepEnabled = false,
                localNotifications = false,
            )
        assertNull(AlertPolicy.evaluate(speeding, muted))

        val unmuted = muted.copy(soundEnabled = true)
        assertTrue(AlertChannel.SOUND in AlertPolicy.evaluate(speeding, unmuted)!!.channels)
    }

    // --------------------------------------------------------------- decision

    @Test
    fun `the decision carries the gager and speech details through`() {
        val profile =
            AlertProfile(
                voice = true,
                shortVoice = true,
                pitchedTone = true,
                soundEnabled = true,
                soundId = 7,
                vibro = false,
                localNotifications = false,
            )
        val decision = AlertPolicy.evaluate(speeding, profile)!!

        assertTrue(decision.pitched, "pitchedTone selects the gager")
        assertTrue(decision.shortVoice)
        assertEquals(7, decision.soundId)
        assertEquals(60, decision.limitKmh)
        assertTrue(abs(decision.excessKmh!! - 30.0) < 0.5, "got ${decision.excessKmh}")
        assertTrue(decision.distanceM > 0.0)
    }

    @Test
    fun `a notification-only decision is reported as silent`() {
        val notificationOnly =
            AlertProfile(
                voice = false,
                soundEnabled = false,
                beepEnabled = false,
                vibro = false,
                localNotifications = true,
            )
        val decision = AlertPolicy.evaluate(speeding, notificationOnly)!!
        assertEquals(setOf(AlertChannel.NOTIFICATION), decision.channels)
        assertTrue(decision.isSilent, "nothing audible is being asked for")
    }

    @Test
    fun `a voice decision is not silent`() {
        val voiceOnly =
            AlertProfile(
                voice = true,
                soundEnabled = false,
                beepEnabled = false,
                vibro = false,
                localNotifications = false,
            )
        assertFalse(AlertPolicy.evaluate(speeding, voiceOnly)!!.isSilent)
    }

    @Test
    fun `a profile with everything disabled produces nothing at all`() {
        assertNull(AlertPolicy.evaluate(speeding, everythingOff))
    }

    @Test
    fun `excess and limit are null together when the limit is unknown`() {
        val decision = AlertPolicy.evaluate(unknownLimit, AlertProfile())!!
        assertNull(decision.limitKmh)
        assertNull(decision.excessKmh)
    }
}
