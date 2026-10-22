package com.speedcam.core.media

/**
 * Speech synthesis / voice alert engine.
 */
enum class VoiceEngine {
    /** Built-in Android Text-To-Speech engine. Fully flexible, speaks all specific fixation types. */
    TTS,

    /** Pre-recorded audio clip packs from storage with automatic TTS fallback for missing clips. */
    AUDIO_PACK,

    /** Voice announcements disabled (beeps / tones only). */
    DISABLED,
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): VoiceEngine = entries.getOrElse(ordinal) { TTS }
    }
}

/**
 * Verbosity and wording style of spoken warnings.
 */
enum class VoicePhraseStyle {
    /** Full descriptive announcement: "Внимание! Через 300 метров контроль светофора. Ограничение 60 км/ч." */
    FULL,

    /** Concise, rapid announcement: "Светофор 60, через 300 метров". */
    SHORT,
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): VoicePhraseStyle = entries.getOrElse(ordinal) { FULL }
    }
}

/**
 * Granular camera fixation and enforcement classification for speech announcement.
 */
enum class FixationType {
    /** Fixed speed enforcement camera. */
    FIXED,

    /** Red-light, stop-line and intersection camera. */
    RED_LIGHT,

    /** Dedicated public transport / bus lane enforcement. */
    BUS_LANE,

    /** Road marking, solid line crossing, and lane discipline. */
    ROAD_MARKING,

    /** Mobile police radar / ambush. */
    MOBILE,

    /** Combined multi-purpose enforcement system. */
    COMPLEX,

    /** Entrance to an average speed enforcement zone. */
    AVERAGE_ENTRY,

    /** Exit from an average speed enforcement zone. */
    AVERAGE_EXIT,

    /** Dummy / decoy camera housing. */
    DUMMY,

    /** General road hazard or unclassified enforcement. */
    UNKNOWN,
}
