package com.speedcam.core.detect

import com.speedcam.core.geo.LatLon

/**
 * An average-speed corridor (SPECS): a measured road section with a limit at both ends.
 *
 * The vehicle's average speed is computed over the straight-line distance between the
 * endpoints. That is a simplification — the original application computes it along the driven
 * route, which requires the route engine this reimplementation does not have. Over a corridor
 * of a few hundred metres to a couple of kilometres the two agree closely, but the difference
 * grows for long corridors.
 *
 * A corridor is normally published in OSM as a `type=enforcement` relation with
 * `enforcement=average_speed`, tying a `from` node and a `to` node to the governed stretch.
 */
data class SectionRecord(
    val from: LatLon,
    val to: LatLon,
    val limitKmh: Int,
    val active: Boolean = true,
) {
    init {
        require(limitKmh > 0) { "an average-speed limit must be positive, got $limitKmh" }
    }

    /** Straight-line length of the corridor, in metres. */
    val lengthM: Double get() =
        com.speedcam.core.geo.Geo
            .haversineMeters(from, to)
}
