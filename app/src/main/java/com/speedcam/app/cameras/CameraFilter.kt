package com.speedcam.app.cameras

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * How the camera list is narrowed.
 *
 * The original has no camera browser at all — its "camera list" is a list of hazard
 * *categories* with no search and no speed-limit filter. This is therefore our design rather
 * than a port, and is built to the one thing a driver actually wants: which cameras are near
 * them, of what kind, and at what limit.
 */
data class CameraFilter(
    /** Free-text over id, operator and type. Empty matches everything. */
    val query: String = "",
    /** Empty means every kind. */
    val kinds: Set<CameraKind> = emptySet(),
    val sources: Set<CameraSource> = emptySet(),
    /** Only cameras with a recorded limit. */
    val requireLimit: Boolean = false,
    /** Inclusive lower bound on the limit, in km/h. */
    val minLimitKmh: Int? = null,
    /** Inclusive upper bound on the limit, in km/h. */
    val maxLimitKmh: Int? = null,
    /** Exclude cameras the driver has blocked. */
    val excludeBlocked: Boolean = true,
    /** Only cameras with no recorded limit. Mutually exclusive with [requireLimit]. */
    val onlyUnknownLimit: Boolean = false,
) {
    val isActive: Boolean
        get() =
            query.isNotBlank() ||
                kinds.isNotEmpty() ||
                sources.isNotEmpty() ||
                requireLimit ||
                onlyUnknownLimit ||
                minLimitKmh != null ||
                maxLimitKmh != null
}

/** One row of the list, with everything the screen needs and nothing else. */
data class CameraRow(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val limitKmh: Int?,
    val kind: CameraKind,
    val source: CameraSource,
    val operator: String?,
    val blocked: Boolean,
) {
    /** A limit of null is "not recorded", never zero. */
    val limitLabel: String get() = limitKmh?.takeIf { it > 0 }?.toString() ?: "—"
}

/**
 * Applies a filter and orders the result.
 *
 * Pure, and the whole of the list screen's behaviour, so it can be tested without Compose,
 * Room or a device.
 */
object CameraFilterEngine {
    /**
     * [blockRadiusM] must match [com.speedcam.core.data.OverrideSet.MATCH_RADIUS_M], because
     * a row shown as blocked has to be the row that blocking actually silences. Passed in
     * rather than imported so this stays free of the persistence module.
     */
    fun apply(
        cameras: List<Camera>,
        filter: CameraFilter,
        blocked: List<Pair<Double, Double>> = emptyList(),
        blockRadiusM: Double = DEFAULT_BLOCK_RADIUS_M,
    ): List<CameraRow> {
        val needle = filter.query.trim().lowercase()

        return cameras
            .asSequence()
            .filter { matchesLimits(it, filter) }
            .filter { matchesText(it, needle) }
            .map { camera ->
                CameraRow(
                    id = camera.id,
                    latitude = camera.position.lat,
                    longitude = camera.position.lon,
                    limitKmh = camera.limitKmh,
                    kind = camera.kind,
                    source = camera.source,
                    operator = camera.operator,
                    blocked = filter.excludeBlocked && isBlocked(camera, blocked, blockRadiusM),
                )
            }.sortedWith(
                // Blocked first so they are reachable to unblock, then by limit so the rows a
                // driver is scanning for are grouped, then by id for a stable order.
                compareByDescending<CameraRow> { it.blocked }
                    .thenBy { it.limitKmh ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            ).toList()
    }

    /**
     * Kind, source and limit constraints.
     *
     * One named predicate per rule rather than a chain of guard clauses: the chain read
     * perfectly and was both too long to hold in one's head and over the complexity budget,
     * while this says the same thing in a form that can be checked rule by rule.
     */
    private fun matchesLimits(
        camera: Camera,
        filter: CameraFilter,
    ): Boolean {
        val limit = camera.limitKmh?.takeIf { it > 0 }

        val kindOk = filter.kinds.isEmpty() || camera.kind in filter.kinds
        val sourceOk = filter.sources.isEmpty() || camera.source in filter.sources
        val requireOk = !filter.requireLimit || limit != null
        val unknownOk = !filter.onlyUnknownLimit || limit == null
        // A camera with no limit cannot satisfy a range. Excluding it is deliberate: leaving
        // it in would silently widen every "50 km/h and above" search.
        val minOk = filter.minLimitKmh == null || (limit != null && limit >= filter.minLimitKmh)
        val maxOk = filter.maxLimitKmh == null || (limit != null && limit <= filter.maxLimitKmh)

        return kindOk && sourceOk && requireOk && unknownOk && minOk && maxOk
    }

    private fun matchesText(
        camera: Camera,
        needle: String,
    ): Boolean {
        if (needle.isEmpty()) return true
        return camera.kind.name
            .lowercase()
            .contains(needle) ||
            camera.source.name
                .lowercase()
                .contains(needle) ||
            camera.operator?.lowercase()?.contains(needle) == true ||
            camera.externalId?.lowercase()?.contains(needle) == true ||
            camera.id.toString().contains(needle)
    }

    private fun isBlocked(
        camera: Camera,
        blocked: List<Pair<Double, Double>>,
        radiusM: Double,
    ): Boolean =
        blocked.any { (lat, lon) ->
            // Equirectangular is plenty at one-block-scale separations and avoids a trig call
            // per camera per redraw.
            val dLat = (camera.position.lat - lat) * METRES_PER_DEGREE
            val dLon = (camera.position.lon - lon) * METRES_PER_DEGREE * cos(lat * PI / 180.0)
            abs(dLon) <= radiusM && abs(dLat) <= radiusM
        }

    const val METRES_PER_DEGREE = 111_320.0
    const val DEFAULT_BLOCK_RADIUS_M = 40.0
}
