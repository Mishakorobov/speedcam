package com.speedcam.service.wear

import com.google.gson.Gson
import com.speedcam.service.DetectionUiState
import kotlin.math.roundToInt

/**
 * Compact serializable vehicle telemetry and hazard state transmitted to Wear OS companion watches.
 */
data class WearTelemetry(
    val speedKmh: Int = 0,
    val speedLimitKmh: Int? = null,
    val overLimit: Boolean = false,
    val nearestDistanceM: Int? = null,
    val nearestKind: String? = null,
    val nearestBehind: Boolean = false,
    val inSpecsZone: Boolean = false,
    val specsAverageKmh: Double? = null,
    val specsLimitKmh: Int? = null,
    val specsRemainingM: Double? = null,
    val warningLevel: Int = 0, // 0 = Clear, 1 = Approaching, 2 = Danger/Overlimit, 3 = SpecsActive
    val running: Boolean = false,
    val hasFix: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        fun fromJson(jsonStr: String): WearTelemetry =
            runCatching { gson.fromJson(jsonStr, WearTelemetry::class.java) }.getOrNull() ?: WearTelemetry()

        fun fromDetectionUiState(state: DetectionUiState): WearTelemetry {
            val nearest = state.nearest
            val section = state.activeSection
            val limit = section?.limitKmh ?: nearest?.limitKmh
            val warningLevel =
                when {
                    !state.running -> 0
                    state.overLimit && nearest != null -> 2
                    section != null -> 3
                    nearest != null -> 1
                    else -> 0
                }

            return WearTelemetry(
                speedKmh = state.speedKmh.roundToInt(),
                speedLimitKmh = limit,
                overLimit = state.overLimit,
                nearestDistanceM = nearest?.distanceM?.roundToInt(),
                nearestKind = nearest?.kind?.name,
                nearestBehind = nearest?.behind ?: false,
                inSpecsZone = section != null,
                specsAverageKmh = section?.averageKmh,
                specsLimitKmh = section?.limitKmh,
                specsRemainingM = section?.remainingM,
                warningLevel = warningLevel,
                running = state.running,
                hasFix = state.hasFix,
            )
        }
    }
}
