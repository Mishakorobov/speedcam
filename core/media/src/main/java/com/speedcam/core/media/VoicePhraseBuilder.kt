package com.speedcam.core.media

import com.speedcam.core.detect.AlertDecision
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent

/**
 * Natural Russian speech phrasing generator for TTS synthesis and audio pack lookup.
 *
 * Implements granular fixation wording:
 * - «Контроль полосы ОТ»
 * - «Контроль светофора»
 * - «Разметка»
 * - «Камера скорости» / «В спину» / «Снижайте скорость»
 */
object VoicePhraseBuilder {
    /** Excess threshold in km/h to trigger urgent "Снижайте скорость!" announcement. */
    const val EXCESS_THRESHOLD_KMH = 10.0

    /**
     * Resolves granular fixation type from [Camera] kind, ContraCam subType, or source detail.
     */
    fun resolveFixation(camera: Camera): FixationType {
        val detail = camera.sourceDetail?.lowercase() ?: ""

        if (camera.kind == CameraKind.BUS_LANE ||
            detail.contains("subtype=8") ||
            detail.contains("subtype=49") ||
            detail.contains("bus_lane")
        ) {
            return FixationType.BUS_LANE
        }
        if (camera.kind == CameraKind.ROAD_MARKING ||
            detail.contains("subtype=9") ||
            detail.contains("subtype=50") ||
            detail.contains("marking")
        ) {
            return FixationType.ROAD_MARKING
        }
        if (camera.kind == CameraKind.RED_LIGHT ||
            detail.contains("subtype=7") ||
            detail.contains("subtype=48")
        ) {
            return FixationType.RED_LIGHT
        }
        if (camera.kind == CameraKind.MOBILE ||
            detail.contains("subtype=5") ||
            detail.contains("subtype=46")
        ) {
            return FixationType.MOBILE
        }
        if (camera.kind == CameraKind.AVERAGE_ENTRY ||
            detail.contains("subtype=3") ||
            detail.contains("subtype=44")
        ) {
            return FixationType.AVERAGE_ENTRY
        }
        if (camera.kind == CameraKind.AVERAGE_EXIT ||
            detail.contains("subtype=4") ||
            detail.contains("subtype=45")
        ) {
            return FixationType.AVERAGE_EXIT
        }
        if (camera.kind == CameraKind.DUMMY ||
            detail.contains("subtype=6") ||
            detail.contains("subtype=47")
        ) {
            return FixationType.DUMMY
        }
        if (camera.kind == CameraKind.COMPLEX) {
            return FixationType.COMPLEX
        }
        if (camera.kind == CameraKind.FIXED) {
            return FixationType.FIXED
        }
        return FixationType.UNKNOWN
    }

    /**
     * Builds full or short natural spoken Russian text for TTS synthesis.
     */
    fun buildHazardText(
        decision: AlertDecision,
        hazard: Hazard,
        style: VoicePhraseStyle,
    ): String {
        val fixation = resolveFixation(hazard.camera)
        val fixationTitle = getFixationTitle(fixation, style)
        val distanceText = formatDistanceText(decision.distanceM, style)
        val directionText = if (hazard.isBehind) "в спину" else ""
        val limit = decision.limitKmh?.takeIf { it > 0 }
        val excess = decision.excessKmh ?: 0.0
        val warnsExcess = limit != null && excess >= EXCESS_THRESHOLD_KMH

        return when (style) {
            VoicePhraseStyle.FULL -> {
                buildString {
                    append("Внимание! ")
                    if (distanceText.isNotBlank()) append("$distanceText ")
                    append(fixationTitle)
                    if (directionText.isNotBlank()) append(" $directionText")
                    if (limit != null) append(". Ограничение $limit километров в час")
                    if (warnsExcess) append(". Снижайте скорость!")
                }.trim()
            }

            VoicePhraseStyle.SHORT -> {
                buildString {
                    append(fixationTitle)
                    if (limit != null) append(" $limit")
                    if (directionText.isNotBlank()) append(" $directionText")
                    if (distanceText.isNotBlank()) append(", $distanceText")
                    if (warnsExcess) append(". Снижайте скорость!")
                }.trim()
            }
        }
    }

    /**
     * Builds spoken Russian announcement for average speed corridor entry / exit events.
     */
    fun buildSectionEventText(
        event: SectionEvent,
        style: VoicePhraseStyle,
    ): String =
        when (event) {
            is SectionEvent.Entered -> {
                val limit = event.section.limitKmh
                when (style) {
                    VoicePhraseStyle.FULL ->
                        "Внимание! Начало зоны контроля средней скорости. Ограничение $limit километров в час."
                    VoicePhraseStyle.SHORT ->
                        "Зона средней скорости $limit."
                }
            }

            is SectionEvent.Exited -> {
                val avg = event.averageKmh.toInt()
                val limit = event.section.limitKmh
                val over = limit > 0 && (avg - limit) >= EXCESS_THRESHOLD_KMH
                when (style) {
                    VoicePhraseStyle.FULL -> {
                        val excessWarning = if (over) " Превышение скорости!" else ""
                        "Конец зоны контроля средней скорости. Средняя скорость $avg километров в час.$excessWarning"
                    }
                    VoicePhraseStyle.SHORT -> {
                        val excessWarning = if (over) " Превышение!" else ""
                        "Конец зоны, средняя $avg.$excessWarning"
                    }
                }
            }

            else -> ""
        }

    /**
     * Returns audio clip keys for matching recorded files inside audio packs.
     */
    fun getAudioClipKeys(
        decision: AlertDecision,
        hazard: Hazard,
        style: VoicePhraseStyle,
    ): List<String> {
        val keys = mutableListOf<String>()
        if (style == VoicePhraseStyle.FULL) {
            keys.add("attention")
        }

        val distKey = getDistanceKey(decision.distanceM)
        if (distKey != null) keys.add(distKey)

        val fixation = resolveFixation(hazard.camera)
        keys.add(getFixationClipKey(fixation))

        if (hazard.isBehind) {
            keys.add("oncoming")
        }

        val limit = decision.limitKmh?.takeIf { it > 0 }
        if (limit != null) {
            keys.add("limit_$limit")
        }

        val excess = decision.excessKmh ?: 0.0
        if (limit != null && excess >= EXCESS_THRESHOLD_KMH) {
            keys.add("slow_down")
        }

        return keys
    }

    private fun getFixationTitle(
        type: FixationType,
        style: VoicePhraseStyle,
    ): String =
        when (type) {
            FixationType.BUS_LANE ->
                if (style == VoicePhraseStyle.FULL) {
                    "контроль полосы общественного транспорта"
                } else {
                    "полоса ОТ"
                }
            FixationType.RED_LIGHT ->
                if (style == VoicePhraseStyle.FULL) "контроль светофора" else "светофор"
            FixationType.ROAD_MARKING ->
                if (style == VoicePhraseStyle.FULL) "контроль разметки" else "разметка"
            FixationType.FIXED ->
                if (style == VoicePhraseStyle.FULL) "камера контроля скорости" else "камера"
            FixationType.MOBILE ->
                if (style == VoicePhraseStyle.FULL) "мобильная засада" else "засада"
            FixationType.COMPLEX ->
                if (style == VoicePhraseStyle.FULL) "комплекс фотофиксации" else "комплекс"
            FixationType.AVERAGE_ENTRY ->
                if (style == VoicePhraseStyle.FULL) {
                    "начало зоны контроля средней скорости"
                } else {
                    "зона средней скорости"
                }
            FixationType.AVERAGE_EXIT ->
                if (style == VoicePhraseStyle.FULL) {
                    "конец зоны контроля средней скорости"
                } else {
                    "конец зоны"
                }
            FixationType.DUMMY ->
                if (style == VoicePhraseStyle.FULL) "муляж камеры" else "муляж"
            FixationType.UNKNOWN ->
                if (style == VoicePhraseStyle.FULL) "опасный участок дороги" else "опасность"
        }

    private fun formatDistanceText(
        distanceM: Double,
        style: VoicePhraseStyle,
    ): String =
        when {
            distanceM >= 950.0 -> if (style == VoicePhraseStyle.FULL) "через 1 километр" else "1 километр"
            distanceM >= 750.0 -> if (style == VoicePhraseStyle.FULL) "через 800 метров" else "800 метров"
            distanceM >= 650.0 -> if (style == VoicePhraseStyle.FULL) "через 700 метров" else "700 метров"
            distanceM >= 450.0 -> if (style == VoicePhraseStyle.FULL) "через 500 метров" else "500 метров"
            distanceM >= 250.0 -> if (style == VoicePhraseStyle.FULL) "через 300 метров" else "300 метров"
            distanceM >= 150.0 -> if (style == VoicePhraseStyle.FULL) "через 200 метров" else "200 метров"
            distanceM >= 75.0 -> if (style == VoicePhraseStyle.FULL) "через 100 метров" else "100 метров"
            else -> ""
        }

    private fun getDistanceKey(distanceM: Double): String? =
        when {
            distanceM >= 950.0 -> "dist_1000"
            distanceM >= 750.0 -> "dist_800"
            distanceM >= 650.0 -> "dist_700"
            distanceM >= 450.0 -> "dist_500"
            distanceM >= 250.0 -> "dist_300"
            distanceM >= 150.0 -> "dist_200"
            distanceM >= 75.0 -> "dist_100"
            else -> null
        }

    private fun getFixationClipKey(type: FixationType): String =
        when (type) {
            FixationType.BUS_LANE -> "cam_bus_lane"
            FixationType.RED_LIGHT -> "cam_red_light"
            FixationType.ROAD_MARKING -> "cam_marking"
            FixationType.FIXED -> "cam_fixed"
            FixationType.MOBILE -> "cam_mobile"
            FixationType.COMPLEX -> "cam_complex"
            FixationType.AVERAGE_ENTRY -> "cam_zone_start"
            FixationType.AVERAGE_EXIT -> "cam_zone_end"
            FixationType.DUMMY -> "cam_dummy"
            FixationType.UNKNOWN -> "cam_hazard"
        }
}
