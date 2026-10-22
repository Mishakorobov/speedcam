package com.speedcam.core.media

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * High-performance speech synthesis player using the built-in Android Text-To-Speech engine.
 *
 * Configured for natural Russian pronunciation of automotive speed camera alerts.
 */
class TtsAlertPlayer(
    private val context: Context,
) : TextToSpeech.OnInitListener,
    AutoCloseable {
    private var tts: TextToSpeech? = null

    @Volatile
    var isReady: Boolean = false
        private set

    private val pendingQueue = mutableListOf<PendingSpeech>()
    private var currentMode: OutputMode = OutputMode.STANDARD

    data class PendingSpeech(
        val text: String,
        val rate: Float,
        val queueMode: Int,
    )

    init {
        initializeTts()
    }

    private fun initializeTts() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to instantiate Android TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                val ruLocale = Locale.forLanguageTag("ru-RU")
                val langResult = engine.setLanguage(ruLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Russian TTS data missing; attempting system default locale")
                    engine.setLanguage(Locale.getDefault())
                }
                applyAudioAttributes(currentMode)
                isReady = true

                synchronized(pendingQueue) {
                    for (pending in pendingQueue) {
                        speakInternal(pending.text, pending.rate, pending.queueMode)
                    }
                    pendingQueue.clear()
                }
            }
        } else {
            Log.w(TAG, "TextToSpeech initialization returned status $status")
        }
    }

    /**
     * Updates output routing audio attributes on the TTS engine.
     */
    fun setOutputMode(mode: OutputMode) {
        currentMode = mode
        if (isReady) {
            applyAudioAttributes(mode)
        }
    }

    private fun applyAudioAttributes(mode: OutputMode) {
        val engine = tts ?: return
        runCatching {
            val attrs =
                AudioAttributes
                    .Builder()
                    .setUsage(mode.usageRaw)
                    .setContentType(mode.contentTypeRaw)
                    .build()
            engine.setAudioAttributes(attrs)
        }
    }

    /**
     * Speaks the provided [text].
     */
    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ) {
        if (text.isBlank()) return

        if (!isReady) {
            synchronized(pendingQueue) {
                pendingQueue.add(PendingSpeech(text, speechRate, queueMode))
            }
            return
        }

        speakInternal(text, speechRate, queueMode)
    }

    private fun speakInternal(
        text: String,
        speechRate: Float,
        queueMode: Int,
    ) {
        val engine = tts ?: return
        try {
            engine.setSpeechRate(speechRate.coerceIn(0.75f, 1.6f))
            val params =
                Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
            val utteranceId = "speedcam_${System.currentTimeMillis()}"
            engine.speak(text, queueMode, params, utteranceId)
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing TTS speech for: $text", e)
        }
    }

    fun stop() {
        runCatching {
            tts?.stop()
        }
    }

    override fun close() {
        stop()
        runCatching {
            tts?.shutdown()
        }
        tts = null
        isReady = false
        synchronized(pendingQueue) {
            pendingQueue.clear()
        }
    }

    companion object {
        private const val TAG = "TtsAlertPlayer"
    }
}
