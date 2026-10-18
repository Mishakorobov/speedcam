package com.speedcam.tools.import.source.pbf

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import com.speedcam.tools.import.source.OverpassMapper
import java.io.File

/**
 * Extracts cameras and average-speed corridors from an OSM `.osm.pbf` extract.
 *
 * This is the bulk path. A single Overpass query spanning a country times out, and the public
 * instances rate-limit unpredictably; a 300 MB district file is downloaded once, verified by
 * MD5 and then read locally as often as needed.
 *
 * **Relations need two passes.** An `enforcement=average_speed` relation names its `from` and
 * `to` nodes by id, and those nodes are not guaranteed to precede the relation in the file. The
 * first pass collects the relation records and the set of node ids they reference; the second
 * resolves those coordinates. Holding every node's position in memory instead would cost
 * gigabytes — a Russia extract has several hundred million nodes.
 */
class PbfImporter {
    /** Statistics for reporting, and for describing a partial file honestly. */
    data class Result(
        val cameras: List<Camera>,
        val sections: List<SectionRecord>,
        val nodesScanned: Long,
        val blobsRead: Long,
        val truncated: Boolean,
    )

    fun import(
        file: File,
        progress: (String) -> Unit = {},
    ): Result {
        require(file.isFile) { "not a file: ${file.path}" }

        val endpoints = collectSectionEndpoints(file)
        progress("pass 1: ${endpoints.relations.size} enforcement relations, ${endpoints.nodeRefs.size} endpoint nodes")

        return collectNodes(file, endpoints)
    }

    /** An average-speed relation, resolved against the second pass. */
    private data class RelationRef(
        val id: Long,
        val fromRef: Long,
        val toRef: Long,
        val limitKmh: Int,
    )

    private class Endpoints(
        val relations: List<RelationRef>,
        val nodeRefs: Set<Long>,
    )

    private fun collectSectionEndpoints(file: File): Endpoints {
        val relations = mutableListOf<RelationRef>()
        val nodeRefs = HashSet<Long>()

        PbfReader(file).use { reader ->
            reader.scan { group, context ->
                group.relations?.forEach { relation ->
                    val tags = context.relationTags(relation.keysVals)
                    if (tags["type"] != "enforcement") return@forEach
                    if (tags["enforcement"] != "average_speed") return@forEach

                    val limit = OverpassMapper.parseLimit(tags["maxspeed"]) ?: return@forEach

                    var from = -1L
                    var to = -1L
                    for (i in relation.roles.indices) {
                        // Only node members carry coordinates; way members would need geometry
                        // resolution, which this importer does not do.
                        if (relation.types.getOrNull(i) != TYPE_NODE) continue

                        // `roles` holds string-table indices, not the role text itself.
                        val role = context.strings.getOrNull(relation.roles[i])
                        if (role == ROLE_FROM) from = relation.memberIds[i]
                        if (role == ROLE_TO) to = relation.memberIds[i]
                    }

                    if (from <= 0 || to <= 0) return@forEach
                    relations += RelationRef(relation.id, from, to, limit)
                    nodeRefs += from
                    nodeRefs += to
                }
            }
        }
        return Endpoints(relations, nodeRefs)
    }

    private fun collectNodes(
        file: File,
        endpoints: Endpoints,
    ): Result {
        val cameras = mutableListOf<Camera>()
        val coords = HashMap<Long, LatLon>(endpoints.nodeRefs.size.coerceAtLeast(16))
        var nodesScanned = 0L
        var blobsRead = 0L
        var truncated = false

        PbfReader(file).use { reader ->
            reader.scan { group, context ->
                val dense = group.dense
                val wanted = endpoints.nodeRefs

                if (dense != null) {
                    for (d in dense) {
                        nodesScanned += d.ids.size
                        val keys = d.keysVals
                        var cursor = 0
                        for (i in d.ids.indices) {
                            val id = d.ids[i]
                            if (id in wanted) {
                                coords[id] = LatLon(context.latitude(d.lat[i]), context.longitude(d.lon[i]))
                            }

                            val tags = context.tagsOf(keys, cursor)
                            cursor = tags.next
                            if (tags.isCamera) {
                                cameras += context.toCamera(id, d.lat[i], d.lon[i], tags.map)
                            }
                        }
                    }
                }

                group.nodes?.forEach { node ->
                    nodesScanned++
                    if (node.id in wanted) {
                        coords[node.id] = LatLon(context.latitude(node.lat), context.longitude(node.lon))
                    }
                    val tags = context.tagsOf(node.keysVals)
                    if (tags.isCamera) {
                        cameras += context.toCamera(node.id, node.lat, node.lon, tags.map)
                    }
                }
            }
            nodesScanned = reader.nodeCount
            blobsRead = reader.blobsRead
            truncated = reader.truncated
        }

        val sections =
            endpoints.relations.mapNotNull { rel ->
                val from = coords[rel.fromRef] ?: return@mapNotNull null
                val to = coords[rel.toRef] ?: return@mapNotNull null
                SectionRecord(from = from, to = to, limitKmh = rel.limitKmh)
            }

        return Result(cameras, sections, nodesScanned, blobsRead, truncated)
    }

    /** Decoded tags for one node, with the cursor position after them. */
    class NodeTags(
        val map: Map<String, String>,
        val next: Int,
        /** True when this node is tagged as a speed camera. */
        val isCamera: Boolean,
    )

    companion object {
        private const val TYPE_NODE = 0
        private const val ROLE_FROM = "from"
        private const val ROLE_TO = "to"

        private const val KEY_HIGHWAY = "highway"
        private const val VALUE_SPEED_CAMERA = "speed_camera"
    }
}

/**
 * Cursor over the packed key/value stream of a `DenseNodes` block.
 *
 * The pairs for every node in a block are packed into one flat array with a zero terminator
 * after each node's tags, so decoding has to track a position across the whole block rather
 * than reading each node's slice independently. Encapsulating that here keeps the scanning
 * loop in [tagsOf] free of nested conditionals.
 */
private class TagCursor(
    private val values: IntArray,
    var index: Int,
) {
    fun exhausted(): Boolean = index >= values.size

    /** True at the zero that terminates the current node's tag list. */
    fun atTerminator(): Boolean = !exhausted() && values[index] == 0

    /** True when a key is present but its value would fall past the end of the stream. */
    fun pairIncomplete(): Boolean = index + 1 >= values.size

    fun keyIndex(): Int = values[index]

    fun valueIndex(): Int = values[index + 1]

    fun advancePair() {
        index += 2
    }

    fun consumeTerminator() {
        index++
    }
}

/**
 * Decodes a node's key/value pairs, starting at [start] within the block's stream.
 *
 * [PbfImporter.NodeTags.next] is where the next node's tags begin, so the caller carries the
 * cursor from one node to the next.
 */
fun PbfReader.Context.tagsOf(
    keysVals: IntArray,
    start: Int = 0,
): PbfImporter.NodeTags {
    val cursor = TagCursor(keysVals, start)
    val map = HashMap<String, String>(4)
    var camera = false

    while (!cursor.exhausted() && !cursor.atTerminator() && !cursor.pairIncomplete()) {
        val pair = decodePair(cursor.keyIndex(), cursor.valueIndex())
        if (pair != null) {
            if (pair.first == KEY_HIGHWAY && pair.second == VALUE_SPEED_CAMERA) camera = true
            map[pair.first] = pair.second
        }
        cursor.advancePair()
    }

    if (cursor.atTerminator()) cursor.consumeTerminator()

    return PbfImporter.NodeTags(map, cursor.index, camera)
}

private fun PbfReader.Context.decodePair(
    keyIndex: Int,
    valueIndex: Int,
): Pair<String, String>? {
    val key = strings.getOrNull(keyIndex) ?: return null
    val value = strings.getOrNull(valueIndex) ?: return null
    return key to value
}

private const val KEY_HIGHWAY = "highway"
private const val VALUE_SPEED_CAMERA = "speed_camera"

/** Decodes the key/value pairs of a relation, which are not zero-terminated. */
fun PbfReader.Context.relationTags(keysVals: IntArray): Map<String, String> {
    val map = HashMap<String, String>(4)
    val cursor = TagCursor(keysVals, 0)

    // Relations carry their pairs to the end of the array with no terminator, so the scan
    // stops on exhaustion rather than on a sentinel.
    while (!cursor.exhausted() && !cursor.pairIncomplete()) {
        val pair = decodePair(cursor.keyIndex(), cursor.valueIndex())
        if (pair != null) map[pair.first] = pair.second
        cursor.advancePair()
    }
    return map
}

fun PbfReader.Context.toCamera(
    id: Long,
    rawLat: Long,
    rawLon: Long,
    tags: Map<String, String>,
): Camera {
    val direction = tags["direction"]
    return Camera(
        id = 0L,
        position = LatLon(latitude(rawLat), longitude(rawLon)),
        source = CameraSource.OSM,
        externalId = "node/$id",
        limitKmh = OverpassMapper.parseLimit(tags["maxspeed"]),
        kind =
            when {
                tags["enforcement"] == "average_speed" -> CameraKind.AVERAGE_ENTRY
                tags["enforcement"] == "traffic_signals" -> CameraKind.RED_LIGHT
                tags["man_made"] == "surveillance" -> CameraKind.COMPLEX
                else -> CameraKind.FIXED
            },
        facingDeg = OverpassMapper.parseDirection(direction),
        dirCount = if (direction != null) 1 else 2,
        operator = tags["operator"],
        sourceDetail = null,
        checkDate = tags["check_date"] ?: tags["survey:date"],
    )
}
