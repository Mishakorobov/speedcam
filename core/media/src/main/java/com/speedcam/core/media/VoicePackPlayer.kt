package com.speedcam.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Sequential player for multi-clip audio phrase sequences.
 */
class VoicePackPlayer(
    private val context: Context,
) : AutoCloseable {
    private var mediaPlayer: MediaPlayer? = null
    private var playlist: List<File> = emptyList()
    private var currentIndex = 0
    private var currentMode: OutputMode = OutputMode.STANDARD

    fun setOutputMode(mode: OutputMode) {
        currentMode = mode
    }

    /**
     * Plays the list of audio [files] back-to-back in order.
     */
    @Synchronized
    fun playSequence(files: List<File>) {
        stop()
        if (files.isEmpty()) return

        playlist = files
        currentIndex = 0
        playNextClip()
    }

    private fun playNextClip() {
        if (currentIndex >= playlist.size) {
            stop()
            return
        }

        val file = playlist[currentIndex]
        currentIndex++

        try {
            val player =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(currentMode.usageRaw)
                            .setContentType(currentMode.contentTypeRaw)
                            .build(),
                    )
                    setDataSource(context, Uri.fromFile(file))
                    setOnCompletionListener {
                        it.release()
                        if (mediaPlayer === it) {
                            mediaPlayer = null
                        }
                        playNextClip()
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.w(TAG, "MediaPlayer error ($what, $extra) playing ${file.name}")
                        mp.release()
                        if (mediaPlayer === mp) {
                            mediaPlayer = null
                        }
                        false
                    }
                    prepare()
                    start()
                }
            mediaPlayer = player
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to play clip ${file.name}", e)
            playNextClip()
        }
    }

    @Synchronized
    fun stop() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        playlist = emptyList()
        currentIndex = 0
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val TAG = "VoicePackPlayer"
    }
}
