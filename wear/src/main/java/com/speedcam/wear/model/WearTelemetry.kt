package com.speedcam.wear.model

import com.google.gson.Gson

/**
 * Compact serializable vehicle telemetry and hazard state received on Wear OS watch.
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
    }
}
