package com.speedcam.tools.import.source

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.LatLon
import java.io.File
import java.nio.charset.Charset

/**
 * Parser for the SpeedCam text format used by the Russian community corpus.
 *
 * The format is line-oriented and roughly:
 *
 * ```
 * N55.755327E037.617274     <- position, hemisphere-prefixed degrees
 * X0                         <- index/flags, optional and variable
 * SPEEDCAM                   <- type marker
 * 0,60,2,90,1                <- comma-separated numeric fields
 * ```
 *
 * **The coordinate line and the type marker are reliably identifiable. The numeric field
 * meanings are not.** They vary between publishers and are documented only informally. The
 * parser therefore:
 *
 *  - takes position and type from the lines it can parse with certainty;
 *  - recovers the speed limit by picking the first field in a plausible range, and keeps it
 *    only when exactly one candidate exists;
 *  - deliberately does **not** derive `facingDeg`. A wrong facing direction silently
 *    suppresses legitimate warnings, which is a worse failure than warning too often;
 *  - stores the raw param line in [Camera.sourceDetail], so a corrected parser can be re-run
 *    over stored data without re-fetching the corpus.
 *
 * This corpus is used under the publisher's public terms for personal, non-commercial use.
 * See `NOTICE.md`.
 */
object SpeedCamParser {
    /** Published by the community in Windows-1251; UTF-8 appears in newer exports. */
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    private val COORDINATE = Regex("^([NS])([0-9]+\\.?[0-9]*)([EW])([0-9]+\\.?[0-9]*)$")
    private val INDEX_LINE = Regex("^X[0-9]+$")

    /**
     * Type markers, mapped to the domain taxonomy.
     *
     * The Russian complex systems are mapped to [CameraKind.COMPLEX] rather than
     * [CameraKind.FIXED]: they measure on a gantry across both carriageways, so a warning
     * is not suppressible by approach direction in the way a roadside post is.
     */
    private val TYPE_MARKERS: Map<String, CameraKind> =
        buildMap {
            put("SPEEDCAM", CameraKind.FIXED)
            put("SPEED", CameraKind.FIXED)
            put("POSTA", CameraKind.FIXED)
            put("STREET", CameraKind.UNKNOWN)

            put("TRAFFIC_LIGHTS", CameraKind.RED_LIGHT)
            put("TRAFFIC_LIGHT", CameraKind.RED_LIGHT)
            put("RED", CameraKind.RED_LIGHT)
            put("RLD", CameraKind.RED_LIGHT)

            put("MNOOGI", CameraKind.MOBILE)
            put("MOBILE", CameraKind.MOBILE)

            put("MUTARE", CameraKind.DUMMY)
            put("MULYAZH", CameraKind.DUMMY)
            put("DUMMY", CameraKind.DUMMY)

            put("STRELKA", CameraKind.COMPLEX)
            put("STRELKA_ST", CameraKind.COMPLEX)
            put("KRECHET", CameraKind.COMPLEX)
            put("KORUND", CameraKind.COMPLEX)
            put("PODARKI", CameraKind.COMPLEX)
            put("NOGOTRON", CameraKind.COMPLEX)
            put("POZICIYA", CameraKind.COMPLEX)
            put("TRESLA", CameraKind.COMPLEX)
            put("TRESTAL", CameraKind.COMPLEX)
            put("AREG", CameraKind.COMPLEX)
            put("BERsud", CameraKind.COMPLEX)
            put("RUSLAN", CameraKind.COMPLEX)
        }

    /** Lower bound of a plausible road speed limit, in km/h. */
    private const val MIN_PLAUSIBLE_LIMIT = 5

    /** Upper bound of a plausible road speed limit, in km/h. */
    private const val MAX_PLAUSIBLE_LIMIT = 185

    /** Zero-based index of the speed-limit field in the parameter line. */
    private const val LIMIT_FIELD_INDEX = 1

    fun parse(file: File): List<Camera> {
        require(file.isFile) { "not a file: ${file.path}" }
        return parse(readLines(file))
    }

    /** Visible for testing: parse already-split lines. */
    fun parse(lines: List<String>): List<Camera> {
        val out = mutableListOf<Camera>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index].trim()
            index++

            if (line.isEmpty() || line.startsWith("#") || INDEX_LINE.matches(line)) {
                continue
            }

            // Check if line is CSV format (SpeedCamOnLine / iGO / Primo / Navitel)
            val csvCam = parseCsvLine(line)
            if (csvCam != null) {
                out += csvCam
                continue
            }

            // A line yields a position only if it is a coordinate line; everything else in the
            // file — blank separators, X-index lines, comments — is skipped without branching
            // out of the loop.
            val position = COORDINATE.matchEntire(line)?.let(::toLatLon)

            if (position != null) {
                val block = findTypeBlock(lines, index)
                // Resume after the block so the parameter line is not re-read as a record.
                index = block.nextIndex
                out += buildCamera(position, block.kind, block.paramLine)
            }
        }
        return out
    }

    /**
     * Parses standard CSV line formats:
     * - `IDX,X,Y,TYPE,SPEED,DIRTYPE,DIRECTION` (7+ fields, SpeedCamOnLine / iGO Primo)
     * - `X,Y,TYPE,SPEED,DIRTYPE,DIRECTION` (6 fields, standard iGO)
     */
    internal fun parseCsvLine(line: String): Camera? {
        if (!line.contains(',')) return null
        val parts = line.split(',')
        if (parts.size < 5) return null

        val first = parts[0].trim()
        if (first.equals("IDX", ignoreCase = true) || first.equals("X", ignoreCase = true)) {
            return null
        }

        var extId = ""
        var lonVal: Double? = null
        var latVal: Double? = null
        var typeCode: Int? = null
        var speedVal: Int? = null
        var dirTypeVal: Int? = null
        var dirVal: Double? = null

        if (parts.size >= 7) {
            val p1 = parts[1].trim().toDoubleOrNull()
            val p2 = parts[2].trim().toDoubleOrNull()
            if (p1 != null && p2 != null && p1 in -180.0..180.0 && p2 in -90.0..90.0) {
                extId = first
                lonVal = p1
                latVal = p2
                typeCode = parts[3].trim().toIntOrNull()
                speedVal = parts[4].trim().toIntOrNull()
                dirTypeVal = parts[5].trim().toIntOrNull()
                dirVal = parts[6].trim().toDoubleOrNull()
            }
        }

        if (latVal == null || lonVal == null) {
            val p0 = parts[0].trim().toDoubleOrNull()
            val p1 = parts[1].trim().toDoubleOrNull()
            if (p0 != null && p1 != null && p0 in -180.0..180.0 && p1 in -90.0..90.0) {
                extId = "$p1,$p0"
                lonVal = p0
                latVal = p1
                typeCode = parts.getOrNull(2)?.trim()?.toIntOrNull()
                speedVal = parts.getOrNull(3)?.trim()?.toIntOrNull()
                dirTypeVal = parts.getOrNull(4)?.trim()?.toIntOrNull()
                dirVal = parts.getOrNull(5)?.trim()?.toDoubleOrNull()
            }
        }

        if (latVal == null || lonVal == null) return null

        val kind = mapIgoTypeToKind(typeCode)
        val limit = speedVal?.takeIf { it in MIN_PLAUSIBLE_LIMIT..MAX_PLAUSIBLE_LIMIT }
        val facing = if (dirTypeVal != 0) dirVal?.takeIf { it in 0.0..360.0 } else null

        return Camera(
            id = 0L,
            position = LatLon(latVal, lonVal),
            source = CameraSource.SPEEDCAM,
            externalId = if (extId.isNotBlank()) "sc:$extId" else "sc:$latVal,$lonVal",
            limitKmh = limit,
            kind = kind,
            facingDeg = facing,
            dirCount = if (dirTypeVal == 2) 2 else 1,
            operator = null,
            sourceDetail = line,
            checkDate = null,
        )
    }

    private fun mapIgoTypeToKind(type: Int?): CameraKind =
        when (type) {
            1, 192 -> CameraKind.FIXED
            2, 68 -> CameraKind.RED_LIGHT
            3 -> CameraKind.COMPLEX
            4, 193 -> CameraKind.AVERAGE_ENTRY
            5, 209 -> CameraKind.MOBILE
            199 -> CameraKind.BUS_LANE
            215 -> CameraKind.DUMMY
            227 -> CameraKind.COMPLEX
            else -> CameraKind.FIXED
        }

    /** The type marker attached to a coordinate line, and where parsing should resume. */
    private class TypeBlock(
        val kind: CameraKind,
        val paramLine: String?,
        val nextIndex: Int,
    )

    /**
     * Finds the type marker belonging to the coordinate line just consumed.
     *
     * Publishers vary in what they put between the coordinate and the marker — an index line,
     * blank lines, or nothing — so the search spans a short window rather than a fixed offset.
     */
    private fun findTypeBlock(
        lines: List<String>,
        from: Int,
    ): TypeBlock {
        val windowEnd = minOf(lines.size, from + LOOKAHEAD_LINES)
        var markerAt = -1
        var kind = CameraKind.UNKNOWN

        for (j in from until windowEnd) {
            val candidate = TYPE_MARKERS[lines[j].trim().uppercase()]
            if (candidate != null) {
                markerAt = j
                kind = candidate
                break
            }
        }

        if (markerAt < 0) return TypeBlock(CameraKind.UNKNOWN, null, from)

        // The parameter line, when present, is the next non-blank line after the marker.
        var paramAt = markerAt + 1
        while (paramAt < lines.size && lines[paramAt].isBlank()) paramAt++
        val paramLine = lines.getOrNull(paramAt)?.trim()?.takeIf { it.isNotEmpty() }

        return TypeBlock(kind, paramLine, if (paramLine != null) paramAt + 1 else markerAt + 1)
    }

    private fun buildCamera(
        position: LatLon,
        kind: CameraKind,
        paramLine: String?,
    ): Camera {
        val fields =
            paramLine
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()

        return Camera(
            id = 0L,
            position = position,
            source = CameraSource.SPEEDCAM,
            // No natural identifier in the format, so one is derived from the position. That
            // makes re-imports idempotent and gives the UNIQUE(source, external_id) upsert
            // something stable to key on.
            externalId = "sc:" + position.lat.toString() + "," + position.lon.toString(),
            limitKmh = recoverLimit(fields),
            kind = kind,
            // Intentionally null: see the class comment. Getting this wrong suppresses
            // legitimate warnings.
            facingDeg = null,
            dirCount = 1,
            operator = null,
            sourceDetail = paramLine,
            checkDate = null,
        )
    }

    /**
     * Recovers the speed limit from the numeric fields.
     *
     * Field 1 is the documented position for the limit in the commonly published variants of
     * this format, and it is the only position the parser trusts. When field 1 is absent or
     * implausible the limit is left unknown.
     *
     * An earlier version searched all fields for a single value in road-speed range. That does
     * not work: the direction field also holds values in that range, so `0,60,2,90,1` yielded
     * two candidates and the limit was discarded. Losing the limit disables excess-based
     * suppression for that camera, so a partial parse is better than none.
     */
    internal fun recoverLimit(fields: List<Int>): Int? {
        val candidate =
            when (fields.size) {
                0 -> null
                1 -> fields.single()
                else -> fields[LIMIT_FIELD_INDEX]
            }
        return candidate?.takeIf { it in MIN_PLAUSIBLE_LIMIT..MAX_PLAUSIBLE_LIMIT }
    }

    private fun toLatLon(m: MatchResult): LatLon? {
        val lat = signedDegrees(m.groupValues[1], m.groupValues[2])
        val lon = signedDegrees(m.groupValues[3], m.groupValues[4])
        if (lat == null || lon == null) return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }

    /** Applies the hemisphere sign to a magnitude, or null when the magnitude is unparseable. */
    private fun signedDegrees(
        hemisphere: String,
        magnitude: String,
    ): Double? {
        val value = magnitude.toDoubleOrNull() ?: return null
        return if (hemisphere == "S" || hemisphere == "W") -value else value
    }

    /**
     * Reads the file, preferring UTF-8 and falling back to Windows-1251.
     *
     * Russian SpeedCam exports are commonly Windows-1251; decoding those as UTF-8 yields
     * replacement characters rather than an error, which would silently corrupt any text
     * field. The fallback triggers on the replacement character specifically.
     */
    private fun readLines(file: File): List<String> {
        val bytes = file.readBytes()
        val asUtf8 = String(bytes, Charsets.UTF_8)
        val text =
            if (asUtf8.contains(REPLACEMENT_CHAR)) {
                String(bytes, WINDOWS_1251)
            } else {
                asUtf8
            }
        return text.lineSequence().toList()
    }

    private const val REPLACEMENT_CHAR = '�'

    /** How far past a coordinate line to look for its type marker. */
    private const val LOOKAHEAD_LINES = 6
}
