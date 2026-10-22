package com.speedcam.core.detect

/**
 * Filters vehicle speed readings by applying:
 * 1. Standstill deadband: speeds strictly below [DEADBAND_SPEED_KMH] (< 0.5556 m/s) are clamped to 0.0.
 * 2. Exponential Moving Average (EMA) smoothing during takeoff/low-speed acceleration to eliminate
 *    erratic Doppler jitter and jumps (e.g. 0 -> 1 -> 2 km/h flicker).
 * 3. Responsive cruising tracking: higher alpha at normal driving speeds to eliminate perceptible lag.
 * 4. Instant standstill snapping: when vehicle drops below deadband, speed immediately resets to 0.0
 *    without lingering or dragging.
 */
class SpeedFilter(
    val deadbandSpeedMs: Float = DEADBAND_SPEED_MS,
    val lowSpeedAlpha: Float = DEFAULT_LOW_SPEED_ALPHA,
    val highSpeedAlpha: Float = DEFAULT_HIGH_SPEED_ALPHA,
    val lowSpeedThresholdMs: Float = DEFAULT_LOW_SPEED_THRESHOLD_MS,
) {
    /** The latest filtered speed in metres per second. */
    var currentSpeedMs: Float = 0f
        private set

    /** The latest filtered speed in km/h. */
    val currentSpeedKmh: Double
        get() = currentSpeedMs * 3.6

    /**
     * Filters raw speed [rawSpeedMs] in metres per second.
     *
     * - Returns 0.0f if [rawSpeedMs] is NaN, negative, or strictly below [deadbandSpeedMs].
     * - Applies EMA when moving:
     *     v_smoothed = alpha * rawSpeedMs + (1 - alpha) * currentSpeedMs
     *   where alpha is [lowSpeedAlpha] during takeoff or [highSpeedAlpha] at cruising speed.
     */
    fun filter(rawSpeedMs: Float): Float {
        if (rawSpeedMs.isNaN() || rawSpeedMs < deadbandSpeedMs) {
            currentSpeedMs = 0f
            return 0f
        }

        val alpha = if (rawSpeedMs <= lowSpeedThresholdMs) lowSpeedAlpha else highSpeedAlpha
        val smoothed = (alpha * rawSpeedMs) + ((1f - alpha) * currentSpeedMs)
        currentSpeedMs = smoothed
        return smoothed
    }

    /**
     * Convenience function to filter an incoming [Fix] and return a new [Fix] with smoothed speed.
     */
    fun filter(fix: Fix): Fix {
        val filtered = filter(fix.speedMs)
        return fix.copy(speedMs = filtered)
    }

    /**
     * Resets the filter state back to stationary (0.0).
     */
    fun reset() {
        currentSpeedMs = 0f
    }

    companion object {
        /** Speed threshold in km/h below which vehicle is considered stationary. */
        const val DEADBAND_SPEED_KMH = 2.0

        /** Speed threshold in m/s below which vehicle is considered stationary (2.0 km/h / 3.6 ≈ 0.5556 m/s). */
        const val DEADBAND_SPEED_MS = (DEADBAND_SPEED_KMH / 3.6).toFloat()

        /** Default EMA alpha during takeoff / low-speed driving (smooths 0 -> 1 -> 2 jumps). */
        const val DEFAULT_LOW_SPEED_ALPHA = 0.5f

        /** Default EMA alpha during normal/high-speed driving (responsive tracking). */
        const val DEFAULT_HIGH_SPEED_ALPHA = 0.85f

        /** Speed threshold in m/s (15.0 km/h) separating low-speed takeoff from cruising. */
        const val DEFAULT_LOW_SPEED_THRESHOLD_MS = (15.0 / 3.6).toFloat()
    }
}
