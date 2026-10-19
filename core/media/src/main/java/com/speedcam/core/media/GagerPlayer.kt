package com.speedcam.core.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the distance-pitched tone through [AudioTrack].
 *
 * The original's `ToneEngine` does exactly this: an 8 kHz, 16-bit mono stream written from a
 * generated buffer, with the pitch chosen by a ladder indexed on distance. The synthesis is
 * pure and lives in [GagerTones]; this class only moves bytes.
 */
class GagerPlayer {
    private var track: AudioTrack? = null

    /**
     * Renders and plays the tone for [distanceM].
     *
     * Returns false when the mode cannot carry a tone — see [OutputMode.allowsTones] — in
     * which case nothing is written, because HFP is a narrow-band voice path and a 1.3 kHz
     * tone through it is inaudible.
     */
    fun play(
        distanceM: Double,
        mode: OutputMode,
        volume: Float = DEFAULT_VOLUME,
    ): Boolean {
        if (!mode.allowsTones) {
            Log.d(TAG, "Output mode $mode cannot carry a tone; skipping the gager.")
            return false
        }

        val pcm = GagerTones.render(distanceM / 1000.0)
        return playPcm(pcm, distanceM, mode, volume)
    }

    /** How long the tone for [distanceM] would last, for the cadence latch. */
    fun durationMs(distanceM: Double): Long = GagerTones.durationMs(distanceM / 1000.0).toLong()

    private fun playPcm(
        pcm: ShortArray,
        distanceM: Double,
        mode: OutputMode,
        volume: Float,
    ): Boolean {
        release()

        val minBuffer =
            AudioTrack.getMinBufferSize(
                GagerTones.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        val created =
            runCatching {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(mode.audioAttributes())
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(GagerTones.SAMPLE_RATE_HZ)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    // MODE_STATIC: the whole tone is known up front, so it is written once
                    // and played. MODE_STREAM would keep a thread blocked on write().
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(maxOf(pcm.size * BYTES_PER_SAMPLE, minBuffer))
                    .build()
            }.getOrNull()

        if (created == null) {
            Log.w(TAG, "Could not create an AudioTrack for the gager.")
            return false
        }

        return try {
            created.write(pcm, 0, pcm.size)
            created.setVolume(volume)
            created.play()
            track = created
            Log.i(
                TAG,
                "Gager played: ${GagerTones.frequencyHz(distanceM / 1000.0)} Hz for " +
                    "${GagerTones.durationMs(distanceM / 1000.0)} ms (step " +
                    "${GagerTones.stepIndex(distanceM / 1000.0)}, ${pcm.size} samples) on $mode",
            )
            true
        } catch (e: IllegalStateException) {
            // The audio system is allowed to tear a track down between creation and play.
            Log.w(TAG, "AudioTrack rejected playback: ${e.message}")
            created.release()
            false
        }
    }

    fun release() {
        track?.let { existing ->
            runCatching {
                existing.stop()
                existing.release()
            }
            track = null
        }
    }

    private companion object {
        const val TAG = "GagerPlayer"
        const val DEFAULT_VOLUME = 1.0f
        const val BYTES_PER_SAMPLE = GagerTones.BITS_PER_SAMPLE / 8
    }
}

/** Builds the attributes for this output mode, mirroring the original's `SoundEngine.n()`. */
fun OutputMode.audioAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(usageRaw)
        .setContentType(contentTypeRaw)
        .build()

/**
 * The legacy stream type for this output mode.
 *
 * Carried only for the routing APIs that still take one. `STREAM_BLUETOOTH_SCO` is deprecated
 * from Android 12, so HFP maps to the voice-call stream and the SCO session is opened
 * separately, which is what [requiresBluetoothSco] is for.
 */
fun OutputMode.legacyStreamType(): Int =
    when (this) {
        OutputMode.ALWAYS_SPEAKER, OutputMode.BLUETOOTH_MULTIMEDIA, OutputMode.BLUETOOTH_HFP ->
            AudioManager.STREAM_VOICE_CALL

        OutputMode.SYSTEM -> AudioManager.STREAM_SYSTEM
        OutputMode.RING -> AudioManager.STREAM_RING
        OutputMode.ALARM -> AudioManager.STREAM_ALARM
        OutputMode.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }
