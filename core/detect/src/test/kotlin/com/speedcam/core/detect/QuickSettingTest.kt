package com.speedcam.core.detect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the eight presets against the only description of them that survives.
 *
 * The original applies a preset through `nativeApplyQuickSetting(int, boolean)`, so no
 * decompiled Java states which flags it sets. What does survive is one subtitle per preset in
 * its `strings.xml`, reproduced in [QuickSetting]. These tests assert the assignments match
 * those sentences rather than merely that the code returns something.
 *
 * Each subtitle is checked as a sentence: "No voice", "No sounds", "No beeps" and "Pop-up
 * notifications only" are four independent claims, and getting any one of them wrong turns a
 * preset the driver chose into a preset that does something they did not ask for.
 */
class QuickSettingTest {
    /** "Always voice" / "No voice". */
    private fun claimsVoice(setting: QuickSetting): Boolean = setting.apply().voice

    /** "Always sounds" / "No sounds" / "Essential sounds only". */
    private fun claimsSound(setting: QuickSetting): Boolean = setting.apply().soundEnabled

    /** "Beep when exceeding limit" / "No beeps". */
    private fun claimsBeep(setting: QuickSetting): Boolean = setting.apply().beepEnabled

    /** "Pop-up notifications only". */
    private fun claimsNotification(setting: QuickSetting): Boolean = setting.apply().localNotifications

    // ---------------------------------------------------------------- the names

    @Test
    fun `there are exactly eight presets and the names are verbatim`() {
        assertEquals(8, QuickSetting.entries.size, "nativeGetQuickSettingsCount() returns 8")
        assertEquals(
            listOf(
                "Maximum alerts",
                "Moderate",
                "Quiet",
                "Silent",
                "Melodic",
                "Signal",
                "Visual only",
                "Quiet city",
            ),
            QuickSetting.entries.map { it.displayName },
        )
    }

    // ------------------------------------------------------------- per subtitle

    @Test
    fun `maximum alerts speaks, sounds and beeps`() {
        // "Always voice. Always sounds. Beep when exceeding limit."
        val setting = QuickSetting.MAXIMUM_ALERTS
        assertTrue(claimsVoice(setting))
        assertTrue(claimsSound(setting))
        assertTrue(claimsBeep(setting))
        assertFalse(setting.apply().shortVoice, "always voice, so not the short vocabulary")
    }

    @Test
    fun `moderate sounds and beeps but speaks only in short form`() {
        // "Voice when exceeding limit. Always sounds. Beep when exceeding limit."
        val setting = QuickSetting.MODERATE
        assertTrue(claimsVoice(setting))
        assertTrue(claimsSound(setting))
        assertTrue(claimsBeep(setting))
        assertTrue(setting.apply().shortVoice, "the gate is the excess, so the short form carries it")
    }

    @Test
    fun `quiet is the moderate preset with a different sound`() {
        // "Voice when exceeding limit. Essential sounds only. Beep when exceeding limit."
        val quiet = QuickSetting.QUIET.apply()
        val moderate = QuickSetting.MODERATE.apply()
        assertTrue(quiet.voice)
        assertTrue(quiet.beepEnabled)
        assertTrue(quiet.shortVoice)
        assertTrue(quiet.soundEnabled)
        assertFalse(
            quiet.soundId == moderate.soundId,
            "Quiet must pick a different sound from Moderate, otherwise the two are identical",
        )
    }

    @Test
    fun `silent speaks but makes no sound and no beep`() {
        // "Voice when exceeding limit. No sounds. No beeps."
        val setting = QuickSetting.SILENT
        assertTrue(claimsVoice(setting))
        assertFalse(claimsSound(setting), "sound 0 is a real clip, so the bank must be disabled")
        assertFalse(claimsBeep(setting))
        assertFalse(setting.apply().vibro, "a 'silent' preset that vibrates is not silent")
    }

    @Test
    fun `melodic drops the voice but keeps sound, beep and vibration`() {
        // "No voice. Always sounds. Beep when exceeding limit."
        val setting = QuickSetting.MELODIC
        assertFalse(claimsVoice(setting))
        assertTrue(claimsSound(setting))
        assertTrue(claimsBeep(setting))
        assertTrue(setting.apply().vibro)
    }

    @Test
    fun `signal is beep-only`() {
        // "No voice. No sounds. Beep when exceeding limit."
        val setting = QuickSetting.SIGNAL
        assertFalse(claimsVoice(setting))
        assertFalse(claimsSound(setting))
        assertTrue(claimsBeep(setting))
        assertFalse(setting.apply().vibro)
    }

    @Test
    fun `visual only leaves the popup as the single channel`() {
        // "No voice. No sounds. No beeps. Pop-up notifications only."
        val setting = QuickSetting.VISUAL_ONLY
        assertFalse(claimsVoice(setting))
        assertFalse(claimsSound(setting))
        assertFalse(claimsBeep(setting))
        assertFalse(setting.apply().vibro)
        assertTrue(claimsNotification(setting))
    }

    @Test
    fun `quiet city speaks and beeps without vibration`() {
        // "Voice only for restrictions and extra control. Beep when exceeding limit."
        val applied = QuickSetting.QUIET_CITY.apply()
        assertTrue(applied.voice)
        assertTrue(applied.beepEnabled)
        assertTrue(applied.soundEnabled)
        assertFalse(applied.vibro)
        assertTrue(applied.shortVoice)
    }

    // ------------------------------------------------------------- all eight, together

    @Test
    fun `no preset is silent when applied to a bare profile except the ones that mean it`() {
        // Every preset must produce at least one output. Visual only is the only preset whose
        // defining property is a single channel, and it must still produce that one.
        QuickSetting.entries.forEach { setting ->
            val applied = setting.apply()
            val anyOutput =
                applied.voice ||
                    applied.soundEnabled ||
                    applied.beepEnabled ||
                    applied.vibro ||
                    applied.localNotifications
            assertTrue(anyOutput, "$setting produced a profile that can never warn about anything")
        }
    }

    @Test
    fun `presets differ from one another`() {
        // Two presets producing the same profile would mean one of the eight is unreachable.
        val distinct = QuickSetting.entries.map { it.apply() }.toSet()
        assertEquals(QuickSetting.entries.size, distinct.size, "two presets resolve to the same profile")
    }

    // ------------------------------------------------------------------ inheritance

    @Test
    fun `a preset keeps the user's capture distance and warn time`() {
        // Presets change channels, not geometry or scheduling. Overwriting these would silently
        // move the warning point for a driver who had tuned it.
        val tuned =
            AlertProfile(
                captureDistanceKm = 1.5,
                warnTime = WarnTime.NIGHT,
                transport = TransportProfile.TRUCK,
            )
        QuickSetting.entries.forEach { setting ->
            val applied = setting.apply(tuned)
            assertEquals(1.5, applied.captureDistanceKm, "speedcam: $setting")
            assertEquals(WarnTime.NIGHT, applied.warnTime, "speedcam: $setting")
            assertEquals(TransportProfile.TRUCK, applied.transport, "speedcam: $setting")
        }
    }

    @Test
    fun `visual only forces its notification on even over a base that disables it`() {
        // The preset is defined by the popup being the only channel, so it has to override
        // rather than inherit: a base with notifications off would otherwise leave it mute.
        val base = AlertProfile(localNotifications = false)
        assertTrue(QuickSetting.VISUAL_ONLY.apply(base).localNotifications)
    }

    @Test
    fun `the default preset is maximum alerts`() {
        // SystemHelper calls nativeApplyQuickSetting(0, false) as the factory default.
        assertEquals(QuickSetting.MAXIMUM_ALERTS, QuickSetting.entries.first())
    }
}
