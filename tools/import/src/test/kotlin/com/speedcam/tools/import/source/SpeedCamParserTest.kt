package com.speedcam.tools.import.source

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedCamParserTest {
    private fun parse(vararg lines: String) = SpeedCamParser.parse(lines.toList())

    @Test
    fun `parses a plain speed camera record`() {
        val cameras =
            parse(
                "N55.755327E037.617274",
                "X0",
                "SPEEDCAM",
                "0,60,2,90,1",
            )

        assertEquals(1, cameras.size)
        val c = cameras.single()
        assertEquals(55.755327, c.position.lat, 1e-6)
        assertEquals(37.617274, c.position.lon, 1e-6)
        assertEquals(60, c.limitKmh)
        assertEquals(com.speedcam.core.detect.CameraKind.FIXED, c.kind)
    }

    @Test
    fun `handles southern and western hemispheres`() {
        val c = parse("S33.868800E151.209300", "SPEEDCAM", "60").single()
        assertEquals(-33.868800, c.position.lat, 1e-6)
        assertEquals(151.209300, c.position.lon, 1e-6)
    }

    @Test
    fun `applies the western hemisphere sign`() {
        val c = parse("N55.000000W070.000000", "SPEEDCAM", "60").single()
        assertEquals(55.0, c.position.lat, 1e-6)
        assertEquals(-70.0, c.position.lon, 1e-6)
    }

    @Test
    fun `maps known type markers to kinds`() {
        val cases =
            mapOf(
                "TRAFFIC_LIGHTS" to com.speedcam.core.detect.CameraKind.RED_LIGHT,
                "MNOOGI" to com.speedcam.core.detect.CameraKind.MOBILE,
                "MUTARE" to com.speedcam.core.detect.CameraKind.DUMMY,
                "STRELKA" to com.speedcam.core.detect.CameraKind.COMPLEX,
            )
        for ((marker, expected) in cases) {
            val c = parse("N55.0E37.0", marker, "60").single()
            assertEquals(expected, c.kind, "marker $marker")
        }
    }

    @Test
    fun `type markers are case insensitive`() {
        assertEquals(1, parse("N55.0E37.0", "speedcam", "60").size)
        assertEquals(
            com.speedcam.core.detect.CameraKind.FIXED,
            parse("N55.0E37.0", "speedcam", "60").single().kind,
        )
    }

    @Test
    fun `parses consecutive records without swallowing the file`() {
        val cameras =
            parse(
                "N55.000000E037.000000",
                "SPEEDCAM",
                "60",
                "N56.000000E038.000000",
                "SPEEDCAM",
                "90",
                "N57.000000E039.000000",
                "SPEEDCAM",
                "40",
            )

        assertEquals(3, cameras.size)
        assertEquals(listOf(55.0, 56.0, 57.0), cameras.map { it.position.lat })
        assertEquals(listOf(60, 90, 40), cameras.map { it.limitKmh })
    }

    @Test
    fun `skips unrelated lines without emitting records`() {
        val cameras =
            parse(
                "# a comment",
                "",
                "not a coordinate",
                "N55.0E37.0",
                "SPEEDCAM",
                "60",
            )
        assertEquals(1, cameras.size)
    }

    @Test
    fun `records out-of-range coordinates are rejected`() {
        assertTrue(parse("N95.0E37.0", "SPEEDCAM", "60").isEmpty())
        assertTrue(parse("N55.0E200.0", "SPEEDCAM", "60").isEmpty())
    }

    @Test
    fun `an unknown marker yields an UNKNOWN kind but keeps the record`() {
        val c = parse("N55.0E37.0", "SOMETHING_ELSE", "60").single()
        assertEquals(com.speedcam.core.detect.CameraKind.UNKNOWN, c.kind)
        assertEquals(55.0, c.position.lat, 1e-9)
    }

    @Test
    fun `never invents a facing direction`() {
        // A wrong facing angle silently suppresses legitimate warnings, so the parser
        // deliberately leaves it null rather than guessing from the param line.
        val c = parse("N55.0E37.0", "SPEEDCAM", "0,60,2,90,1").single()
        assertNull(c.facingDeg)
    }

    @Test
    fun `keeps the raw param line for later re-parsing`() {
        val c = parse("N55.0E37.0", "SPEEDCAM", "0,60,2,90,1").single()
        assertEquals("0,60,2,90,1", c.sourceDetail)
    }

    @Test
    fun `an implausible limit in the documented position is rejected`() {
        // Field 1 is the documented position. If it is not a road speed, the limit is unknown
        // rather than taken from some other field that happens to look plausible.
        assertNull(SpeedCamParser.recoverLimit(listOf(0, 2, 90, 1)))
        assertNull(SpeedCamParser.recoverLimit(listOf(0, 500, 90)))
        assertNull(SpeedCamParser.recoverLimit(listOf(0, -60, 90)))
    }

    @Test
    fun `a direction field that looks like a limit does not confuse the parser`() {
        // Regression: an earlier heuristic searched all fields for a value in road-speed
        // range and found two candidates here, discarding the limit entirely.
        assertEquals(60, SpeedCamParser.recoverLimit(listOf(0, 60, 2, 90, 1)))
    }

    @Test
    fun `a single-field record carries only the limit`() {
        assertEquals(60, SpeedCamParser.recoverLimit(listOf(60)))
        assertNull(SpeedCamParser.recoverLimit(listOf(3)))
    }

    @Test
    fun `a record with no recognisable limit yields null`() {
        assertNull(SpeedCamParser.recoverLimit(emptyList()))
    }

    @Test
    fun `external id is derived from position so re-imports are idempotent`() {
        val first = parse("N55.123456E037.123456", "SPEEDCAM", "60").single()
        val second = parse("N55.123456E037.123456", "SPEEDCAM", "60").single()
        assertEquals(first.externalId, second.externalId)
        assertNotNull(first.externalId)
        assertTrue(first.externalId!!.startsWith("sc:"))
    }

    @Test
    fun `parses standard CSV iGO Primo speedcam format with IDX`() {
        val cameras =
            parse(
                "IDX,X,Y,TYPE,SPEED,DIRTYPE,DIRECTION",
                "34688,85.9056840,51.9722265,192,60,1,142",
                "10173,85.9319176,51.9542595,68,60,1,76",
                "10182,85.8936807,51.9765467,192,40,1,154",
            )
        assertEquals(3, cameras.size)
        assertEquals(51.9722265, cameras[0].position.lat, 1e-6)
        assertEquals(85.9056840, cameras[0].position.lon, 1e-6)
        assertEquals(60, cameras[0].limitKmh)
        assertEquals(com.speedcam.core.detect.CameraKind.FIXED, cameras[0].kind)
        assertEquals(142.0, cameras[0].facingDeg)

        assertEquals(51.9542595, cameras[1].position.lat, 1e-6)
        assertEquals(com.speedcam.core.detect.CameraKind.RED_LIGHT, cameras[1].kind)
        assertEquals(76.0, cameras[1].facingDeg)

        assertEquals(40, cameras[2].limitKmh)
    }

    @Test
    fun `parses standard CSV iGO format without IDX`() {
        val cameras =
            parse(
                "X,Y,TYPE,SPEED,DIRTYPE,DIRECTION",
                "85.9056840,51.9722265,1,60,1,142",
            )
        assertEquals(1, cameras.size)
        val c = cameras.single()
        assertEquals(51.9722265, c.position.lat, 1e-6)
        assertEquals(85.9056840, c.position.lon, 1e-6)
        assertEquals(60, c.limitKmh)
        assertEquals(com.speedcam.core.detect.CameraKind.FIXED, c.kind)
    }
}
