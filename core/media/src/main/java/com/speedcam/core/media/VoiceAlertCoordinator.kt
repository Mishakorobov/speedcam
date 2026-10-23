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

        if (engine == VoiceEngine.AUDIO_PACK) {
            val targetPackId =
                activePackId?.takeIf { it.isNotBlank() }
                    ?: packManager.listInstalledPacks().firstOrNull()?.id
                    ?: "elena_standard"
            val pack = packManager.getPack(targetPackId)
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
     * Uses local audio pack if available (or if [engine] is AUDIO_PACK),
     * otherwise falls back to speech synthesis (TTS).
     */
    fun speakTest(customText: String? = null) {
        stop()
        if (engine != VoiceEngine.DISABLED) {
            val targetPackId =
                activePackId?.takeIf { it.isNotBlank() }
                    ?: packManager.listInstalledPacks().firstOrNull()?.id
                    ?: "elena_standard"

            val pack =
                packManager.getPack(targetPackId)
                    ?: packManager.listInstalledPacks().firstOrNull()

            if (pack != null) {
                val testKeys =
                    if (phraseStyle == VoicePhraseStyle.FULL) {
                        listOf("attention", "dist_300", "cam_bus_lane", "oncoming", "limit_60")
                    } else {
                        listOf("cam_bus_lane", "limit_60", "oncoming", "dist_300")
                    }
                var clips = packManager.findClipsForKeys(pack.dir, testKeys)
                if (clips.isNullOrEmpty()) {
                    clips = packManager.findAvailableClips(pack.dir, testKeys)
                }
                if (clips.isNullOrEmpty()) {
                    clips =
                        pack.dir
                            .listFiles { f ->
                                f.isFile && f.extension.lowercase() in listOf("wav", "mp3", "ogg", "m4a")
                            }?.sortedBy { it.name }
                            ?.take(4) ?: emptyList()
                }

                if (!clips.isNullOrEmpty()) {
                    Log.d(TAG, "Playing test audio clips from pack '${pack.name}': ${clips.map { it.name }}")
                    packPlayer.playSequence(clips)
                    return
                }
                Log.d(TAG, "Audio pack '${pack.name}' has no clips; falling back to TTS")
            }
        }

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
