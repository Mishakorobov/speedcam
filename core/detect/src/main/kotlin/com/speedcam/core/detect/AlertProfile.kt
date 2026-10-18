package com.speedcam.core.detect

/** When a profile is allowed to warn. Ported from the original application. */
enum class WarnTime { ALWAYS, DAY, NIGHT }

/**
 * The complete warning policy.
 *
 * Ported from the original application's `DrivenProfile`. Every field here was recovered from the
 * decompiled Java layer, so this part of the design is known rather than inferred.
 */
data class AlertProfile(
    /**
     * Distance at which a camera starts to be announced, in kilometres.
     * `0.0` means "Auto" — resolved by [effectiveDistanceM] rather than by a hidden rule.
     */
    val captureDistanceKm: Double = 0.0,
    val voice: Boolean = true,
    val shortVoice: Boolean = false,
    val beepEnabled: Boolean = true,
    /**
     * Excess over the camera's posted limit, in km/h, above which the beep sounds.
     * `0` means "beep always", matching the original's `beep_excess_values` entry 0.
     *
     * This is an **excess**, not the absolute driving speed. That distinction was previously
     * documented here as absolute speed, which the decompile contradicts:
     *  - the array is named `beep_excess_values`, not `beep_speed_values`;
     *  - its Russian labels read `Бипер превышение выше %s` / `Гейгер превышение выше %s`
     *    ("beeper excess above N"), where `превышение` is the same word the neighbouring
     *    `op_warn_excess` setting uses;
     *  - the ladder's magnitudes (1, 3, 5, 8, 10, ... 50) are a natural scale for how far
     *    over a posted limit a driver is, not for whether they are moving at all.
     *
     * Getting this wrong makes the beeper fire as the driver approaches instead of as they
     * speed, which is a materially different behaviour.
     *
     * What could *not* be recovered: the comparison itself lives in `libVestigoEngines.so`.
     * The Java layer only stores the signed value into `DrivenProfile.beepId` and ships it
     * across JNI. So the *semantics* above are inferred from the array name and the localized
     * strings rather than read from code, and are worth revisiting if real-device behaviour
     * disagrees.
     *
     * The sign of the original's value also selected beeper versus gager; that axis is split
     * out here into [beepEnabled] and [pitchedTone], since the original encodes both the
     * threshold and the timbre in one field.
     */
    val beepExcessThresholdKmh: Int = 5,
    /**
     * True selects the distance-pitched "gager" tone; false selects a fixed beep.
     * Mirrors the sign convention of the original: negative `beepId` meant "gager".
     */
    val pitchedTone: Boolean = false,
    /**
     * Whether the alert sound bank is played at all.
     *
     * The original keeps this separate from [soundId]: `setSound(true)` with
     * `setSoundId(0..37)` is how a sound is chosen, and turning the bank off leaves a stale
     * id behind rather than clearing it — `PrefRadarDetectorFragment` does exactly that.
     *
     * Modelling only the id would conflate "muted" with "playing sound 0", which silently
     * turns three of the eight presets back into audible ones.
     */
    val soundEnabled: Boolean = true,
    /** Index into the alert sound bank, 0..37. */
    val soundId: Int = 0,
    /** Do not warn when current speed is below this, in km/h. 0 disables the check. */
    val warnSpeedLimitKmh: Int = 0,
    /** Do not warn when the excess over the limit is below this, in km/h. 0 disables the check. */
    val warnSpeedExcessKmh: Int = 0,
    val vibro: Boolean = true,
    val warnTime: WarnTime = WarnTime.ALWAYS,
    /**
     * When true, only warn for cameras facing the driver, suppressing oncoming-direction
     * cameras on the opposite carriageway. The original exposes this as "Warn back-shot".
     */
    val backshotOnly: Boolean = false,
    /**
     * Whether a background popup notification is raised when the app is not in the foreground.
     *
     * Named "Local notifications" in the original's Java, but its UI label is "Background
     * popup notifications" and it is serialised as `"alert"`. It gates the popup shown per
     * hazard and the ongoing foreground notification — not a notification of any kind.
     */
    val localNotifications: Boolean = true,
    /** Which vehicle this profile belongs to. See [TransportProfile]. */
    val transport: TransportProfile = TransportProfile.COMMON,
) {
    /**
     * The distance within which a camera is considered active, in metres.
     *
     * The original resolves `captureDistanceKm == 0.0` ("Auto") inside its native library by an
     * undocumented rule. This implementation states the rule explicitly instead: a floor, plus
     * a time budget that scales the warning distance with speed, bounded by a ceiling.
     *
     *     clamp(MIN_AUTO_M + speed * TIME_BUDGET_SEC, MIN_AUTO_M, MAX_AUTO_M)
     *
     * At 30 km/h that is 425 m, at 90 km/h 475 m, at 150 km/h 525 m.
     *
     * Note that [MAX_AUTO_M] is a safety guard rather than a working parameter: the time-budget
     * term only reaches it above roughly 1300 km/h. Long warning distances are reachable only
     * by setting [captureDistanceKm] explicitly, which is how the original application works
     * too — its "Auto" is a low-burden default, not the long-range setting.
     */
    fun effectiveDistanceM(speedMs: Double): Double {
        if (captureDistanceKm > 0.0) return captureDistanceKm * 1000.0
        return (MIN_AUTO_M + speedMs * TIME_BUDGET_SEC).coerceIn(MIN_AUTO_M, MAX_AUTO_M)
    }

    /**
     * Whether the under-speed rules allow a warning at all.
     *
     * Mirrors the original's "Do not warn if excess less" / "Do not warn if speed less" settings,
     * which together implement an under-speed filter. Both default to 0, meaning disabled.
     */
    fun isSuppressedBySpeed(
        currentKmh: Double,
        limitKmh: Int?,
    ): Boolean {
        val limit = limitKmh?.takeIf { it > 0 } ?: return false
        val drivingTooSlow = warnSpeedLimitKmh > 0 && currentKmh < warnSpeedLimitKmh
        val excessTooSmall = warnSpeedExcessKmh > 0 && currentKmh - limit < warnSpeedExcessKmh
        return drivingTooSlow || excessTooSmall
    }

    companion object {
        /** Floor applied to the automatic distance, in metres. */
        const val MIN_AUTO_M = 400.0

        /**
         * Warning time budget used by the automatic distance, in seconds.
         *
         * This is the real content of the "Auto" rule: how much notice the driver gets.
         */
        const val TIME_BUDGET_SEC = 3.0

        /**
         * Upper bound applied to the automatic distance, in metres.
         *
         * A guard rather than a working value. The time-budget term reaches it only above
         * roughly 1300 km/h, so in practice [MIN_AUTO_M] and the budget do the work.
         */
        const val MAX_AUTO_M = 1_500.0
    }
}

/**
 * The eight named presets.
 *
 * The eight *names* are verbatim from the original: `quick_setting_title_0..7` in its
 * `strings.xml` read "Maximum alerts", "Moderate", "Quiet", "Silent", "Melodic", "Signal",
 * "Visual only" and "Quiet city", and nothing else is offered.
 *
 * The *field assignments* are not recovered, and the original's own subtitles say why they
 * cannot be: each preset is applied by `RadarDetectorEngine.nativeApplyQuickSetting(int, boolean)`,
 * a single native call, so nothing in the decompile states which flags it sets. What survives
 * is a one-line description per preset, which is what these values are derived from:
 *
 * ```
 * 0  Always voice. Always sounds. Beep when exceeding limit.
 * 1  Voice when exceeding limit. Always sounds. Beep when exceeding limit.
 * 2  Voice when exceeding limit. Essential sounds only. Beep when exceeding limit.
 * 3  Voice when exceeding limit. No sounds. No beeps.
 * 4  No voice. Always sounds. Beep when exceeding limit.
 * 5  No voice. No sounds. Beep when exceeding limit.
 * 6  No voice. No sounds. No beeps. Pop-up notifications only.
 * 7  Voice only for restrictions and extra control. Beep when exceeding limit.
 * ```
 *
 * "Essential sounds only" has no counterpart in the original's model, so `soundId` here means
 * "the quieter end of the bank" rather than any known restriction. Treat the numeric choices
 * as informed guesses and revisit them against the running app.
 */
enum class QuickSetting(
    val displayName: String,
) {
    MAXIMUM_ALERTS("Maximum alerts"),
    MODERATE("Moderate"),
    QUIET("Quiet"),
    SILENT("Silent"),
    MELODIC("Melodic"),
    SIGNAL("Signal"),
    VISUAL_ONLY("Visual only"),
    QUIET_CITY("Quiet city"),
    ;

    /**
     * The profile this preset installs.
     *
     * Each preset overrides only the channels it changes; everything else is inherited from
     * [base], so a user-supplied capture distance or warn-time survives a preset switch.
     */
    fun apply(base: AlertProfile = AlertProfile()): AlertProfile =
        when (this) {
            MAXIMUM_ALERTS -> base.copy(voice = true, soundId = 0, beepEnabled = true, shortVoice = false)
            MODERATE -> base.copy(voice = true, soundId = 0, beepEnabled = true, shortVoice = true)
            QUIET -> base.copy(voice = true, soundId = 1, beepEnabled = true, shortVoice = true)
            // "Voice when exceeding limit. No sounds. No beeps." soundEnabled has to be
            // cleared explicitly: sound 0 is a real clip, so leaving the bank enabled would
            // keep this preset audible.
            SILENT ->
                base.copy(
                    voice = true,
                    soundEnabled = false,
                    soundId = 0,
                    beepEnabled = false,
                    vibro = false,
                    shortVoice = true,
                )

            MELODIC -> base.copy(voice = false, soundId = 2, beepEnabled = true, vibro = true)

            // "No voice. No sounds. Beep when exceeding limit."
            SIGNAL ->
                base.copy(
                    voice = false,
                    soundEnabled = false,
                    soundId = 0,
                    beepEnabled = true,
                    vibro = false,
                )

            // "No voice. No sounds. No beeps. Pop-up notifications only." Both the bank and
            // the notification flag have to be forced here: this preset is *defined* by
            // leaving the popup as the only channel, so inheriting either from the base
            // profile would silently produce a preset with no output at all.
            VISUAL_ONLY ->
                base.copy(
                    voice = false,
                    soundEnabled = false,
                    soundId = 0,
                    beepEnabled = false,
                    vibro = false,
                    localNotifications = true,
                )

            QUIET_CITY -> base.copy(voice = true, soundId = 1, beepEnabled = true, vibro = false, shortVoice = true)
        }
}
