package com.speedcam.core.detect

import com.speedcam.core.geo.Geo

/**
 * Decides which cameras warrant a warning for the current position, heading and profile.
 *
 * This is the component that replaces the original application's native detection core
 * (`RadarDetectorEngine`, 121 `native` declarations). The original's exact geometry is not
 * recoverable from the decompiled Java layer — see Plan.md §5 — so this implementation
 * defines an explicit model rather than replicating undocumented behaviour.
 *
 * Pure by construction: no clock, no I/O, no Android types. Every input arrives as a parameter.
 */
class DetectionEngine(
    private val query: CameraQuery,
) {
    /**
     * Returns all active hazards, nearest first.
     *
     * Pipeline:
     *  1. bound the search to the effective warning radius,
     *  2. reject anything beyond that radius by exact great-circle distance,
     *  3. apply the directional gate (facing direction and, optionally, oncoming suppression),
     *  4. apply the under-speed suppression rules,
     *  5. sort nearest-first.
     */
    fun detect(
        fix: Fix,
        profile: AlertProfile,
    ): List<Hazard> = runDetection(fix, profile, query)

    /** Convenience overload for callers holding an already-collected candidate set. */
    fun detect(
        fix: Fix,
        profile: AlertProfile,
        candidates: Collection<Camera>,
    ): List<Hazard> = runDetection(fix, profile) { candidates.toList() }

    private inline fun runDetection(
        fix: Fix,
        profile: AlertProfile,
        cameraQuery: CameraQuery,
    ): List<Hazard> {
        val radiusM = profile.effectiveDistanceM(fix.speedMs.toDouble())
        if (radiusM <= 0.0) return emptyList()

        val box = Geo.boundingBox(fix.position, radiusM)

        return cameraQuery
            .inBox(box)
            .asSequence()
            .mapNotNull { toHazardOrNull(fix, it, radiusM) }
            .filter { it.distanceM <= radiusM }
            .filter { passesDirectionGate(it, profile) }
            .filterNot { isSuppressedBySpeed(fix, it, profile) }
            .sortedBy { it.distanceM }
            .toList()
    }

    private fun toHazardOrNull(
        fix: Fix,
        camera: Camera,
        radiusM: Double,
    ): Hazard? {
        val distance = Geo.haversineMeters(fix.position, camera.position)

        // Cheap early out. The authoritative test is `distanceM <= radiusM` in detect(),
        // so this bound only needs to be safely wider than it — the bounding box may return
        // corner candidates that lie outside the radius, and those are common.
        if (distance > radiusM * PREFILTER_FACTOR) return null

        val bearing = Geo.bearingDeg(fix.position, camera.position)
        val relative = Geo.normalize180(bearing - fix.headingDeg)
        val limit = camera.limitKmh

        return Hazard(
            camera = camera,
            distanceM = distance,
            bearingDeg = bearing,
            relativeBearingDeg = relative,
            excessKmh = if (limit != null && limit > 0) fix.speedKmh - limit else null,
        )
    }

    /**
     * Suppresses cameras that cannot be facing the driver.
     *
     * Two independent rules, both present in the original application:
     *
     *  - **Facing rule.** When the source recorded a direction (`direction=` in OSM, the
     *    direction field in the SpeedCam format) the camera only warns if it faces the
     *    approaching traffic. A north-facing camera does not warn southbound drivers.
     *
     *  - **Back-shot rule.** When [AlertProfile.backshotOnly] is set, anything more than 90
     *    degrees off the vehicle's heading is an oncoming camera on the opposite carriageway
     *    and is suppressed. The original exposes this as "Warn back-shot".
     */
    fun passesDirectionGate(
        hazard: Hazard,
        profile: AlertProfile,
    ): Boolean {
        val facingOk = cameraFacesUs(hazard)
        val backshotOk = !profile.backshotOnly || !hazard.isBehind
        return facingOk && backshotOk
    }

    private fun cameraFacesUs(hazard: Hazard): Boolean {
        val camera = hazard.camera
        val facing = camera.facingDeg
        if (facing == null || camera.dirCount > 1) return true

        // The camera warns us only if it looks back down the road at us. Our bearing to it is
        // `hazard.bearingDeg` and the device faces `facing`; these describe opposite travel
        // directions, hence the 180 degree offset.
        return Geo.angleDiff(facing + 180.0, hazard.bearingDeg) <= FACING_TOLERANCE_DEG
    }

    private fun isSuppressedBySpeed(
        fix: Fix,
        hazard: Hazard,
        profile: AlertProfile,
    ): Boolean = profile.isSuppressedBySpeed(fix.speedKmh, hazard.camera.limitKmh)

    companion object {
        /**
         * Slack applied to the cheap trigonometric prefilter, as a multiple of the radius.
         *
         * A bounding-box query returns the box corners as well as the circle, so candidates
         * just outside the radius are normal and expected. The exact test in [detect] is
         * authoritative; this only skips ones that cannot possibly qualify.
         */
        private const val PREFILTER_FACTOR = 2.0

        /**
         * Slack allowed when comparing a recorded facing direction, in degrees.
         *
         * Surveyed `direction=` values are frequently imprecise, and a strict 90-degree
         * comparison would drop legitimate warnings for a few degrees of annotation error.
         */
        private const val FACING_TOLERANCE_DEG = 100.0
    }
}
