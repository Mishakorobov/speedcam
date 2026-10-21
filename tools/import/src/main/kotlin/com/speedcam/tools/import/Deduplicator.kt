package com.speedcam.tools.import

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon

/**
 * Collapses records that describe the same physical camera across sources.
 *
 * OSM and the SpeedCam corpus come from different communities and overlap heavily. Left alone,
 * a driver would be warned twice for one camera.
 *
 * **Matching is by distance, not by rounded coordinates.** An earlier version bucketed on six
 * decimal places, which is exact agreement to about 0.1 m — fine in tests, useless against real
 * data, because the two sources routinely disagree by a few metres on the same installation.
 *
 * **The radius is deliberately tight.** Russian enforcement complexes often put several
 * devices on one gantry, metres apart, and those are distinct cameras that must each warn.
 * Merging too eagerly loses a real warning, which is worse than warning twice.
 */
class Deduplicator(
    private val radiusM: Double = RADIUS_M,
) {
    private class Cluster(
        val members: MutableList<Camera> = mutableListOf(),
    )

    fun merge(cameras: Collection<Camera>): List<Camera> {
        // One cluster representative per grid cell. The cell is sized so that any two points
        // within [radiusM] land in the same or an adjacent cell, which bounds the search to
        // the nine cells below.
        val byCell = HashMap<Long, MutableList<Cluster>>()
        val ordered = cameras.sortedWith(STABLE_ORDER)

        val merged = mutableListOf<Camera>()

        for (camera in ordered) {
            val match = findNearbyCluster(camera, byCell)
            if (match == null) {
                val cluster = Cluster(mutableListOf(camera))
                byCell.getOrPut(cellKey(camera.position)) { mutableListOf() } += cluster
            } else {
                match.members += camera
            }
        }

        for (clusters in byCell.values) {
            for (cluster in clusters) {
                merged +=
                    if (cluster.members.size == 1) {
                        cluster.members.single()
                    } else {
                        collapse(cluster.members)
                    }
            }
        }

        return merged.sortedWith(STABLE_ORDER)
    }

    /** Finds an existing cluster whose representative is within [radiusM] of [camera]. */
    private fun findNearbyCluster(
        camera: Camera,
        byCell: Map<Long, MutableList<Cluster>>,
    ): Cluster? {
        val (cellLat, cellLon) = cellOf(camera.position)

        return neighbouringCells(cellLat, cellLon)
            .asSequence()
            .mapNotNull { byCell[it] }
            .flatten()
            .firstOrNull { distanceToRepresentative(it, camera) <= radiusM }
    }

    /**
     * The nine grid cells around (lat, lon).
     *
     * Searching only the centre cell would miss a pair straddling a boundary, so the whole
     * neighbourhood is consulted.
     */
    private fun neighbouringCells(
        lat: Long,
        lon: Long,
    ): List<Long> =
        buildList {
            for (dLat in -1..1) {
                for (dLon in -1..1) {
                    add(packCell(lat + dLat, lon + dLon))
                }
            }
        }

    private fun distanceToRepresentative(
        cluster: Cluster,
        camera: Camera,
    ): Double = Geo.haversineMeters(cluster.members.first().position, camera.position)

    /**
     * Collapses one cluster into a single record.
     *
     * Field selection is "most complete wins", with [SOURCE_PRIORITY] breaking ties.
     * [Camera.facingDeg] is treated as worth more than any other single field, because it is
     * what suppresses an oncoming false alert.
     */
    private fun collapse(members: List<Camera>): Camera {
        val ordered =
            members.sortedWith(
                compareByDescending<Camera> { richness(it) }.thenBy { sourcePriority(it.source) },
            )
        val best = ordered.first()

        return best.copy(
            limitKmh = ordered.firstNotNullOfOrNull { it.limitKmh },
            kind = ordered.firstOrNull { it.kind != CameraKind.UNKNOWN }?.kind ?: best.kind,
            facingDeg = ordered.firstNotNullOfOrNull { it.facingDeg },
            checkDate = ordered.firstNotNullOfOrNull { it.checkDate },
            operator = ordered.firstNotNullOfOrNull { it.operator },
            // Keep every contributing param line so nothing is lost in the collapse.
            sourceDetail =
                ordered
                    .mapNotNull { it.sourceDetail }
                    .distinct()
                    .joinToString(" | ")
                    .ifEmpty { null },
        )
    }

    /** Counts how many optional fields a record populates, with facing direction weighted. */
    private fun richness(c: Camera): Int {
        var score = 0
        if (c.facingDeg != null) score += 2
        if (c.limitKmh != null) score += 1
        if (c.kind != CameraKind.UNKNOWN) score += 1
        if (c.operator != null) score += 1
        if (c.checkDate != null) score += 1
        return score
    }

    private fun sourcePriority(source: CameraSource): Int = SOURCE_PRIORITY[source] ?: Int.MAX_VALUE

    private fun cellKey(position: LatLon): Long = packCell(cellOf(position).first, cellOf(position).second)

    private fun cellOf(position: LatLon): Pair<Long, Long> {
        val lat = Math.floor(position.lat / CELL_DEGREES).toLong()
        val lon = Math.floor(position.lon / CELL_DEGREES).toLong()
        return lat to lon
    }

    /** Packs a cell coordinate pair into one key. Latitude and longitude stay distinguishable. */
    private fun packCell(
        lat: Long,
        lon: Long,
    ): Long = ((lat and 0xFFFFFFFFL) shl 32) or (lon and 0xFFFFFFFFL)

    companion object {
        /**
         * Merge radius, in metres.
         *
         * Wide enough to absorb cross-source disagreement and coordinate rounding, tight enough
         * that devices on a shared gantry stay separate.
         */
        const val RADIUS_M = 8.0

        /**
         * Grid cell size in degrees, about 55 m at the equator and ~10 m at 80° latitude.
         *
         * Must exceed [RADIUS_M] in both latitude and longitude across all serviceable latitudes,
         * so that any two points within the radius land in the same or an adjacent cell.
         */
        private const val CELL_DEGREES = 0.0005

        /**
         * Tie-break order when two records are equally complete.
         *
         * The SpeedCam corpus leads for Russia: it carries locally relevant device types and
         * speed limits, and is denser than OSM in the regions drivers actually use.
         */
        val SOURCE_PRIORITY: Map<CameraSource, Int> =
            mapOf(
                CameraSource.SPEEDCAM to 0,
                CameraSource.OSM to 1,
                CameraSource.MANUAL to 2,
            )

        /** Source-independent ordering, so successive builds stay comparable. */
        private val STABLE_ORDER: Comparator<Camera> =
            compareBy<Camera>(
                { it.position.lat },
                { it.position.lon },
                { it.source.ordinal },
                { it.externalId ?: "" },
            )
    }
}
