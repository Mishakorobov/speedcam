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
     * Minimum driving speed at which the proximity signal fires, in km/h.
     *
     * This is a **speed** threshold, not a distance. Verified against the original's
     * `beep_excess_values` array. Getting this wrong makes the app beep on approach rather
     * than on speeding, which is a materially different behaviour.
     */
    val beepSpeedThresholdKmh: Int = 5,
    /**
     * True selects the distance-pitched "gager" tone; false selects a fixed beep.
     * Mirrors the sign convention of the original: negative `beepId` meant "gager".
     */
    val pitchedTone: Boolean = false,
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
    val localNotifications: Boolean = true,
    /** ID of the owning transport profile: Common / Car / Truck / Moto. See Plan.md §6.2. */
    val transportId: Int = TRANSPORT_COMMON,
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
        const val TRANSPORT_COMMON = 0
        const val TRANSPORT_CAR = 1
        const val TRANSPORT_TRUCK = 2
        const val TRANSPORT_MOTO = 3

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

/** The eight named presets. Reproduced verbatim from the original application. */
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
            SILENT -> base.copy(voice = true, soundId = 0, beepEnabled = false, vibro = false, shortVoice = true)
            MELODIC -> base.copy(voice = false, soundId = 2, beepEnabled = true, vibro = true)
            SIGNAL -> base.copy(voice = false, soundId = 0, beepEnabled = true, vibro = false)
            VISUAL_ONLY -> base.copy(voice = false, soundId = 0, beepEnabled = false, vibro = false)
            QUIET_CITY -> base.copy(voice = true, soundId = 1, beepEnabled = true, vibro = false, shortVoice = true)
        }
}
