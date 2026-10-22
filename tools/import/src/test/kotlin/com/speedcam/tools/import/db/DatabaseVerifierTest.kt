package com.speedcam.tools.import.db

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

class DatabaseVerifierTest {
    @TempDir
    lateinit var tempDir: File

    private fun createValidDb(dbFile: File) {
        DatabaseWriter.create(dbFile.absolutePath).use { writer ->
            writer.open()
            writer.createSchema()
            writer.replaceCameras(
                listOf(
                    Camera(
                        id = 1,
                        position = LatLon(55.75, 37.61),
                        source = CameraSource.OSM,
                        limitKmh = 60,
                        kind = CameraKind.FIXED,
                    ),
                ),
            )
            writer.putMeta("built_at", "2026-10-22T10:00:00Z")
            writer.optimise()
        }
    }

    @Test
    fun `verifies valid database and manifest successfully`() {
        val dbFile = File(tempDir, "valid.db")
        val manifestFile = File(tempDir, "manifest.json")
        createValidDb(dbFile)

        ManifestGenerator.writeManifest(
            dbFile = dbFile,
            manifestFile = manifestFile,
            version = "2026.10.22",
        )

        val result = DatabaseVerifier.verify(dbFile, manifestFile)
        assertTrue(result.ok, "Expected verify to pass: ${result.errors}")
        assertTrue(result.manifestVerified)
        assertTrue(result.integrity.equals("ok", ignoreCase = true))
        assertTrue(result.cameraCount > 0)
    }

    @Test
    fun `detects invalid schema identity hash`() {
        val dbFile = File(tempDir, "bad_schema.db")
        createValidDb(dbFile)

        // Corrupt room_master_table identity hash
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("UPDATE room_master_table SET identity_hash = 'invalid_hash' WHERE id=42")
            }
        }

        val result = DatabaseVerifier.verify(dbFile)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("Room schema identity hash mismatch") })
    }

    @Test
    fun `detects manifest sha256 mismatch`() {
        val dbFile = File(tempDir, "valid.db")
        val manifestFile = File(tempDir, "manifest.json")
        createValidDb(dbFile)

        ManifestGenerator.writeManifest(
            dbFile = dbFile,
            manifestFile = manifestFile,
            version = "2026.10.22",
        )

        // Corrupt manifest sha256
        val tamperedJson = manifestFile.readText().replace(Regex("\"sha256\": \"[^\"]+\""), "\"sha256\": \"000000000000\"")
        manifestFile.writeText(tamperedJson)

        val result = DatabaseVerifier.verify(dbFile, manifestFile)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("Manifest SHA-256") })
    }
}
