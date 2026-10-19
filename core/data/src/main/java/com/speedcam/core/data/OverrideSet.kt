package com.speedcam.core.data

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon

/**
 * What the driver has done about a camera.
 *
 * The original distinguishes two things that look alike from the outside:
 *
 *  - **mute** — `nativeMuteHazardAtSlot`, hides one hazard for this session only. The
 *    unfiltered hazard array still reaches the audio engine, so the engine decides whether
 *    the camera is still worth sounding for.
 *  - **block** — `nativeBlockHazardAtSlot`, a persistent blacklist. Blocked POIs are removed
 *    from `nativeGetHazardsState()` inside the native core, so they never become hazards at
 *    all.
 *
 * Only the second is reproduced here, because it is the one the driver expects to persist.
 */
enum class OverrideAction {
    /** Silenced until the database is rebuilt. */
    BLOCK,

    /** Added by the user where the source had nothing. */
    ADD,
    ;

    companion object {
        /** `override_.action`, as written by the importer and the UI. */
        fun fromOrdinal(ordinal: Int): OverrideAction = entries.getOrElse(ordinal) { BLOCK }
    }
}

/**
 * The user's overrides, loaded once and applied to every query.
 *
 * The `override_` schema is positional — latitude, longitude, action, note, created — with no
 * column for the upstream identifier. Matching therefore has to be by proximity, and the
 * radius is a judgement call worth stating: GPS under tree cover drifts by tens of metres,
 * so a tight radius would let a blocked camera back into the warning list while the car is
 * stationary, which is the worst moment for it to happen.
 */
data class OverrideSet(
    /** Positions the driver has blocked. */
    val blocked: List<LatLon> = emptyList(),
    /** Positions the driver has added manually. */
    val added: List<LatLon> = emptyList(),
) {
    val isEmpty: Boolean get() = blocked.isEmpty() && added.isEmpty()

    companion object {
        /**
         * How close a camera must be to count as the one that was blocked.
         *
         * 40 m. Comfortably larger than the 5-25 m of drift a consumer receiver shows under
         * tree cover, and comfortably smaller than the ~60 m spacing between cameras at the
         * closest this database places them - so blocking one cannot silently take out its
         * neighbour.
         */
        const val MATCH_RADIUS_M = 40.0

        fun from(overrides: List<OverrideEntity>): OverrideSet {
            val blocked = ArrayList<LatLon>()
            val added = ArrayList<LatLon>()
            overrides.forEach { row ->
                val position = LatLon(row.lat, row.lon)
                when (OverrideAction.fromOrdinal(row.action)) {
                    OverrideAction.BLOCK -> blocked += position
                    OverrideAction.ADD -> added += position
                }
            }
            return OverrideSet(blocked = blocked, added = added)
        }
    }
}

/**
 * Applies [OverrideSet] to a camera query.
 *
 * A decorator rather than a filter inside [com.speedcam.core.detect.DetectionEngine], because
 * the domain has no business knowing the user blocked something: the engine is asked "what is
 * near here" and the answer already has the driver's wishes taken into account. That also
 * keeps blocking testable without Room or Android.
 */
class BlockingCameraQuery(
    private val delegate: CameraQuery,
    private var overrides: OverrideSet = OverrideSet(),
) : CameraQuery {
    /** Replaces the override set. Cheap enough to call on every fix. */
    fun update(overrides: OverrideSet) {
        this.overrides = overrides
    }

    override fun inBox(box: BoundingBox): List<Camera> {
        val cameras = delegate.inBox(box)
        if (overrides.blocked.isEmpty()) return cameras
        return cameras.filterNot { camera -> isBlocked(camera.position) }
    }

    /** True when a camera at [position] falls inside any block. */
    fun isBlocked(position: LatLon): Boolean =
        overrides.blocked.any { blocked ->
            Geo.haversineMeters(blocked, position) <= OverrideSet.MATCH_RADIUS_M
        }
}
