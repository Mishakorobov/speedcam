package com.speedcam.core.media

/**
 * Where an alert should be heard.
 *
 * The original exposes twelve "desired output" modes (`R.array.desired_output`), persisted as
 * an int, and every audio engine maps its own settings off it — `SoundPool`/`ExoPlayer`
 * attributes, `AudioTrack` stream type, and the TTS stream. Three modes matter for correctness
 * rather than preference:
 *
 *  - **3, HFP.** Both the fixed beep and the generated gager return zero and play nothing.
 *    HFP is a narrow-band voice path; a 1.3 kHz tone through it is inaudible, and the
 *    original declines to try rather than emitting something the driver cannot hear.
 *  - **2 and 3, Bluetooth multimedia and voice.** Require `startBluetoothSco()`, which is
 *    torn down 2 s after the queue drains.
 *  - **1, always speaker.** Turns the speaker on even with a headset attached, so a driver
 *    who asks for it hears the alert from the car rather than through a device that may be
 *    in a pocket.
 *
 * Note that this is *not* the same thing as [com.speedcam.core.detect.TransportProfile].
 * That one chooses which warning profile applies; this one chooses where the sound goes. A
 * driver can be in the Car profile with Standard output.
 */
enum class OutputMode(
    val usageRaw: Int,
    val contentTypeRaw: Int,
) {
    /** Ordinary media output. The default. */
    STANDARD(1, 2),

    /** Always the phone speaker, even with a headset attached. */
    ALWAYS_SPEAKER(2, 1),

    /** Bluetooth multimedia. Needs SCO. */
    BLUETOOTH_MULTIMEDIA(2, 1),

    /**
     * Bluetooth voice (HFP).
     *
     * Mutes both the fixed beep and the gager: see [allowsTones].
     */
    BLUETOOTH_HFP(2, 1),

    /** Follow the system's own routing. */
    SYSTEM(1, 2),

    /** Ring stream. */
    RING(6, 2),

    /** Multimedia stream. */
    MULTIMEDIA(1, 2),

    /** Alarm stream. Loud, and routed around the media volume control. */
    ALARM(4, 2),

    /** Notification stream. */
    NOTIFICATION(5, 2),

    /** Android Auto. */
    ANDROID_AUTO(12, 1),

    /** Navigation guidance. */
    NAVIGATION(12, 2),

    /** Headrest output. */
    HEADRESTS(12, 1),
    ;

    /** True when a Bluetooth SCO session must be opened to be heard. */
    val requiresBluetoothSco: Boolean
        get() = this == BLUETOOTH_MULTIMEDIA || this == BLUETOOTH_HFP

    /** True when the speaker must be forced on rather than left to the system. */
    val forcesSpeaker: Boolean
        get() = this == ALWAYS_SPEAKER

    /**
     * Whether the fixed beep and the generated gager can be heard at all here.
     *
     * False for HFP only. The original returns zero from both `SoundEngine.C()` and
     * `ToneEngine.k()` in that mode rather than playing a tone the driver would not receive.
     */
    val allowsTones: Boolean
        get() = this != BLUETOOTH_HFP

    /** Audio focus is requested unconditionally for these, per the original's gating. */
    val requiresUnconditionalFocus: Boolean
        get() = this == ANDROID_AUTO || this == NAVIGATION || this == HEADRESTS

    companion object {
        fun fromOrdinal(ordinal: Int): OutputMode = entries.getOrElse(ordinal) { STANDARD }
    }
}

/**
 * Whether audio focus is requested for this alert.
 *
 * The original gates the request on a sound-ducking setting and the output mode; the gain is
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` in all three engines without exception.
 */
fun OutputMode.shouldRequestFocus(duckingEnabled: Boolean): Boolean = duckingEnabled || requiresUnconditionalFocus

/**
 * How long to hold audio focus after the last sound finishes.
 *
 * The original abandons focus 1900 ms after its queue drains and stops SCO 2000 ms after.
 * Kept as two values because they are two different resources and tearing one down before the
 * other is audible as a click on some headsets.
 */
object AudioFocusTimings {
    const val ABANDON_FOCUS_DELAY_MS = 1_900L
    const val STOP_SCO_DELAY_MS = 2_000L
}
