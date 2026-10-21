package com.speedcam.service

import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon

/**
 * Throttles location polling when stationary to conserve battery and limit device thermals.
 *
 * Requirements (Phase 9.5):
 * When a vehicle stops (speed <= [stationarySpeedThresholdKmh] for longer than [stationaryDelayMs]):
 *  - Automatically throttles polling from [PollingRate.ACTIVE] (1 Hz) to [PollingRate.STATIONARY] (5s).
 *  - As soon as speed exceeds the threshold or vehicle displacement exceeds [displacementThresholdM],
 *    instantly returns to [PollingRate.ACTIVE] (1 Hz) so upcoming camera alerts are never delayed.
 *
 * Pure: takes [nowMs] and [fix], returning the target [PollingRate].
 */
class AdaptiveLocationWatchdog(
    /** Maximum speed (in km/h) considered stationary (allows for GPS drift). Default 3 km/h. */
    val stationarySpeedThresholdKmh: Double = DEFAULT_STATIONARY_SPEED_KMH,
    /** How long (in ms) the vehicle must stay stationary before throttling. Default 60 seconds. */
    val stationaryDelayMs: Long = DEFAULT_STATIONARY_DELAY_MS,
    /** Minimum displacement from stationary anchor (in meters) to immediately trip active mode. */
    val displacementThresholdM: Double = DEFAULT_DISPLACEMENT_THRESHOLD_M,
) {
    private var stationarySinceMs: Long? = null
    private var stationaryAnchor: LatLon? = null

    @Volatile
    var currentRate: PollingRate = PollingRate.ACTIVE
        private set

    /**
     * Evaluates a location fix and returns the updated [PollingRate].
     */
    fun onFix(
        nowMs: Long,
        fix: Fix,
    ): PollingRate {
        val isMovingSpeed = fix.speedKmh > stationarySpeedThresholdKmh
        val anchor = stationaryAnchor
        val isDisplaced = anchor != null && Geo.haversineMeters(anchor, fix.position) > displacementThresholdM

        if (isMovingSpeed || isDisplaced) {
            // Vehicle is actively moving: reset stationary timers and restore 1 Hz polling
            stationarySinceMs = null
            stationaryAnchor = null
            currentRate = PollingRate.ACTIVE
            return PollingRate.ACTIVE
        }

        // Vehicle speed is below threshold and within stationary radius
        val since = stationarySinceMs
        if (since == null) {
            stationarySinceMs = nowMs
            stationaryAnchor = fix.position
            currentRate = PollingRate.ACTIVE
            return PollingRate.ACTIVE
        }

        if (nowMs - since >= stationaryDelayMs) {
            currentRate = PollingRate.STATIONARY
            return PollingRate.STATIONARY
        }

        currentRate = PollingRate.ACTIVE
        return PollingRate.ACTIVE
    }

    /** Resets the state (e.g. on service start or foreground resume). */
    fun reset() {
        stationarySinceMs = null
        stationaryAnchor = null
        currentRate = PollingRate.ACTIVE
    }

    companion object {
        const val DEFAULT_STATIONARY_SPEED_KMH = 3.0
        const val DEFAULT_STATIONARY_DELAY_MS = 60_000L
        const val DEFAULT_DISPLACEMENT_THRESHOLD_M = 15.0
    }
}
