package com.speedcam.core.media

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.speedcam.core.detect.AlertChannel
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent

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
    private val scoController = BluetoothScoController(applicationContext, audioManager)
    val voiceCoordinator = VoiceAlertCoordinator(applicationContext)

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

    /**
     * Points alerts at a different output route.
     *
     * A no-op when the mode has not changed. Rebuilding a `SoundPool` on every alert would be
     * pure waste at one alert per second, and the caller naturally asks on every fix.
     */
    fun setOutputMode(mode: OutputMode) {
        if (mode == this.mode && bank.isLoadedOrEmpty) return
        this.mode = mode
        voiceCoordinator.setOutputMode(mode)
        // A route that cannot be set up must not take the alert pipeline down with it.
        runCatching { bank.load(applicationContext, mode) }
            .onFailure { Log.w(TAG, "Could not load the sound bank: ${it.message}") }
    }

    /** Preloads the bank. Safe to call once at startup; cheap when the bank is empty. */
    fun warmUp() {
        runCatching { bank.load(applicationContext, mode) }
            .onFailure { Log.w(TAG, "Could not load the sound bank: ${it.message}") }
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
        if (callState.suppresses()) {
            Log.d(TAG, "Call in progress; dropping the alert.")
            return
        }

        prepareAudioRoute()

        val wantsBeep = AlertChannel.BEEP in decision.channels && mode.allowsTones
        val wantsSound = AlertChannel.SOUND in decision.channels
        val wantsSpeech = AlertChannel.VOICE in decision.channels
        val wantsVibro = VibrationPolicy.shouldVibrate(AlertChannel.VIBRATION in decision.channels, isPrimary)

        val outcome =
            AlertCadence.advance(
                state = cadence,
                nowMillis = nowMillis,
                request =
                    AlertRequest(
                        wantsSound = wantsSound,
                        wantsBeep = wantsBeep,
                        wantsSpeech = wantsSpeech,
                        wantsVibro = wantsVibro,
                        playbackMillis = gager.durationMs(decision.distanceM),
                    ),
            )
        cadence = outcome.next

        dispatchAlertOutputs(decision, hazard, outcome)
        scheduleRouteRelease(gager.durationMs(decision.distanceM).toLong())
    }

    private fun prepareAudioRoute() {
        if (mode.requiresBluetoothSco) {
            scoController.acquireSco()
        } else if (mode.forcesSpeaker) {
            runCatching { audioManager?.isSpeakerphoneOn = true }
        }
    }

    private fun scheduleRouteRelease(durationMs: Long) {
        if (mode.requiresBluetoothSco) {
            scoController.releaseSco(durationMs + BluetoothScoController.DEFAULT_SCO_TEARDOWN_DELAY_MS)
        }
    }

    private fun dispatchAlertOutputs(
        decision: AlertDecision,
        hazard: Hazard,
        outcome: CadenceOutcome,
    ) {
        val bankPlayed = if (outcome.sound) bank.play(decision.soundId) else false
        if (outcome.beep && !bankPlayed) {
            if (outcome.sound) Log.d(TAG, "Clip ${decision.soundId} is not in the bank; using a tone.")
            playFallbackTone(decision)
        }

        if (outcome.speech) {
            val plan = SpeechPlanner.plan(decision, hazard)
            Log.i(TAG, "Speech planned: ${plan.phrases.joinToString { it.name }}")
            voiceCoordinator.speakHazard(decision, hazard)
        }

        if (outcome.vibro) {
            vibration.vibrate()
        }
    }

    /**
     * Announces an average-speed corridor transition ([SectionEvent.Entered] or [SectionEvent.Exited]).
     *
     * Entry and exit are one-shot edge events rather than per-fix repeats, so they bypass the
     * approach cadence latch while still honouring call suppression, output routing, and the
     * profile's voice/beep/vibration switches.
     */
    fun playSectionEvent(
        event: SectionEvent,
        profile: AlertProfile,
    ) {
        if (callState.suppresses()) return

        val plan =
            when (event) {
                is SectionEvent.Entered -> SpeechPlanner.zoneEntered(event.section.limitKmh)
                is SectionEvent.Exited -> SpeechPlanner.zoneExited(event.averageKmh, event.section.limitKmh)
                else -> return
            }

        prepareAudioRoute()

        if (profile.voice) {
            Log.i(
                TAG,
                "Section speech planned: ${plan.phrases.joinToString { it.name }} " +
                    "(limit=${plan.limitKmh}, avg=${plan.achievedAverageKmh})",
            )
            voiceCoordinator.speakSectionEvent(event)
        }
        if (profile.beepEnabled && mode.allowsTones) {
            gager.play(0.0, mode)
        }
        if (profile.vibro) {
            vibration.vibrate()
        }
        scheduleRouteRelease(2500L)
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
        voiceCoordinator.release()
        scoController.forceStop()
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
