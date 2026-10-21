package com.speedcam.core.data.contracam

import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ContraCamBinaryParserTest {
    @Test
    fun mapSpeedCode_returnsExpectedLimits() {
        assertNull(ContraCamBinaryParser.mapSpeedCode(0))
        assertNull(ContraCamBinaryParser.mapSpeedCode(2))
        assertEquals(10, ContraCamBinaryParser.mapSpeedCode(1))
        assertEquals(20, ContraCamBinaryParser.mapSpeedCode(3))
        assertEquals(30, ContraCamBinaryParser.mapSpeedCode(5))
        assertEquals(40, ContraCamBinaryParser.mapSpeedCode(7))
        assertEquals(50, ContraCamBinaryParser.mapSpeedCode(9))
        assertEquals(60, ContraCamBinaryParser.mapSpeedCode(11))
        assertEquals(70, ContraCamBinaryParser.mapSpeedCode(15))
        assertEquals(80, ContraCamBinaryParser.mapSpeedCode(19))
        assertEquals(90, ContraCamBinaryParser.mapSpeedCode(23))
        assertEquals(100, ContraCamBinaryParser.mapSpeedCode(25))
        assertEquals(110, ContraCamBinaryParser.mapSpeedCode(27))
        assertEquals(120, ContraCamBinaryParser.mapSpeedCode(29))
        assertEquals(130, ContraCamBinaryParser.mapSpeedCode(31))
    }

    @Test
    fun mapSubTypeToKind_mapsAccurately() {
        assertEquals(CameraKind.FIXED, ContraCamBinaryParser.mapSubTypeToKind(1))
        assertEquals(CameraKind.FIXED, ContraCamBinaryParser.mapSubTypeToKind(42))
        assertEquals(CameraKind.AVERAGE_ENTRY, ContraCamBinaryParser.mapSubTypeToKind(3))
        assertEquals(CameraKind.AVERAGE_ENTRY, ContraCamBinaryParser.mapSubTypeToKind(44))
        assertEquals(CameraKind.AVERAGE_EXIT, ContraCamBinaryParser.mapSubTypeToKind(4))
        assertEquals(CameraKind.AVERAGE_EXIT, ContraCamBinaryParser.mapSubTypeToKind(45))
        assertEquals(CameraKind.MOBILE, ContraCamBinaryParser.mapSubTypeToKind(5))
        assertEquals(CameraKind.DUMMY, ContraCamBinaryParser.mapSubTypeToKind(6))
        assertEquals(CameraKind.RED_LIGHT, ContraCamBinaryParser.mapSubTypeToKind(7))
        assertEquals(CameraKind.COMPLEX, ContraCamBinaryParser.mapSubTypeToKind(8))
        assertEquals(CameraKind.COMPLEX, ContraCamBinaryParser.mapSubTypeToKind(9))
        assertEquals(CameraKind.COMPLEX, ContraCamBinaryParser.mapSubTypeToKind(12))
    }

    @Test
    fun parse_validSpeedCamRecord_parsesFieldsCorrectly() {
        // Moscow Kremlin coordinates
        val targetLat = 55.751244
        val targetLon = 37.618423
        val latUnits = (targetLat * 65536.0).toInt()
        val lonUnits = (targetLon * 65536.0).toInt()

        val recordBytes =
            byteArrayOf(
                // Prefix noise
                0x00,
                0x11,
                0x22,
                // Record
                0x01, // subType = 1 (Fixed)
                0xA9.toByte(), // SpeedCam tag
                ((2 shl 5) or 11).toByte(), // Octant 2 (90 deg), speedCode 11 (60 km/h)
                0x2A, // Record ID varint = 42
                // 3 bytes lat
                ((latUnits shr 16) and 0xFF).toByte(),
                ((latUnits shr 8) and 0xFF).toByte(),
                (latUnits and 0xFF).toByte(),
                // 3 bytes lon
                ((lonUnits shr 16) and 0xFF).toByte(),
                ((lonUnits shr 8) and 0xFF).toByte(),
                (lonUnits and 0xFF).toByte(),
                // Suffix noise
                0x00,
                0x7F,
            )

        val cameras = ContraCamBinaryParser.parse(recordBytes, checkDate = "2026-10-21")
        assertEquals(1, cameras.size)

        val cam = cameras.first()
        assertEquals(CameraSource.CONTRACAM.ordinal, cam.source)
        assertEquals("contracam_42", cam.externalId)
        assertEquals(CameraKind.FIXED.ordinal, cam.kind)
        assertEquals(60, cam.limitKmh)
        assertEquals(90.0, cam.facingDeg!!, 0.01)
        assertEquals(1, cam.dirCount)
        assertEquals("2026-10-21", cam.checkDate)
        assertTrue(abs(cam.lat - targetLat) < 0.0001)
        assertTrue(abs(cam.lon - targetLon) < 0.0001)
    }

    @Test
    fun parse_bidirectionalCamera_setsDirCountTwo() {
        val targetLat = 55.0
        val targetLon = 37.0
        val latUnits = (targetLat * 65536.0).toInt()
        val lonUnits = (targetLon * 65536.0).toInt()

        val recordBytes =
            byteArrayOf(
                42.toByte(), // subType = 42 (Backshot / Bidirectional)
                0xA9.toByte(),
                ((0 shl 5) or 19).toByte(), // Octant 0 (0 deg), speedCode 19 (80 km/h)
                0x05, // ID = 5
                ((latUnits shr 16) and 0xFF).toByte(),
                ((latUnits shr 8) and 0xFF).toByte(),
                (latUnits and 0xFF).toByte(),
                ((lonUnits shr 16) and 0xFF).toByte(),
                ((lonUnits shr 8) and 0xFF).toByte(),
                (lonUnits and 0xFF).toByte(),
            )

        val cameras = ContraCamBinaryParser.parse(recordBytes)
        assertEquals(1, cameras.size)
        assertEquals(2, cameras[0].dirCount)
        assertEquals(80, cameras[0].limitKmh)
        assertEquals(0.0, cameras[0].facingDeg!!, 0.01)
    }

    @Test
    fun parse_outOfBoundsCoordinates_areFilteredOut() {
        val invalidLatUnits = (10.0 * 65536.0).toInt() // Outside Russia/EU bounds [30, 85]
        val validLonUnits = (37.0 * 65536.0).toInt()

        val recordBytes =
            byteArrayOf(
                1.toByte(),
                0xA9.toByte(),
                11.toByte(),
                1.toByte(),
                ((invalidLatUnits shr 16) and 0xFF).toByte(),
                ((invalidLatUnits shr 8) and 0xFF).toByte(),
                (invalidLatUnits and 0xFF).toByte(),
                ((validLonUnits shr 16) and 0xFF).toByte(),
                ((validLonUnits shr 8) and 0xFF).toByte(),
                (validLonUnits and 0xFF).toByte(),
            )

        val cameras = ContraCamBinaryParser.parse(recordBytes)
        assertTrue(cameras.isEmpty())
    }

    @Test
    fun parse_emptyOrGarbageData_returnsEmptyListWithoutCrashing() {
        assertTrue(ContraCamBinaryParser.parse(ByteArray(0)).isEmpty())
        assertTrue(ContraCamBinaryParser.parse(ByteArray(10) { 0xFF.toByte() }).isEmpty())
        assertTrue(ContraCamBinaryParser.parse(byteArrayOf(0xA9.toByte())).isEmpty())
    }
}
