package com.speedcam.core.media

import android.content.Context
import android.util.Log
import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent

/**
 * High-level coordinator managing flexible speech synthesis (TTS) and
 * pre-recorded audio sound packs with automatic smart fallback.
 */
class VoiceAlertCoordinator(
    private val context: Context,
) : AutoCloseable {
    val ttsPlayer = TtsAlertPlayer(context)
    val packPlayer = VoicePackPlayer(context)
    val packManager = VoicePackManager(context)

    var engine: VoiceEngine = VoiceEngine.TTS
    var phraseStyle: VoicePhraseStyle = VoicePhraseStyle.FULL
    var speechRate: Float = 1.0f
    var activePackId: String? = null

    fun setOutputMode(mode: OutputMode) {
        ttsPlayer.setOutputMode(mode)
        packPlayer.setOutputMode(mode)
    }

    /**
     * Announces a speed camera / road hazard alert using the selected speech engine.
     */
    fun speakHazard(
        decision: AlertDecision,
        hazard: Hazard,
    ) {
        if (engine == VoiceEngine.DISABLED) return

        if (engine == VoiceEngine.AUDIO_PACK && !activePackId.isNullOrBlank()) {
            val pack = packManager.getPack(activePackId)
            if (pack != null) {
                val keys = VoicePhraseBuilder.getAudioClipKeys(decision, hazard, phraseStyle)
                val clips = packManager.findClipsForKeys(pack.dir, keys)
                if (clips != null && clips.isNotEmpty()) {
                    packPlayer.playSequence(clips)
                    return
                }
                Log.d(TAG, "Audio pack '${pack.name}' missing clips for $keys; falling back to TTS")
            }
        }

        // Standard or Fallback TTS speech
        val text = VoicePhraseBuilder.buildHazardText(decision, hazard, phraseStyle)
        ttsPlayer.speak(text, speechRate)
    }

    /**
     * Announces average-speed corridor entry or exit event.
     */
    fun speakSectionEvent(event: SectionEvent) {
        if (engine == VoiceEngine.DISABLED) return
        val text = VoicePhraseBuilder.buildSectionEventText(event, phraseStyle)
        if (text.isNotBlank()) {
            ttsPlayer.speak(text, speechRate)
        }
    }

    /**
     * Speaks a preview phrase for testing audio settings.
     */
    fun speakTest(customText: String? = null) {
        val testText =
            customText
                ?: if (phraseStyle == VoicePhraseStyle.FULL) {
                    "Внимание! Через 300 метров контроль полосы общественного транспорта в спину. Ограничение 60 километров в час."
                } else {
                    "Полоса ОТ 60 в спину, через 300 метров."
                }
        ttsPlayer.speak(testText, speechRate)
    }

    fun stop() {
        ttsPlayer.stop()
        packPlayer.stop()
    }

    fun release() {
        close()
    }

    override fun close() {
        ttsPlayer.close()
        packPlayer.close()
    }

    companion object {
        private const val TAG = "VoiceAlertCoordinator"
    }
}
