package com.speedcam.core.detect

/** An output the app can raise for a hazard. Mirrors the original's per-hazard flags. */
enum class AlertChannel {
    /** Spoken warning. */
    VOICE,

    /** A clip from the 38-sound alert bank. */
    SOUND,

    /** The fixed beep, or the distance-pitched gager when [AlertDecision.pitched]. */
    BEEP,

    VIBRATION,

    /** The background popup, raised only when the app is not in the foreground. */
    NOTIFICATION,
}

/**
 * Ambient conditions the profile's rules are evaluated against.
 *
 * Supplied by the caller rather than read from a clock, because [AlertPolicy] is pure: it
 * takes no decision that depends on when it happened to run.
 */
data class AlertContext(
    /**
     * Whether it is currently night.
     *
     * How this is decided is deliberately not this module's business. The original stores
     * `warnTime` as 0/1/2 for always/day/night and then evaluates it inside
     * `libVestigoEngines.so`; its decompile contains no sunrise or sunset calculation at all,
     * so whether it meant astronomical twilight, civil twilight or fixed hours is unknown.
     * Guessing here would bake in a wrong answer that is expensive to notice.
     */
    val isNight: Boolean = false,
)

/**
 * What to do about one hazard, and how loudly.
 *
 * Returned rather than acted upon, so that the policy stays pure and the presentation layer —
 * audio focus, Bluetooth routing, notification channels — decides how to produce it.
 */
data class AlertDecision(
    /** The outputs to raise. Never empty. */
    val channels: Set<AlertChannel>,
    /** Index into the 38-sound alert bank. Meaningless unless [AlertChannel.SOUND] is present. */
    val soundId: Int,
    /** True to use the distance-pitched gager instead of the fixed beep. */
    val pitched: Boolean,
    /** True to use the short phrase vocabulary. */
    val shortVoice: Boolean,
    /** Distance to the camera, carried through for the gager's pitch and for speech. */
    val distanceM: Double,
    /** The camera's posted limit, or null when the source recorded none. */
    val limitKmh: Int?,
    /**
     * `speedKmh - limitKmh`, or null when the limit is unknown.
     *
     * The two are null together by construction: an excess cannot exist without a limit.
     */
    val excessKmh: Double?,
) {
    /** True when nothing audible is being asked for. */
    val isSilent: Boolean
        get() = channels.none { it == AlertChannel.VOICE || it == AlertChannel.SOUND || it == AlertChannel.BEEP }
}

/**
 * Decides what a hazard should produce.
 *
 * [DetectionEngine] answers *whether* a camera warrants attention — geometry, facing
 * direction, and the under-speed rules. This answers *how* to announce it, which is where the
 * remaining half of [AlertProfile] lives: the channels to raise, which sound, the beep
 * threshold, and the time-of-day gate.
 *
 * Pure by construction, as the rest of `core:detect` is: no clock, no I/O, no Android types.
 */
object AlertPolicy {
    /**
     * Returns what to do about [hazard], or null when nothing should be said about it.
     *
     * Null means genuinely nothing, not "quietly": either the time-of-day gate forbids
     * warning at all, or every channel the profile allows is off. A decision is never
     * returned with an empty channel set, so a caller never has to check.
     */
    fun evaluate(
        hazard: Hazard,
        profile: AlertProfile,
        context: AlertContext = AlertContext(),
    ): AlertDecision? {
        if (!isWarnTimeSatisfied(profile.warnTime, context)) return null

        val minSpeed =
            if (profile.warnSpeedLimitKmh > 0) {
                profile.warnSpeedLimitKmh.toDouble()
            } else {
                DEFAULT_MIN_ALERT_SPEED_KMH
            }
        val isMovingAboveThreshold = hazard.speedKmh >= minSpeed

        val channels =
            buildSet {
                // Standing still or driving below threshold silences audible/haptic alerts completely.
                if (isMovingAboveThreshold) {
                    if (profile.voice) add(AlertChannel.VOICE)
                    if (profile.soundEnabled && profile.soundId in SOUND_BANK) add(AlertChannel.SOUND)
                    if (profile.vibro) add(AlertChannel.VIBRATION)
                    if (profile.beepEnabled && isBeepThresholdSatisfied(hazard, profile)) add(AlertChannel.BEEP)
                }
                if (profile.localNotifications) add(AlertChannel.NOTIFICATION)
            }

        if (channels.isEmpty()) return null

        return AlertDecision(
            channels = channels,
            soundId = profile.soundId,
            pitched = profile.pitchedTone,
            shortVoice = profile.shortVoice,
            distanceM = hazard.distanceM,
            limitKmh = hazard.camera.limitKmh,
            excessKmh = hazard.excessKmh,
        )
    }

    /**
     * Whether the profile permits warning at this time of day.
     *
     * The original's `warn_time_array_values` is literally 0/1/2 for always/day/night, which
     * is what [WarnTime]'s ordinals already encode.
     */
    fun isWarnTimeSatisfied(
        warnTime: WarnTime,
        context: AlertContext,
    ): Boolean =
        when (warnTime) {
            WarnTime.ALWAYS -> true
            WarnTime.DAY -> !context.isNight
            WarnTime.NIGHT -> context.isNight
        }

    /**
     * Whether the beep should sound for this hazard.
     *
     * The threshold is an excess over the posted limit, not an absolute speed: the original
     * names the array `beep_excess_values` and labels it `превышение` in Russian. See
     * [AlertProfile.beepExcessThresholdKmh] for how much of that is recovered and how much is
     * inferred from native code that was never decompiled.
     *
     * A threshold of `0` is the original's "Beeper always" entry.
     *
     * A camera with no recorded limit has no excess to exceed. The beep is allowed there
     * rather than suppressed: an unknown limit is not evidence of compliance, and silently
     * withholding the warning would be the dangerous direction to be wrong in.
     */
    fun isBeepThresholdSatisfied(
        hazard: Hazard,
        profile: AlertProfile,
    ): Boolean {
        if (hazard.speedKmh < DEFAULT_MIN_ALERT_SPEED_KMH) return false
        if (profile.beepExcessThresholdKmh <= 0) return true
        val excess = hazard.excessKmh ?: return true
        return excess > profile.beepExcessThresholdKmh
    }

    /** Minimum vehicle speed in km/h to trigger audible or haptic alerts (suppresses alerts while standing still). */
    const val DEFAULT_MIN_ALERT_SPEED_KMH = 5.0

    /**
     * Number of alert sounds in the bank.
     *
     * Confirmed from the original: `SoundEngine` maps indices 0..37 to `sound_alert_0..37`,
     * with the sentinel 255 reserved for the fixed beep. The wheel labels are 1-based while
     * the stored ids are 0-based, an off-by-one worth remembering when wiring up the UI.
     */
    const val SOUND_BANK_SIZE = 38

    private val SOUND_BANK = 0 until SOUND_BANK_SIZE
}
