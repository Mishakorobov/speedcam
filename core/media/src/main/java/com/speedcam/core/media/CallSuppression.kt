package com.speedcam.core.media

import android.media.AudioManager

/**
 * Decides whether an alert is dropped entirely because the phone is busy.
 *
 * The original's `AudioEngine.s(Context)`:
 *
 * ```
 * if (!silentDuringCall) return false;
 * return audioManager.getMode() == 2 || audioManager.getMode() == 3
 *     || !audioManager.getActiveRecordingConfigurations().isEmpty();
 * ```
 *
 * and both alert entry points bail out *before anything at all*, including vibration and the
 * fixed beep. There is no queueing for later and no visual substitute. Whether that is right is
 * arguable - a driver deserves to know about a camera during a call - but it is what the
 * original does, and the point of a port is to be faithful about behaviour that is safety
 * relevant rather than to quietly improve on it.
 *
 * Wrapped as a value so the decision is testable without an `AudioManager`.
 */
data class CallSuppressionState(
    /** `AudioManager.getMode()`. */
    val audioMode: Int = AudioManager.MODE_NORMAL,
    /** True when anything is recording — a call, a voice memo, another app. */
    val microphoneInUse: Boolean = false,
    /** The user's `silent_during_call` setting. Defaults to false, as in the original. */
    val silentDuringCall: Boolean = false,
) {
    /**
     * True when the whole alert is dropped: no sound, no vibration, no notification.
     *
     * Note the third clause. The original also silences when the microphone is in use even if
     * the audio mode is normal, which covers a recording app or another app holding the mic.
     */
    fun suppresses(): Boolean =
        silentDuringCall &&
            (
                audioMode == AudioManager.MODE_IN_CALL ||
                    audioMode == AudioManager.MODE_IN_COMMUNICATION ||
                    microphoneInUse
            )
}

/**
 * The original applies the *fixed beep* to any camera, but vibration only to the first hazard
 * in the array, and to the speedometer. Vibration on every hazard is a continuous buzz as the
 * driver passes a corridor of them.
 */
object VibrationPolicy {
    /** One-shot length from the original's `VibrationEffect.createOneShot(500L, -1)`. */
    const val DURATION_MS = 500L

    /**
     * Whether this alert should vibrate.
     *
     * @param isPrimary whether this is the nearest hazard of the current tick.
     */
    fun shouldVibrate(
        enabledInProfile: Boolean,
        isPrimary: Boolean,
    ): Boolean = enabledInProfile && isPrimary

    /** Default amplitude, `-1`, meaning the device's own default for a one-shot. */
    const val DEFAULT_AMPLITUDE = -1
}
