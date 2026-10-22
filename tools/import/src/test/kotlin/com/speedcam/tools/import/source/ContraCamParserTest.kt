package com.speedcam.tools.import.source

import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class ContraCamParserTest {
    @Test
    fun `parses empty and truncated payloads safely`() {
        assertTrue(ContraCamParser.parse(ByteArray(0)).isEmpty())
        assertTrue(ContraCamParser.parse(ByteArray(5) { 0xFF.toByte() }).isEmpty())
    }

    @Test
    fun `maps speed codes and subtypes correctly`() {
        assertEquals(60, ContraCamParser.mapSpeedCode(11))
        assertEquals(90, ContraCamParser.mapSpeedCode(22))
        assertEquals(CameraKind.FIXED, ContraCamParser.mapSubTypeToKind(1))
        assertEquals(CameraKind.MOBILE, ContraCamParser.mapSubTypeToKind(5))
        assertEquals(CameraKind.RED_LIGHT, ContraCamParser.mapSubTypeToKind(7))
        assertEquals(CameraKind.COMPLEX, ContraCamParser.mapSubTypeToKind(8))
    }

    @Test
    fun `parses synthetic binary camera record into domain Camera`() {
        val bos = ByteArrayOutputStream()

        val subType = 1.toByte()
        val tag = 0xA9.toByte()
        val sub = (2 shl 5 or 11).toByte() // 90 deg facing, 60 km/h speed code (11)
        val recId = 1001L

        bos.write(subType.toInt())
        bos.write(tag.toInt())
        bos.write(sub.toInt())

        // LEB128 for 1001: 1001 = 0b1111101001 -> byte 0: (1001 & 0x7F) | 0x80 = 0xE9, byte 1: (1001 >> 7) = 0x07
        bos.write(0xE9)
        bos.write(0x07)

        val targetLat = 55.751244
        val targetLon = 37.618423
        val latUnits = (targetLat * 65536.0).toInt()
        val lonUnits = (targetLon * 65536.0).toInt()

        bos.write(((latUnits shr 16) and 0xFF))
        bos.write(((latUnits shr 8) and 0xFF))
        bos.write((latUnits and 0xFF))

        bos.write(((lonUnits shr 16) and 0xFF))
        bos.write(((lonUnits shr 8) and 0xFF))
        bos.write((lonUnits and 0xFF))

        val cameras = ContraCamParser.parse(bos.toByteArray(), checkDate = "2026-10-22")
        assertEquals(1, cameras.size)

        val cam = cameras.first()
        assertEquals(CameraSource.CONTRACAM, cam.source)
        assertEquals("contracam_1001", cam.externalId)
        assertEquals(targetLat, cam.position.lat, 0.0001)
        assertEquals(targetLon, cam.position.lon, 0.0001)
        assertEquals(60, cam.limitKmh)
        assertEquals(90.0, cam.facingDeg!!, 0.001)
        assertEquals(CameraKind.FIXED, cam.kind)
        assertEquals("2026-10-22", cam.checkDate)
    }
}
