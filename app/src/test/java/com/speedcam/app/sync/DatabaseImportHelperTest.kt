package com.speedcam.app.sync

import com.speedcam.core.detect.CameraSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DatabaseImportHelperTest {
    @Test
    fun `extractManifestDownloadUrl extracts url from valid manifest json`() {
        val jsonSnake =
            """
            {
                "version": "1.0",
                "download_url": "https://example.com/mirror/speedcam.db"
            }
            """.trimIndent()
        assertEquals("https://example.com/mirror/speedcam.db", DatabaseImportHelper.extractManifestDownloadUrl(jsonSnake))

        val jsonCamel =
            """
            {
                "version": "1.0",
                "downloadUrl": "https://example.com/mirror/speedcam.db"
            }
            """.trimIndent()
        assertEquals("https://example.com/mirror/speedcam.db", DatabaseImportHelper.extractManifestDownloadUrl(jsonCamel))
    }

    @Test
    fun `extractManifestDownloadUrl returns null for non-manifest json`() {
        val nonManifest = """[{"lat": 55.75, "lon": 37.61}]"""
        assertNull(DatabaseImportHelper.extractManifestDownloadUrl(nonManifest))

        val invalid = """not json"""
        assertNull(DatabaseImportHelper.extractManifestDownloadUrl(invalid))
    }

    @Test
    fun `parseCamerasJson parses flat camera array correctly`() {
        val json =
            """
            [
                {
                    "lat": 55.75124,
                    "lon": 37.61842,
                    "limit_kmh": 60,
                    "kind": 1,
                    "facing_deg": 180.0,
                    "external_id": "cam_moscow_1",
                    "operator": "GIBDD"
                },
                {
                    "lat": 59.93428,
                    "lon": 30.33510,
                    "limitKmh": 80,
                    "kind": 2,
                    "facingDeg": 90.0,
                    "id": "cam_spb_2"
                }
            ]
            """.trimIndent()

        val cameras = DatabaseImportHelper.parseCamerasJson(json)
        assertEquals(2, cameras.size)

        val c1 = cameras[0]
        assertEquals(55.75124, c1.lat)
        assertEquals(37.61842, c1.lon)
        assertEquals(60, c1.limitKmh)
        assertEquals(CameraSource.OSM.ordinal, c1.source)
        assertEquals(180.0, c1.facingDeg)
        assertEquals("cam_moscow_1", c1.externalId)
        assertEquals("GIBDD", c1.operator)

        val c2 = cameras[1]
        assertEquals(59.93428, c2.lat)
        assertEquals(30.33510, c2.lon)
        assertEquals(80, c2.limitKmh)
        assertEquals(90.0, c2.facingDeg)
        assertEquals("cam_spb_2", c2.externalId)
    }

    @Test
    fun `parseCamerasJson parses object containing cameras array`() {
        val json =
            """
            {
                "version": "2026.10",
                "cameras": [
                    {
                        "lat": 55.75,
                        "lon": 37.61,
                        "limit_kmh": 40
                    }
                ]
            }
            """.trimIndent()

        val cameras = DatabaseImportHelper.parseCamerasJson(json)
        assertEquals(1, cameras.size)
        assertEquals(55.75, cameras[0].lat)
        assertEquals(37.61, cameras[0].lon)
        assertEquals(40, cameras[0].limitKmh)
    }

    @Test
    fun `parseCamerasJson ignores elements without lat and lon`() {
        val json =
            """
            [
                { "name": "Invalid point without coordinates" },
                { "lat": 55.0, "lon": 37.0 }
            ]
            """.trimIndent()

        val cameras = DatabaseImportHelper.parseCamerasJson(json)
        assertEquals(1, cameras.size)
        assertEquals(55.0, cameras[0].lat)
    }
}
