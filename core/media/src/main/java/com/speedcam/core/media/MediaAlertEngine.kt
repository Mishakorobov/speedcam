package com.speedcam.core.media

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.speedcam.core.detect.AlertChannel
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.Hazard

/**
 * Turns an [AlertDecision] into sound, speech and vibration.
 *
 * This is where the pieces from §7 meet: the cadence latch, the output routing, the call
 * suppression, the tone generator, the sound bank and the speech plan. Every decision about
 * *what* to do is made by pure code elsewhere and unit-tested; this class performs the side
 * effects.
 *
 * Nothing here decides policy. Given the same inputs it always does the same thing, and the
 * interesting failures — a tone played through HFP, an alert repeating every fix, an alert
 * overwriting a phone call — are all caught in unit tests rather than on a motorway.
 */
class MediaAlertEngine(
    context: Context,
    outputMode: OutputMode = OutputMode.STANDARD,
    /** The user's sound-ducking setting; gates the focus request as in the original. */
    private val duckingEnabled: Boolean = true,
) {
    private val applicationContext = context.applicationContext
    private val audioManager: AudioManager? =
        runCatching { applicationContext.getSystemService(AudioManager::class.java) }.getOrNull()

    private val gager = GagerPlayer()
    private val bank = SoundBank()
    private val vibration = VibrationEngine(applicationContext)

    private var focusRequest: AudioFocusRequest? = null
    private var cadence: AlertCadenceState = AlertCadence.initial()

    @Volatile
    private var callState = CallSuppressionState()

    /** The output mode alerts are currently routed through. */
    @Volatile
    var mode: OutputMode = outputMode
        private set

    /** Reports the current call and microphone state. Refreshed by the caller each fix. */
    fun updateCallState(
        audioMode: Int,
        microphoneInUse: Boolean,
        silentDuringCall: Boolean,
    ) {
        callState =
            CallSuppressionState(
                audioMode = audioMode,
                microphoneInUse = microphoneInUse,
                silentDuringCall = silentDuringCall,
            )
    }

    fun setOutputMode(mode: OutputMode) {
        this.mode = mode
        bank.load(applicationContext, mode)
    }

    /** Preloads the bank. Safe to call once at startup; cheap when the bank is empty. */
    fun warmUp() {
        bank.load(applicationContext, mode)
    }

    /**
     * Plays one alert.
     *
     * @param decision what the alert policy asked for.
     * @param hazard the hazard it refers to, needed for the spoken wording.
     * @param isPrimary whether this is the nearest hazard of the current tick.
     * @param nowMillis the current time, for the cadence latch.
     */
    fun play(
        decision: AlertDecision,
        hazard: Hazard,
        isPrimary: Boolean,
        nowMillis: Long,
    ) {
        // The original drops the whole alert — sound, speech and vibration alike — when the
        // phone is busy. Reproduced rather than improved on.
        if (callState.suppresses()) {
            Log.d(TAG, "Call in progress; dropping the alert.")
            return
        }

        val wantsBeep = AlertChannel.BEEP in decision.channels && mode.allowsTones
        val wantsSound = AlertChannel.SOUND in decision.channels
        val wantsSpeech = AlertChannel.VOICE in decision.channels

        val outcome =
            AlertCadence.advance(
                state = cadence,
                nowMillis = nowMillis,
                request =
                    AlertRequest(
                        wantsSound = wantsSound,
                        wantsBeep = wantsBeep,
                        wantsSpeech = wantsSpeech,
                        playbackMillis = gager.durationMs(decision.distanceM),
                    ),
            )
        cadence = outcome.next

        if (outcome.sound && !bank.play(decision.soundId)) {
            // No clip for that id in this build. Rather than produce nothing audible, fall
            // back to a tone, which is the same choice the original makes when a recorded
            // voice pack is absent.
            Log.d(TAG, "Clip ${decision.soundId} is not in the bank; falling back to a tone.")
            playFallbackTone(decision)
        }

        if (outcome.beep) {
            gager.play(decision.distanceM, mode)
        }

        if (outcome.speech) {
            val plan = SpeechPlanner.plan(decision, hazard)
            Log.i(TAG, "Speech planned: ${plan.phrases.joinToString { it.name }}")
        }

        if (VibrationPolicy.shouldVibrate(AlertChannel.VIBRATION in decision.channels, isPrimary)) {
            vibration.vibrate()
        }
    }

    /**
     * Emits the tone that carries the alert when the bank has no clip for it.
     *
     * Pitched by distance so the driver still gets the rising approach cue, which is the part
     * that carries meaning.
     */
    private fun playFallbackTone(decision: AlertDecision) {
        if (!mode.allowsTones) return
        if (!decision.pitched && AlertChannel.BEEP !in decision.channels) return
        gager.play(decision.distanceM, mode)
    }

    fun release() {
        gager.release()
        bank.release()
        vibration.release()
        abandonFocus()
    }

    // --------------------------------------------------------------- audio focus

    /**
     * Requests focus for the alert window.
     *
     * The gain is `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` in all three of the original's engines
     * without exception. `setWillPauseWhenDucked` is deliberately *not* set, matching the
     * original: a navigation cue that goes quiet when the radio ducks is worse than one that
     * briefly overlaps it.
     */
    fun requestFocus(): Boolean {
        val manager = audioManager ?: return false
        if (!mode.shouldRequestFocus(duckingEnabled)) return false

        val request =
            focusRequest
                ?: AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(mode.audioAttributes())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                    .also { focusRequest = it }

        return runCatching { manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
            .getOrElse {
                Log.w(TAG, "Audio focus refused: ${it.message}")
                false
            }
    }

    fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
        focusRequest = null
    }

    private companion object {
        const val TAG = "MediaAlertEngine"
    }
}
