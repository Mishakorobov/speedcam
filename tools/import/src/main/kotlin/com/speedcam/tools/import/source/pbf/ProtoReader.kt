package com.speedcam.tools.import.source.pbf

import java.io.IOException
import java.io.InputStream

/**
 * Minimal reader for the Protocol Buffers wire format.
 *
 * Only what OSM PBF actually uses is implemented: varint, 64-bit, length-delimited and
 * 32-bit fields. Groups (wire types 3 and 4) were deprecated in proto2 and do not appear in
 * PBF, so encountering one is an error rather than something to skip.
 *
 * Written by hand rather than pulled in as a dependency: the PBF path exists to avoid
 * requiring an external `osmium` binary, and the subset of protobuf needed here is small
 * enough that a generated-code library would be more machinery than the problem warrants.
 */
class ProtoReader(
    private val data: ByteArray,
    private var pos: Int = 0,
    private val limit: Int = data.size,
) {
    /** True while more fields remain. */
    fun hasNext(): Boolean = pos < limit

    /** Reads the next field header. */
    fun next(): Field {
        val key = readVarint()
        val fieldNumber = (key ushr 3).toInt()
        val wireType = (key and 0x7).toInt()

        if (fieldNumber <= 0) throw IOException("invalid field number $fieldNumber at offset $pos")
        if (wireType == WIRE_START_GROUP || wireType == WIRE_END_GROUP) {
            throw IOException("group wire types are not used in OSM PBF (field $fieldNumber)")
        }
        return Field(fieldNumber, wireType)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= limit) throw IOException("truncated varint")
            val b = data[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IOException("varint longer than 64 bits")
    }

    fun readFixed64(): Long {
        require(8)
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return result
    }

    fun readFixed32(): Int {
        require(4)
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (data[pos + i].toInt() and 0xFF)
        }
        pos += 4
        return result
    }

    fun readFixed32Float(): Float = Float.fromBits(readFixed32())

    /** Returns a view over the next length-delimited field without copying. */
    fun readBytesView(): ByteArray {
        val length = readVarint().toInt()
        require(length)
        val start = pos
        pos += length
        return data.copyOfRange(start, start + length)
    }

    fun readString(): String = String(readBytesView(), Charsets.UTF_8)

    /** A nested reader over a length-delimited field, for embedded messages. */
    fun readMessage(): ProtoReader {
        val length = readVarint().toInt()
        require(length)
        val nested = ProtoReader(data, pos, pos + length)
        pos += length
        return nested
    }

    /** Skips a field whose contents are not needed. */
    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> readFixed64()
            WIRE_LENGTH -> readBytesView()
            WIRE_FIXED32 -> readFixed32()
            else -> throw IOException("cannot skip wire type $wireType")
        }
    }

    /**
     * Reads a packed repeated varint field.
     *
     * PBF uses packed encoding for the delta arrays in `DenseNodes`, where varint fields
     * would otherwise each cost a tag byte.
     */
    fun readPackedVarints(): LongArray {
        val view = readBytesView()
        val reader = ProtoReader(view)
        val out = ArrayList<Long>(view.size / 2)
        while (reader.hasNext()) out += reader.readVarint()
        return out.toLongArray()
    }

    /** Reads a packed repeated field that uses zigzag (sint64) encoding. */
    fun readPackedSint64(): LongArray {
        val raw = readPackedVarints()
        val out = LongArray(raw.size)
        for (i in raw.indices) out[i] = zigzagDecode(raw[i])
        return out
    }

    private fun require(n: Int) {
        if (pos + n > limit) throw IOException("truncated field: need $n bytes, have ${limit - pos}")
    }

    data class Field(
        val number: Int,
        val wireType: Int,
    )

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_START_GROUP = 3
        const val WIRE_END_GROUP = 4
        const val WIRE_FIXED32 = 5

        /** Protobuf `sint64` is zigzag-encoded: 0, -1, 1, -2, 2 … */
        fun zigzagDecode(value: Long): Long = (value ushr 1) xor -(value and 1L)
    }
}

/** Reads exactly [count] bytes from [input]. */
fun InputStream.readExactly(count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) throw IOException("stream ended after $read of $count bytes")
        read += n
    }
    return out
}
