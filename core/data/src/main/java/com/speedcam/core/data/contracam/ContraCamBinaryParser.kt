package com.speedcam.core.data.contracam

import com.speedcam.core.data.CameraEntity
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import java.io.File
import java.io.InputStream

/**
 * Fast zero-dependency streaming binary and text parser for ContraCam speed camera archives.
 *
 * ContraCam speed camera binary archives use a compact format:
 * - Records start with `0xA9` ([CameraKind.FIXED] / RTSpeedCamera).
 * - Preceding byte: camera `subType` (stationary, mobile, red-light, average-speed, dummy, etc.).
 * - Following byte: high 3 bits = facing compass octant (0..7 * 45°), low 5 bits = speed limit code.
 * - LEB128 varint: camera record ID.
 * - 6 bytes: 24-bit fixed point coordinates with 16 fractional bits:
 *     lat = (b0 shl 16 or b1 shl 8 or b2) / 65536.0
 *     lon = (b3 shl 16 or b4 shl 8 or b5) / 65536.0
 *
 * Also safely detects and rejects proprietary encrypted Vestigo disk images (to prevent
 * scraping unrelated road topology nodes), and parses standard PocketGIS/Navitel CSV exports.
 */
object ContraCamBinaryParser {
    private const val SPEEDCAM_RECORD_TAG = 0xA9.toByte()
    private const val DEGREE_SCALE = 65536.0
    private const val OCTANT_DEGREES = 45.0

    private const val MIN_LAT = 35.0
    private const val MAX_LAT = 82.0
    private const val MIN_LON = 19.0
    private const val MAX_LON = 180.0

    private val SPEED_CODE_MAP =
        mapOf(
            1 to 10,
            3 to 20,
            5 to 30,
            7 to 40,
            9 to 50,
            11 to 60,
            15 to 70,
            18 to 80,
            19 to 80,
            22 to 90,
            23 to 90,
            25 to 100,
            27 to 110,
            29 to 120,
            31 to 130,
        )

    private data class RecordHeader(
        val subType: Int,
        val sub: Int,
        val recId: Long,
    )

    /**
     * Parses all speed camera records from raw binary [data].
     */
    fun parse(
        data: ByteArray,
        checkDate: String? = null,
    ): List<CameraEntity> {
        if (data.isEmpty()) return emptyList()

        // Check if data is a Vestigo disk container (e.g. map.dat)
        val headerPrefix = if (data.size >= 32) String(data, 0, minOf(data.size, 64), Charsets.ISO_8859_1) else ""
        if (headerPrefix.contains("VESTIGO") || headerPrefix.contains("XVESTIGO")) {
            // map.dat is a Vestigo proprietary disk image container.
            // Raw 0xA9 scanning yields false positive road topology nodes in Siberia.
            // Return empty list so caller can fall back to the verified road-aligned speed camera dataset.
            return emptyList()
        }

        // Check if data is standard text / CSV format (e.g. speedcam.txt / PocketGIS / Navitel / iGO)
        if (isTextFormat(data)) {
            return parseTextSpeedcam(data, checkDate)
        }

        val result = ArrayList<CameraEntity>(data.size / 300)
        var p = 0
        val dataLen = data.size

        while (p < dataLen - 10) {
            if (data[p + 1] != SPEEDCAM_RECORD_TAG) {
                p++
                continue
            }
            val subType = data[p].toInt() and 0xFF
            val sub = data[p + 2].toInt() and 0xFF
            val (recId, cur) = readVarint(data, p + 3)

            if (cur + 6 <= dataLen) {
                val header = RecordHeader(subType, sub, recId)
                parseRecord(data, cur, header, checkDate)?.let { result.add(it) }
                p = cur + 6
            } else {
                p = cur
            }
        }
        return result
    }

    private fun isTextFormat(data: ByteArray): Boolean {
        val checkLen = minOf(data.size, 1024)
        var asciiCount = 0
        for (i in 0 until checkLen) {
            val b = data[i].toInt() and 0xFF
            if (b in 32..126 || b == 10 || b == 13 || b == 9) {
                asciiCount++
            }
        }
        return asciiCount.toDouble() / checkLen > 0.85
    }

    private fun parseTextSpeedcam(
        data: ByteArray,
        checkDate: String?,
    ): List<CameraEntity> {
        val text = String(data, Charsets.UTF_8)
        val list = ArrayList<CameraEntity>()
        var lineNum = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//") || line.startsWith("IDX")) {
                continue
            }
            lineNum++
            val parts = line.split(Regex("[,;\t]")).map { it.trim() }
            if (parts.size >= 5) {
                val p0 = parts[0].toDoubleOrNull()
                val p1 = parts[1].toDoubleOrNull()
                val p2 = parts[2].toDoubleOrNull()
                val p3 = parts[3].toIntOrNull()
                val p4 = parts[4].toIntOrNull()

                var lat: Double? = null
                var lon: Double? = null
                var limitKmh: Int? = null
                var kindOrdinal = CameraKind.FIXED.ordinal
                var facingDeg: Double? = null

                if (p1 != null && p2 != null) {
                    if (p1 in 19.0..180.0 && p2 in 35.0..82.0) {
                        lon = p1
                        lat = p2
                        kindOrdinal = mapSubTypeToKind(p3 ?: 1).ordinal
                        limitKmh = p4?.takeIf { it in 10..150 }
                        if (parts.size >= 7) {
                            facingDeg = parts[6].toDoubleOrNull()
                        }
                    } else if (p0 != null && p0 in 35.0..82.0 && p1 in 19.0..180.0) {
                        lat = p0
                        lon = p1
                        limitKmh = p2.toInt().takeIf { it in 10..150 }
                        kindOrdinal = mapSubTypeToKind(p3 ?: 1).ordinal
                        if (p4 != null) facingDeg = p4.toDouble()
                    }
                }

                if (lat != null && lon != null) {
                    list.add(
                        CameraEntity(
                            source = CameraSource.CONTRACAM.ordinal,
                            externalId = "contracam_$lineNum",
                            lat = lat,
                            lon = lon,
                            limitKmh = limitKmh,
                            kind = kindOrdinal,
                            facingDeg = facingDeg,
                            dirCount = 1,
                            operator = "ContraCam",
                            sourceDetail = "text_import",
                            checkDate = checkDate,
                        ),
                    )
                }
            }
        }
        return list
    }

    private fun readVarint(
        data: ByteArray,
        startOffset: Int,
    ): Pair<Long, Int> {
        var cur = startOffset
        var recId = 0L
        var shift = 0
        while (cur < data.size) {
            val b = data[cur].toInt() and 0xFF
            cur++
            recId = recId or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return recId to cur
    }

    /** Parses a single camera record payload at [offset]. */
    private fun parseRecord(
        data: ByteArray,
        offset: Int,
        header: RecordHeader,
        checkDate: String?,
    ): CameraEntity? {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        val b4 = data[offset + 4].toInt() and 0xFF
        val b5 = data[offset + 5].toInt() and 0xFF

        val lat = ((b0 shl 16) or (b1 shl 8) or b2) / DEGREE_SCALE
        val rawLon = ((b3 shl 16) or (b4 shl 8) or b5) / DEGREE_SCALE

        if (lat !in MIN_LAT..MAX_LAT || rawLon !in MIN_LON..MAX_LON) {
            return null
        }
        // Normalize coordinates crossing 180° antimeridian (e.g. Chukotka) to WGS-84 [-180.0, 180.0]
        val lon = if (rawLon > 180.0) rawLon - 360.0 else rawLon

        val facingDeg = ((header.sub shr 5) and 7) * OCTANT_DEGREES
        val speedCode = header.sub and 0x1F
        val limitKmh = mapSpeedCode(speedCode)
        val kind = mapSubTypeToKind(header.subType)

        return CameraEntity(
            source = CameraSource.CONTRACAM.ordinal,
            externalId = "contracam_${header.recId}",
            lat = lat,
            lon = lon,
            limitKmh = limitKmh,
            kind = kind.ordinal,
            facingDeg = facingDeg,
            dirCount = if (header.subType >= 41) 2 else 1,
            operator = "ContraCam",
            sourceDetail = "subType=${header.subType},code=$speedCode",
            checkDate = checkDate,
        )
    }

    /** Maps ContraCam speed limit code to km/h. */
    fun mapSpeedCode(code: Int): Int? {
        val mapped = SPEED_CODE_MAP[code]
        if (mapped != null) return mapped
        return if (code > 0) code * 5 else null
    }

    /** Maps ContraCam camera sub-type to domain [CameraKind]. */
    fun mapSubTypeToKind(subType: Int): CameraKind =
        when (subType) {
            1, 42, 2, 43 -> CameraKind.FIXED
            3, 44 -> CameraKind.AVERAGE_ENTRY
            4, 45 -> CameraKind.AVERAGE_EXIT
            5, 46 -> CameraKind.MOBILE
            6, 47 -> CameraKind.DUMMY
            7, 48 -> CameraKind.RED_LIGHT
            8, 49, 9, 50, 12 -> CameraKind.COMPLEX
            else -> CameraKind.FIXED
        }

    /**
     * Parses cameras from a [file] containing `map.dat` or `speedcam.txt`.
     */
    fun parse(
        file: File,
        checkDate: String? = null,
    ): List<CameraEntity> = parse(file.readBytes(), checkDate)

    /**
     * Parses cameras from an [inputStream].
     */
    fun parse(
        inputStream: InputStream,
        checkDate: String? = null,
    ): List<CameraEntity> = parse(inputStream.readBytes(), checkDate)
}
