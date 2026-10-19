package com.speedcam.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log

/**
 * The alert sound bank.
 *
 * The original ships 38 clips (`res/raw/sound_alert_0..37`) plus a fixed beep at id 255, and
 * builds a `SoundPool` with five streams. Two things about that are not reproduced here:
 *
 *  - **The clips are not ours.** They are the original application's audio, and shipping them
 *    would be the same problem as shipping their `map.dat`. The bank loads whatever
 *    `sound_alert_*` resources a build actually contains and degrades to silence when there
 *    are none, which is the case for this build.
 *  - **The SoundPool is dead code in the original anyway.** Its `useSoundPool` flag is never
 *    assigned, so every clip plays through ExoPlayer instead. `SoundPool` is still the right
 *    choice here: these are short, overlapping, latency-sensitive clips, which is precisely
 *    what it is for, and it costs one dependency instead of a media3 player.
 *
 * Playback therefore depends on the bank being populated. [isLoaded] says whether it is, and
 * the caller falls back to the synthesised tones rather than silently producing nothing.
 */
class SoundBank(
    private val maxStreams: Int = MAX_STREAMS,
) {
    private var pool: SoundPool? = null
    private val soundIds = HashMap<Int, Int>()

    @Volatile
    var loadedCount: Int = 0
        private set

    /**
     * Loads `sound_alert_0` .. `sound_alert_(count-1)`, plus the fixed beep.
     *
     * A missing resource is skipped rather than fatal: a partial bank is still useful, and
     * this build ships none of them.
     */
    fun load(
        context: Context,
        mode: OutputMode,
        count: Int = EXPECTED_CLIPS,
    ) {
        release()
        loadedCount = 0

        val created =
            runCatching {
                SoundPool
                    .Builder()
                    .setMaxStreams(maxStreams)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(mode.usageRaw)
                            .setContentType(mode.contentTypeRaw)
                            .build(),
                    ).build()
            }.getOrNull()

        if (created == null) {
            Log.w(TAG, "Could not build a SoundPool; the bank will stay empty.")
            return
        }

        pool = created
        // Recorded even when no clip is found: "this build ships none" is a permanent
        // condition, not a reason to rebuild the pool on every alert.
        loaded = true
        for (id in 0 until count) {
            val resource = resourceFor(context, "sound_alert_$id")
            if (resource != NO_RESOURCE) {
                soundIds[id] = created.load(context, resource, 1)
            }
        }

        // Guarded, not `?.let`: resourceFor returns an Int and 0 means absent, and
        // SoundPool.load(context, 0, 1) throws Resources$NotFoundException rather than
        // returning a failure code. This build ships no clips, so this branch is the one
        // that runs on every start.
        val beep = resourceFor(context, BEEP_RESOURCE)
        if (beep != NO_RESOURCE) {
            soundIds[BEEP_ID] = created.load(context, beep, 1)
        }
    }

    /**
     * Plays a clip.
     *
     * Returns false when the id was never loaded, so the caller can fall back rather than
     * assume the driver heard something.
     */
    fun play(
        id: Int,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
    ): Boolean {
        val sample = soundIds[id] ?: return false
        val active = pool ?: return false
        return runCatching {
            active.play(sample, volume, volume, 1, 0, rate)
            true
        }.getOrElse {
            Log.w(TAG, "SoundPool refused clip $id: ${it.message}")
            false
        }
    }

    /** True when at least one clip is available, so callers know whether to fall back. */
    val isLoaded: Boolean get() = soundIds.isNotEmpty()

    /**
     * True when the bank is in a decided state, loaded or knowingly empty.
     *
     * Distinguishes "no clips shipped" from "never asked to load", which matters because a
     * build with no clips is a permanent condition and must not be retried on every alert.
     */
    val isLoadedOrEmpty: Boolean get() = loaded || soundIds.isNotEmpty()

    private var loaded = false

    fun release() {
        pool?.let { runCatching { it.release() } }
        pool = null
        soundIds.clear()
        loadedCount = 0
    }

    private fun resourceFor(
        context: Context,
        name: String,
    ): Int =
        runCatching {
            context.resources.getIdentifier(name, "raw", context.packageName)
        }.getOrDefault(NO_RESOURCE)

    companion object {
        private const val TAG = "SoundBank"

        /** `Resources.getIdentifier` returns 0 for a name that does not exist. */
        const val NO_RESOURCE = 0

        /** The original's `SoundPool.Builder().setMaxStreams(5)`. */
        const val MAX_STREAMS = 5

        /** `sound_alert_0..37`. */
        const val EXPECTED_CLIPS = 38

        /** The reserved id for the fixed beep, matching the original's `q(int)` map. */
        const val BEEP_ID = 255

        /** `beep.m4a` in the original, 423 ms, AAC-LC mono. */
        const val BEEP_RESOURCE = "beep"
    }
}
