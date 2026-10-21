package com.speedcam.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the pure half of the media stack: where sound goes, when it is silenced, and what is
 * said.
 *
 * None of this needs a device, which is the point of keeping it out of `MediaAlertEngine`.
 * Everything the original decides in `AudioEngine`, `SoundEngine`, `ToneEngine` and the native
 * core is testable here as a value, so the parts that would otherwise need a car to check can
 * be checked at a desk.
 */
class MediaPolicyTest {
    // ------------------------------------------------------------------ routing

    @Test
    fun `HFP cannot carry a tone`() {
        // The original returns zero from both the fixed beep and the gager in this mode, on the
        // grounds that a 1.3 kHz tone through a narrow-band voice path is inaudible.
        assertFalse(OutputMode.BLUETOOTH_HFP.allowsTones)
    }

    @Test
    fun `every other mode can carry a tone`() {
        OutputMode.entries.filter { it != OutputMode.BLUETOOTH_HFP }.forEach {
            assertTrue(it.allowsTones, "$it must be able to produce a tone")
        }
    }

    @Test
    fun `only the two Bluetooth modes open a SCO session`() {
        assertTrue(OutputMode.BLUETOOTH_MULTIMEDIA.requiresBluetoothSco)
        assertTrue(OutputMode.BLUETOOTH_HFP.requiresBluetoothSco)
        assertFalse(OutputMode.STANDARD.requiresBluetoothSco)
        assertFalse(OutputMode.HEADRESTS.requiresBluetoothSco)
    }

    @Test
    fun `only always-speaker forces the speaker`() {
        assertTrue(OutputMode.ALWAYS_SPEAKER.forcesSpeaker)
        OutputMode.entries.filter { it != OutputMode.ALWAYS_SPEAKER }.forEach {
            assertFalse(it.forcesSpeaker, "$it must leave routing to the system")
        }
    }

    @Test
    fun `an unknown ordinal falls back to standard rather than throwing`() {
        assertEquals(OutputMode.STANDARD, OutputMode.fromOrdinal(-1))
        assertEquals(OutputMode.STANDARD, OutputMode.fromOrdinal(99))
        assertEquals(OutputMode.BLUETOOTH_HFP, OutputMode.fromOrdinal(3))
    }

    @Test
    fun `focus is requested for those three modes regardless of the ducking setting`() {
        // The original gates the request on the ducking flag *or* one of modes 9, 10, 11.
        listOf(OutputMode.ANDROID_AUTO, OutputMode.NAVIGATION, OutputMode.HEADRESTS).forEach {
            assertTrue(it.shouldRequestFocus(duckingEnabled = false), "$it must request focus")
        }
        assertFalse(OutputMode.STANDARD.shouldRequestFocus(duckingEnabled = false))
        assertTrue(OutputMode.STANDARD.shouldRequestFocus(duckingEnabled = true))
    }

    @Test
    fun `the standard mode uses media usage and sonification content`() {
        assertEquals(1, OutputMode.STANDARD.usageRaw)
        assertEquals(2, OutputMode.STANDARD.contentTypeRaw)
    }

    @Test
    fun `the alarm mode uses the alarm usage`() {
        assertEquals(4, OutputMode.ALARM.usageRaw)
    }

    // --------------------------------------------------------------- call state

    @Test
    fun `a call suppresses the alert only when the setting is on`() {
        val inCall =
            CallSuppressionState(
                audioMode = android.media.AudioManager.MODE_IN_CALL,
                silentDuringCall = false,
            )
        assertFalse(inCall.suppresses(), "the original defaults this setting to off")

        assertTrue(inCall.copy(silentDuringCall = true).suppresses())
    }

    @Test
    fun `normal audio mode with no microphone is not suppressed`() {
        val normal =
            CallSuppressionState(
                audioMode = android.media.AudioManager.MODE_NORMAL,
                silentDuringCall = true,
            )
        assertFalse(normal.suppresses())
    }

    @Test
    fun `a microphone in use suppresses even in normal mode`() {
        // A recording app holding the mic is the case the original's third clause covers.
        val recording =
            CallSuppressionState(
                audioMode = android.media.AudioManager.MODE_NORMAL,
                microphoneInUse = true,
                silentDuringCall = true,
            )
        assertTrue(recording.suppresses())
    }

    @Test
    fun `communication mode suppresses`() {
        val onComms =
            CallSuppressionState(
                audioMode = android.media.AudioManager.MODE_IN_COMMUNICATION,
                silentDuringCall = true,
            )
        assertTrue(onComms.suppresses())
    }

    // ---------------------------------------------------------------- vibration

    @Test
    fun `only the nearest hazard vibrates`() {
        // The original vibrates for the primary hazard alone; every hazard would be a
        // continuous buzz through a corridor.
        assertTrue(VibrationPolicy.shouldVibrate(enabledInProfile = true, isPrimary = true))
        assertFalse(VibrationPolicy.shouldVibrate(enabledInProfile = true, isPrimary = false))
    }

    @Test
    fun `nothing vibrates when the profile has it off`() {
        assertFalse(VibrationPolicy.shouldVibrate(enabledInProfile = false, isPrimary = true))
    }

    @Test
    fun `the vibration is the original's single five hundred millisecond pulse`() {
        assertEquals(500L, VibrationPolicy.DURATION_MS)
        assertEquals(-1, VibrationPolicy.DEFAULT_AMPLITUDE)
    }

    // ------------------------------------------------------------------- speech

    @Test
    fun `distance below one kilometre is announced in metres`() {
        val spoken = DistancePhrases.describe(500.0)
        assertEquals(listOf(Phrase.FIVE_HUNDRED_METERS, Phrase.METERS), spoken)
    }

    @Test
    fun `distance rounds to the nearest hundred rather than truncating`() {
        // 1196 m is nearer 1200 than 1100; saying "1100 metres" would be a number the driver
        // did not get from us. Twelve hundreds is "one point two kilometres".
        assertEquals(Phrase.ONE_POINT_TWO_KILOMETERS, DistancePhrases.describe(1_196.0).first())
        assertEquals(Phrase.TWO_HUNDRED_METERS, DistancePhrases.describe(249.0).first())
    }

    @Test
    fun `one kilometre switches to the kilometre vocabulary`() {
        val spoken = DistancePhrases.describe(1_000.0)
        assertEquals(listOf(Phrase.ONE_KILOMETER, Phrase.METERS), spoken)
    }

    @Test
    fun `two kilometres and a bit is still in the tenths vocabulary`() {
        val spoken = DistancePhrases.describe(2_300.0)
        assertEquals(Phrase.TWO_KILOMETERS, spoken.first())
        assertEquals(Phrase.KILOMETERS, spoken.last())
    }

    @Test
    fun `a long distance uses whole kilometres`() {
        assertEquals(listOf(Phrase.FIVE_KILOMETERS, Phrase.KILOMETERS), DistancePhrases.describe(5_000.0))
        assertEquals(listOf(Phrase.FIFTY_KILOMETERS, Phrase.KILOMETERS), DistancePhrases.describe(50_000.0))
    }

    @Test
    fun `an absurd distance still resolves to the furthest phrase`() {
        val spoken = DistancePhrases.describe(900_000.0)
        assertEquals(Phrase.FIFTY_KILOMETERS, spoken.first(), "nothing crashes on a far-fetched fix")
    }

    @Test
    fun `zero distance is announced rather than rejected`() {
        assertFalse(DistancePhrases.describe(0.0).isEmpty())
    }

    @Test
    fun `a negative distance is refused`() {
        var refused = false
        try {
            DistancePhrases.describe(-1.0)
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused, "a negative distance is a bug upstream, not a phrase to speak")
    }

    @Test
    fun `average speed zone entry announces the limit`() {
        val plan = SpeechPlanner.zoneEntered(90)
        assertEquals(listOf(Phrase.AVERAGE_SPEED_ZONE_ENTERED, Phrase.SPEED_LIMIT), plan.phrases)
        assertEquals(90, plan.limitKmh)
        assertFalse(plan.warnsExcess)
    }

    @Test
    fun `average speed zone exit announces the achieved average and warns on excess`() {
        val compliant = SpeechPlanner.zoneExited(averageKmh = 88.4, limitKmh = 90)
        assertEquals(listOf(Phrase.AVERAGE_SPEED_ZONE_EXIT), compliant.phrases)
        assertEquals(88, compliant.achievedAverageKmh)
        assertEquals(90, compliant.limitKmh)
        assertFalse(compliant.warnsExcess)

        val speeding = SpeechPlanner.zoneExited(averageKmh = 105.2, limitKmh = 90)
        assertEquals(listOf(Phrase.AVERAGE_SPEED_ZONE_EXIT, Phrase.DECREASE_SPEED), speeding.phrases)
        assertEquals(105, speeding.achievedAverageKmh)
        assertTrue(speeding.warnsExcess)
    }
}
