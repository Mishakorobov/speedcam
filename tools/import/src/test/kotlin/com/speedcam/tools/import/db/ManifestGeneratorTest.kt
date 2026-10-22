package com.speedcam.tools.import.db

import com.google.gson.JsonParser
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ManifestGeneratorTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sha256 computes correct digest for file`() {
        val testFile = File(tempDir, "test.txt")
        testFile.writeText("hello speedcam world", Charsets.UTF_8)
        val hash = ManifestGenerator.sha256(testFile)

        // sha256("hello speedcam world") = b9a1f26f2537c35832a842f61e89b4f65ce137e29bc1269ba15469e8b4e7a7e8
        assertNotNull(hash)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `generates valid manifest from SQLite database`() {
        val dbFile = File(tempDir, "test_cameras.db")
        val manifestFile = File(tempDir, "manifest.json")

        // Create a realistic SQLite database with Room schema
        DatabaseWriter.create(dbFile.absolutePath).use { writer ->
            writer.open()
            writer.createSchema()
            writer.replaceCameras(
                listOf(
                    Camera(
                        id = 1,
                        position = LatLon(55.75, 37.61),
                        source = CameraSource.OSM,
                        externalId = "osm_1",
                        limitKmh = 60,
                        kind = CameraKind.FIXED,
                    ),
                    Camera(
                        id = 2,
                        position = LatLon(55.80, 37.70),
                        source = CameraSource.OSM,
                        externalId = "osm_2",
                        limitKmh = 80,
                        kind = CameraKind.COMPLEX,
                    ),
                ),
            )
            writer.replaceSections(
                listOf(
                    SectionRecord(
                        from = LatLon(55.1, 37.1),
                        to = LatLon(55.2, 37.2),
                        limitKmh = 90,
                    ),
                ),
            )
            writer.putMeta("built_at", "2026-10-22T10:00:00Z")
            writer.optimise()
        }

        val manifest =
            ManifestGenerator.writeManifest(
                dbFile = dbFile,
                manifestFile = manifestFile,
                version = "2026.10.22",
                downloadUrl = "https://example.com/russia.db",
            )

        assertEquals("2026.10.22", manifest.version)
        assertEquals(2, manifest.cameraCount)
        assertEquals(1, manifest.sectionCount)
        assertEquals("2026-10-22T10:00:00Z", manifest.builtAt)
        assertEquals("https://example.com/russia.db", manifest.downloadUrl)
        assertEquals(64, manifest.sha256.length)

        assertTrue(manifestFile.isFile)
        val json = JsonParser.parseString(manifestFile.readText()).asJsonObject
        assertEquals("2026.10.22", json.get("version").asString)
        assertEquals(2, json.get("camera_count").asInt)
        assertEquals(1, json.get("section_count").asInt)
        assertEquals(manifest.sha256, json.get("sha256").asString)
    }
}
