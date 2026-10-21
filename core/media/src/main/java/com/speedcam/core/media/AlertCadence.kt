package com.speedcam.core.media

/**
 * Decides how often an alert may actually make a sound.
 *
 * Without this the service re-announces the same camera on every location fix. At the 1 Hz
 * cadence the fused provider delivers indoors, a driver approaching a single camera would
 * hear it dozens of times, which is not a feature and not survivable.
 *
 * The original solves it with a single static boolean, `AudioEngine.e`, initialised `true`:
 *
 * ```
 * if (!latched) { if (soundId < 0 || stillSpeaking) return; playSound(soundId); return; }
 * latched = false
 * post({ speak(); latched = true }, SOUND_INTERVAL_MS)
 * ```
 *
 * with the re-arm delay being 1200 ms when a sound id is present, otherwise the length of
 * whatever was played, and 0 while speech is still running. Once latched low, only a bare
 * sound id still plays — the beep and the speech are skipped for the rest of the window.
 *
 * That shape is reproduced here as a value rather than as mutable state, so that the whole
 * timing rule is testable without a clock and without threads.
 */
data class AlertCadenceState(
    /** Whether the latch is currently open. Starts open, as the original's static does. */
    val latched: Boolean = false,
    /** When the latch re-opens, in the caller's millisecond clock. */
    val reopensAtMillis: Long = 0L,
)

/** What the caller would like to play, before the latch is consulted. */
data class AlertRequest(
    val wantsSound: Boolean,
    val wantsBeep: Boolean,
    val wantsSpeech: Boolean,
    val wantsVibro: Boolean = false,
    /** How long the sound will last, used when the alert carries no bank sound. */
    val playbackMillis: Long = AlertCadence.MIN_INTERVAL_MS,
)

/** What the caller should actually play for one alert. */
data class CadenceOutcome(
    val sound: Boolean,
    val beep: Boolean,
    val speech: Boolean,
    val vibro: Boolean = false,
    /** The next state to feed back in. */
    val next: AlertCadenceState,
)

object AlertCadence {
    /**
     * Re-arm delay when a sound from the bank is part of the alert.
     *
     * The original's constant `1200L`, and it is the *sound* path's delay, not a general one.
     */
    const val SOUND_INTERVAL_MS = 1_200L

    /**
     * The minimum gap between announcements for one camera.
     *
     * This is a policy choice rather than a recovered value: the original has no per-camera
     * cooldown at all, leaving the cadence to its native core, and that core is not
     * decompilable. Rather than fire on every fix, the latch is generalised from the one
     * boolean that *is* visible in Java.
     *
     * Too short and a single camera becomes a stutter; too long and a genuinely new camera
     * behind is missed because the latch is still closed. 1200 ms is the interval the
     * original already uses for its own playback window, and a fix cadence of 1 Hz means it
     * drops roughly every other announcement - which is what a rising tone wants, since the
     * pitch itself carries the approach.
     */
    const val MIN_INTERVAL_MS = SOUND_INTERVAL_MS

    /**
     * Advances the latch for one alert.
     *
     * @param state the state carried from the previous alert.
     * @param nowMillis the current time in the caller's clock.
     */
    fun advance(
        state: AlertCadenceState,
        nowMillis: Long,
        request: AlertRequest,
    ): CadenceOutcome {
        val open = !state.latched || nowMillis >= state.reopensAtMillis

        // While the latch is closed only a bare bank sound gets through, exactly as in the
        // original: the tone would otherwise retrigger on every fix and the speech would
        // stutter mid-phrase.
        if (!open) {
            return CadenceOutcome(
                sound = request.wantsSound,
                beep = false,
                speech = false,
                vibro = false,
                next = state,
            )
        }

        val duration =
            if (request.wantsSound) {
                SOUND_INTERVAL_MS
            } else {
                request.playbackMillis.coerceAtLeast(MIN_INTERVAL_MS)
            }

        return CadenceOutcome(
            sound = request.wantsSound,
            beep = request.wantsBeep,
            speech = request.wantsSpeech,
            vibro = request.wantsVibro,
            next = AlertCadenceState(latched = true, reopensAtMillis = nowMillis + duration),
        )
    }

    /** The initial state: open. */
    fun initial(): AlertCadenceState = AlertCadenceState()
}
