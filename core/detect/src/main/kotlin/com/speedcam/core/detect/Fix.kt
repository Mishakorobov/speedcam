package com.speedcam.core.detect

import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon

/**
 * Read access to the camera store.
 *
 * Declared here, in the pure domain module, rather than depending on Room from `core:data`.
 * The direction of the dependency is deliberate: the domain declares what it needs, and the
 * persistence layer supplies an implementation. This is also what makes [DetectionEngine]
 * testable without an Android runtime.
 *
 * Declared `fun interface` so tests can pass a candidate set as a lambda.
 */
fun interface CameraQuery {
    /** Returns every camera whose position falls inside [box]. */
    fun inBox(box: BoundingBox): List<Camera>
}

/** A single GPS fix, as consumed by the detector. */
data class Fix(
    val position: LatLon,
    val timestampMillis: Long,
    /** Horizontal accuracy in metres, as reported by the provider. */
    val accuracyM: Float = 0f,
    /** Ground speed in metres per second. */
    val speedMs: Float = 0f,
    /** Course over ground in degrees within [0, 360). */
    val headingDeg: Double = 0.0,
    val altitudeM: Double = 0.0,
    /**
     * Raw provider name as reported by Android. Android reports the string `"fused"` for
     * fixes that have already been arbitrated between the GPS and network providers, which
     * [com.speedcam.service.FixArbiter] uses as a tie-breaker.
     */
    val providerTag: String? = null,
) {
    /** Speed in km/h. */
    val speedKmh: Double get() = speedMs * 3.6

    /** True when the provider has already arbitrated between GPS and network sources. */
    val isFused: Boolean get() = providerTag == FUSED_TAG

    /** False when the accuracy is implausible, i.e. the provider is momentarily lost. */
    val isUsable: Boolean get() = accuracyM in 0f..MAX_USABLE_ACCURACY_M

    companion object {
        const val FUSED_TAG = "fused"

        /** Beyond this the fix is noise, not a position. */
        const val MAX_USABLE_ACCURACY_M = 200f

        /** Speed threshold in km/h below which vehicle is considered stationary. */
        const val DEADBAND_SPEED_KMH = SpeedFilter.DEADBAND_SPEED_KMH

        /** Speed threshold in m/s below which vehicle is considered stationary (2.0 km/h / 3.6 ≈ 0.5556 m/s). */
        const val DEADBAND_SPEED_MS = SpeedFilter.DEADBAND_SPEED_MS
    }
}

/**
 * A camera that the current profile considers worth warning about.
 */
data class Hazard(
    val camera: Camera,
    val distanceM: Double,
    /** Bearing from the vehicle to the camera, degrees clockwise from north. */
    val bearingDeg: Double,
    /**
     * Signed angle between the vehicle's heading and the bearing to the camera,
     * within [-180, 180). Zero means the camera is dead ahead.
     */
    val relativeBearingDeg: Double,
    /** `speedKmh - limitKmh`, or null when the camera has no recorded limit. */
    val excessKmh: Double?,
    /** Current vehicle speed in km/h when hazard was evaluated. */
    val speedKmh: Double = 0.0,
) {
    /** True when the camera is behind or beside the vehicle rather than ahead. */
    val isBehind: Boolean get() = kotlin.math.abs(relativeBearingDeg) > 90.0
}
