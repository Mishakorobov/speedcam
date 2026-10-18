package com.speedcam.tools.import.source.pbf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Builds OSM PBF byte streams for tests.
 *
 * Writing the encoder as well as the decoder is deliberate: a test that round-trips through
 * the same helper proves less than one that asserts against hand-computed bytes. The
 * end-to-end test below checks absolute values — a known node id and a known coordinate in
 * real degrees — so a decoder that mis-scaled or mis-delta'd anything would fail.
 */
object PbfBuilder {
    // --- protobuf primitives ---

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            out.write(if (v == 0L) b else b or 0x80)
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

    /** field = length-delimited payload */
    fun field(
        fieldNumber: Int,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNumber.toLong() shl 3) or 2))
        out.write(varint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    /** field = varint */
    fun varintField(
        fieldNumber: Int,
        value: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varint((fieldNumber.toLong() shl 3) or 0))
        out.write(varint(value))
        return out.toByteArray()
    }

    fun stringTable(s: List<String>): ByteArray =
        s
            .fold(ByteArrayOutputStream()) { acc, v ->
                acc.write(field(1, v.toByteArray()))
                acc
            }.toByteArray()

    /**
     * Builds a `DenseNodes` message.
     *
     * Coordinates are supplied in PBF units (100 nanodegrees by default) so the test can
     * choose round numbers and assert on exact degrees afterwards.
     */
    fun denseNodes(
        ids: List<Long>,
        lats: List<Long>,
        lons: List<Long>,
        keysVals: List<Int>,
    ): ByteArray {
        fun deltas(values: List<Long>): ByteArray {
            var prev = 0L
            val out = ByteArrayOutputStream()
            for (v in values) {
                out.write(varint(zigzag(v - prev)))
                prev = v
            }
            return out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        out.write(field(1, deltas(ids)))
        out.write(field(8, deltas(lats)))
        out.write(field(9, deltas(lons)))

        val kv = ByteArrayOutputStream()
        for (v in keysVals) kv.write(varint(v.toLong()))
        out.write(field(10, kv.toByteArray()))

        return out.toByteArray()
    }

    /** Builds a `Relation` with the given members. */
    fun relation(
        id: Long,
        keysVals: List<Int>,
        types: List<Int>,
        memberIds: List<Long>,
        roles: List<Int>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varintField(1, zigzag(id)))

        fun packed(values: List<Int>): ByteArray {
            val b = ByteArrayOutputStream()
            for (v in values) b.write(varint(v.toLong()))
            return b.toByteArray()
        }

        fun packedDelta(values: List<Long>): ByteArray {
            var prev = 0L
            val b = ByteArrayOutputStream()
            for (v in values) {
                b.write(varint(zigzag(v - prev)))
                prev = v
            }
            return b.toByteArray()
        }

        out.write(field(2, packed(keysVals)))
        out.write(field(8, packed(roles)))
        out.write(field(9, packedDelta(memberIds)))
        out.write(field(10, packed(types)))
        return out.toByteArray()
    }

    fun primitiveBlock(
        strings: List<String>,
        groups: List<ByteArray>,
        granularity: Int = 100,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(field(1, stringTable(strings)))
        for (g in groups) out.write(field(2, g))
        out.write(varintField(17, granularity.toLong()))
        return out.toByteArray()
    }

    fun blob(raw: ByteArray): ByteArray {
        val deflated = deflate(raw)
        val body = ByteArrayOutputStream()
        body.write(varintField(2, raw.size.toLong()))
        body.write(field(3, deflated))
        return body.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Frames a blob as int32 datasize, BlobHeader, Blob — the file-level layout. */
    fun framedBlob(
        type: String,
        raw: ByteArray,
    ): ByteArray {
        val body = blob(raw)
        val header = field(1, type.toByteArray()) + varintField(3, body.size.toLong())

        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                ((header.size shr 24) and 0xFF).toByte(),
                ((header.size shr 16) and 0xFF).toByte(),
                ((header.size shr 8) and 0xFF).toByte(),
                (header.size and 0xFF).toByte(),
            ),
        )
        out.write(header)
        out.write(body)
        return out.toByteArray()
    }

    /** A complete `.osm.pbf` file containing the supplied OSMData blocks. */
    fun file(blocks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in blocks) out.write(framedBlob("OSMData", b))
        return out.toByteArray()
    }
}
