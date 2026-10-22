package com.speedcam.tools.import.source

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.LatLon
import java.io.File
import java.io.InputStream

/**
 * Streaming parser for proprietary ContraCam speed camera binary archives (`map.dat` / `*.dat`).
 *
 * Produces pure domain [Camera] instances with [CameraSource.CONTRACAM].
 */
object ContraCamParser {
    private const val SPEEDCAM_RECORD_TAG = 0xA9.toByte()
    private const val DEGREE_SCALE = 65536.0
    private const val OCTANT_DEGREES = 45.0

    private const val MIN_LAT = 30.0
    private const val MAX_LAT = 85.0
    private const val MIN_LON = 10.0
    private const val MAX_LON = 190.0

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

    fun parse(
        data: ByteArray,
        checkDate: String? = null,
    ): List<Camera> {
        val result = ArrayList<Camera>(data.size / 300)
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

    private fun parseRecord(
        data: ByteArray,
        offset: Int,
        header: RecordHeader,
        checkDate: String?,
    ): Camera? {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        val b4 = data[offset + 4].toInt() and 0xFF
        val b5 = data[offset + 5].toInt() and 0xFF

        val lat = ((b0 shl 16) or (b1 shl 8) or b2) / DEGREE_SCALE
        val lon = ((b3 shl 16) or (b4 shl 8) or b5) / DEGREE_SCALE

        if (lat !in MIN_LAT..MAX_LAT || lon !in MIN_LON..MAX_LON) {
            return null
        }

        val facingDeg = ((header.sub shr 5) and 7) * OCTANT_DEGREES
        val speedCode = header.sub and 0x1F
        val limitKmh = mapSpeedCode(speedCode)
        val kind = mapSubTypeToKind(header.subType)

        return Camera(
            id = 0L,
            position = LatLon(lat, lon),
            source = CameraSource.CONTRACAM,
            externalId = "contracam_${header.recId}",
            limitKmh = limitKmh,
            kind = kind,
            facingDeg = facingDeg,
            dirCount = if (header.subType >= 41) 2 else 1,
            operator = "ContraCam",
            sourceDetail = "subType=${header.subType},code=$speedCode",
            checkDate = checkDate,
        )
    }

    fun mapSpeedCode(code: Int): Int? {
        val mapped = SPEED_CODE_MAP[code]
        if (mapped != null) return mapped
        return if (code > 0) code * 5 else null
    }

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

    fun parse(
        file: File,
        checkDate: String? = null,
    ): List<Camera> = parse(file.readBytes(), checkDate)

    fun parse(
        inputStream: InputStream,
        checkDate: String? = null,
    ): List<Camera> = parse(inputStream.readBytes(), checkDate)
}
