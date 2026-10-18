package com.speedcam.tools.import.source.pbf

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater

/**
 * Streaming reader for the OSM PBF format.
 *
 * A `.osm.pbf` file is a flat sequence of blobs, each a `BlobHeader` followed by a `Blob`.
 * The header's `datasize` makes each blob self-delimiting, which means a truncated download
 * is still readable up to the last complete blob — useful when a multi-gigabyte fetch is
 * interrupted.
 *
 * Only `OSMHeader`, `OSMData` and `zlib_data` blobs are decoded. LZMA, LZ4 and zstd variants
 * exist in the format but Geofabrik has never produced them; encountering one is an explicit
 * error rather than a silent skip.
 *
 * The file is streamed, never loaded whole: a Russia extract is around 4 GB and several
 * hundred million nodes.
 */
class PbfReader(
    file: File,
) : Closeable {
    private val input: BufferedInputStream = BufferedInputStream(file.inputStream(), BUFFER_SIZE)

    /** Nodes decoded so far. */
    var nodeCount: Long = 0
        private set

    /** Blob headers decoded, i.e. how far into the file the scan reached. */
    var blobsRead: Long = 0
        private set

    /** True when the scan stopped because the file ended rather than completing. */
    var truncated: Boolean = false
        private set

    override fun close() = input.close()

    /** Invokes [onGroup] for every decoded `PrimitiveGroup], with its enclosing [Context]. */
    fun scan(onGroup: (PrimitiveGroup, Context) -> Unit) {
        while (true) {
            val blob = readBlob() ?: break
            blobsRead++
            if (blob.type == "OSMData") decodeBlock(blob.raw, onGroup)
            // OSMHeader holds the file-wide bbox, which the importer does not need.
            // Raw hasher payloads and unknown blob types are likewise ignored.
        }
    }

    /** Block-level context: the string table and the coordinate scaling this block uses. */
    class Context(
        val strings: Array<String>,
        val granularity: Int,
        val latOffset: Long,
        val lonOffset: Long,
    ) {
        /**
         * Converts a PBF coordinate to decimal degrees.
         *
         * PBF stores coordinates as integers scaled by `granularity` (default 100
         * nanodegrees) and offset by the block's `lat_offset` / `lon_offset` (default 0).
         */
        fun latitude(delta: Long): Double = (latOffset + granularity.toLong() * delta) * 1e-9

        fun longitude(delta: Long): Double = (lonOffset + granularity.toLong() * delta) * 1e-9
    }

    /** Decoded `PrimitiveGroup`: dense nodes, plain nodes, or relations. */
    class PrimitiveGroup(
        val dense: List<DenseNodes>?,
        val nodes: List<SimpleNode>?,
        val relations: List<Relation>?,
    ) {
        /**
         * Every node in this group as (id, rawLat, rawLon).
         *
         * Coordinates are raw PBF units, not degrees: converting needs the block's
         * granularity and offsets, which live in [Context] and are only known to the caller.
         */
        fun allNodes(): Sequence<Triple<Long, Long, Long>> =
            sequence {
                dense?.forEach { d ->
                    for (i in d.ids.indices) yield(Triple(d.ids[i], d.lat[i], d.lon[i]))
                }
                nodes?.forEach { n -> yield(Triple(n.id, n.lat, n.lon)) }
            }
    }

    class DenseNodes(
        val ids: LongArray,
        /** Absolute PBF latitude units, already un-delta'd. */
        val lat: LongArray,
        val lon: LongArray,
        /** Flattened string-table key/value indices, zero-terminated per node. */
        val keysVals: IntArray,
    )

    class SimpleNode(
        val id: Long,
        val lat: Long,
        val lon: Long,
        val keysVals: IntArray,
    )

    class Relation(
        val id: Long,
        val keysVals: IntArray,
        /** Member type per entry: 0 = node, 1 = way, 2 = relation. */
        val types: IntArray,
        /** Member reference ids, delta-encoded in the file and un-delta'd here. */
        val memberIds: LongArray,
        /** Role string-table index per member. */
        val roles: IntArray,
    )

    /**
     * Reads one blob, or null when the file ends or breaks off.
     *
     * [nextBlobLength] is what distinguishes the three endings, which the caller needs to
     * tell apart: a clean end on a blob boundary, a stream that stops part-way through the
     * length prefix, and an implausible length left by an interrupted write.
     */
    private fun readBlob(): Blob? {
        val headerLength = nextBlobLength() ?: return null

        val header = ProtoReader(input.readExactly(headerLength))
        var type: String? = null
        var datasize = 0

        while (header.hasNext()) {
            val field = header.next()
            when (field.number) {
                1 -> type = header.readString()
                3 -> datasize = header.readVarint().toInt()
                else -> header.skip(field.wireType)
            }
        }

        return Blob(type ?: throw IOException("blob without a type"), decompress(input.readExactly(datasize)))
    }

    /**
     * Reads the next blob's header length, or null when no further blob can be read.
     *
     * Three endings are distinguished. A clean end exactly on a blob boundary means the file
     * is complete and [truncated] stays false; a stream that stops part-way through the
     * length prefix, or an implausible length left behind by an interrupted write, both mean
     * the file stops early and set [truncated].
     */
    private fun nextBlobLength(): Int? {
        val length =
            try {
                input.readIntOrNull()
            } catch (_: EOFException) {
                null
            }

        if (length == null) return null

        if (length <= 0 || length > MAX_BLOB_HEADER) {
            truncated = true
            return null
        }
        return length
    }

    private fun decompress(body: ByteArray): ByteArray {
        val blob = ProtoReader(body)

        var raw: ByteArray? = null
        var rawSize = 0
        var compressed: ByteArray? = null
        var compression = ""

        while (blob.hasNext()) {
            val field = blob.next()
            when (field.number) {
                1 -> raw = blob.readBytesView()
                2 -> rawSize = blob.readVarint().toInt()
                3 -> {
                    compressed = blob.readBytesView()
                    compression = "zlib"
                }
                // LZMA, bzip2, LZ4 and zstd exist in the format but Geofabrik does not emit
                // them. Remembering which was seen lets the check below name it.
                4 -> compression = "lzma"
                5 -> compression = "bzip2"
                6 -> compression = "lz4"
                7 -> compression = "zstd"
                else -> blob.skip(field.wireType)
            }
        }

        raw?.let { return it }

        val data = compressed ?: throw IOException("blob ($compression) carries no data")
        if (compression != "zlib") {
            throw IOException("unsupported blob compression '$compression'; Geofabrik emits zlib only")
        }
        return inflate(data, rawSize)
    }

    private fun inflate(
        data: ByteArray,
        rawSize: Int,
    ): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArray(rawSize)
        var written = 0

        while (written < rawSize && !inflater.finished()) {
            val n = inflater.inflate(out, written, rawSize - written)
            if (n == 0 && (inflater.finished() || inflater.needsInput())) break
            written += n
        }
        inflater.end()
        return if (written == rawSize) out else out.copyOf(written)
    }

    private fun decodeBlock(
        blockBytes: ByteArray,
        onGroup: (PrimitiveGroup, Context) -> Unit,
    ) {
        val reader = ProtoReader(blockBytes)

        var strings: Array<String> = emptyArray()
        var granularity = 100
        var latOffset = 0L
        var lonOffset = 0L
        val groupBytes = mutableListOf<ByteArray>()

        while (reader.hasNext()) {
            val field = reader.next()
            when (field.number) {
                1 -> strings = readStringTable(reader.readMessage())
                2 -> groupBytes += reader.readBytesView()
                17 -> granularity = reader.readVarint().toInt()
                19 -> latOffset = reader.readVarint()
                20 -> lonOffset = reader.readVarint()
                else -> reader.skip(field.wireType)
            }
        }

        val context = Context(strings, granularity, latOffset, lonOffset)
        for (bytes in groupBytes) {
            val group = readPrimitiveGroup(bytes)
            nodeCount += (group.dense?.sumOf { it.ids.size } ?: 0) + (group.nodes?.size ?: 0)
            onGroup(group, context)
        }
    }

    private fun readStringTable(reader: ProtoReader): Array<String> {
        val out = mutableListOf<String>()
        while (reader.hasNext()) {
            val field = reader.next()
            if (field.number == 1) out += reader.readString() else reader.skip(field.wireType)
        }
        return out.toTypedArray()
    }

    private fun readPrimitiveGroup(bytes: ByteArray): PrimitiveGroup {
        val reader = ProtoReader(bytes)

        var dense: MutableList<DenseNodes>? = null
        var nodes: MutableList<SimpleNode>? = null
        var relations: MutableList<Relation>? = null

        while (reader.hasNext()) {
            val field = reader.next()
            when (field.number) {
                1 ->
                    (nodes ?: mutableListOf<SimpleNode>().also { nodes = it }).add(
                        readSimpleNode(reader.readMessage()),
                    )
                2 ->
                    (dense ?: mutableListOf<DenseNodes>().also { dense = it }).add(
                        readDenseNodes(reader.readMessage()),
                    )
                4 ->
                    (relations ?: mutableListOf<Relation>().also { relations = it }).add(
                        readRelation(reader.readMessage()),
                    )
                else -> reader.skip(field.wireType)
            }
        }
        return PrimitiveGroup(dense, nodes, relations)
    }

    private fun readDenseNodes(reader: ProtoReader): DenseNodes {
        var ids = LongArray(0)
        var lats = LongArray(0)
        var lons = LongArray(0)
        var keysVals = IntArray(0)

        while (reader.hasNext()) {
            val field = reader.next()
            when (field.number) {
                1 -> ids = reader.readPackedSint64()
                8 -> lats = reader.readPackedSint64()
                9 -> lons = reader.readPackedSint64()
                10 -> keysVals = readPackedInt32(reader)
                else -> reader.skip(field.wireType)
            }
        }

        // id, lat and lon are delta-encoded within their own arrays.
        return DenseNodes(undelta(ids), undelta(lats), undelta(lons), keysVals)
    }

    private fun readSimpleNode(reader: ProtoReader): SimpleNode {
        var id = 0L
        var lat = 0L
        var lon = 0L
        var keysVals = IntArray(0)

        while (reader.hasNext()) {
            val field = reader.next()
            when (field.number) {
                1 -> id = ProtoReader.zigzagDecode(reader.readVarint())
                2 -> keysVals = readVarintKeysVals(reader)
                8 -> lat = ProtoReader.zigzagDecode(reader.readVarint())
                9 -> lon = ProtoReader.zigzagDecode(reader.readVarint())
                else -> reader.skip(field.wireType)
            }
        }
        return SimpleNode(id, lat, lon, keysVals)
    }

    private fun readRelation(reader: ProtoReader): Relation {
        var id = 0L
        var keysVals = IntArray(0)
        var roles = IntArray(0)
        var memberIds = LongArray(0)
        var types = IntArray(0)

        while (reader.hasNext()) {
            val field = reader.next()
            when (field.number) {
                1 -> id = ProtoReader.zigzagDecode(reader.readVarint())
                2 -> keysVals = readPackedInt32(reader)
                8 -> roles = readPackedInt32(reader)
                9 -> memberIds = reader.readPackedSint64()
                10 -> types = readPackedInt32(reader)
                else -> reader.skip(field.wireType)
            }
        }
        // memberIds are delta-encoded across the relation's member list.
        return Relation(id, keysVals, types, undelta(memberIds), roles)
    }

    /** Node key/value pairs are plain varints terminated by a zero. */
    private fun readVarintKeysVals(reader: ProtoReader): IntArray {
        val out = mutableListOf<Int>()
        while (reader.hasNext()) {
            val v = reader.readVarint().toInt()
            if (v == 0) break
            out += v
        }
        return out.toIntArray()
    }

    private fun readPackedInt32(reader: ProtoReader): IntArray {
        val values = reader.readPackedVarints()
        return IntArray(values.size) { values[it].toInt() }
    }

    private fun undelta(values: LongArray): LongArray {
        var acc = 0L
        for (i in values.indices) {
            acc += values[i]
            values[i] = acc
        }
        return values
    }

    /**
     * Reads a big-endian int32, the PBF blob-length prefix.
     *
     * Returns null when the stream ends cleanly with nothing left. Throws [EOFException] if
     * the stream ends part-way through the four bytes, which distinguishes a finished file
     * from a truncated one.
     */
    private fun InputStream.readIntOrNull(): Int? {
        val b = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = read(b, read, 4 - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("stream ended after $read of 4 bytes")
            }
            read += n
        }
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private data class Blob(
        val type: String,
        val raw: ByteArray,
    )

    companion object {
        private const val BUFFER_SIZE = 1 shl 16
        private const val MAX_BLOB_HEADER = 64 * 1024
    }
}
