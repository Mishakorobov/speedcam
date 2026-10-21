package com.speedcam.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class DatabaseSyncEngineTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `parses valid database manifest JSON`() {
        val json =
            """
            {
                "version": "2026.10.21",
                "built_at": "2026-10-21T12:00:00Z",
                "camera_count": 11426,
                "section_count": 42,
                "download_url": "https://example.com/speedcam.db",
                "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            }
            """.trimIndent()

        val manifest = DatabaseSyncEngine.parseManifestJson(json)

        assertEquals("2026.10.21", manifest.version)
        assertEquals("2026-10-21T12:00:00Z", manifest.builtAt)
        assertEquals(11426, manifest.cameraCount)
        assertEquals(42, manifest.sectionCount)
        assertEquals("https://example.com/speedcam.db", manifest.downloadUrl)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", manifest.sha256)
    }

    @Test
    fun `parses manifest with optional fields omitted`() {
        val json =
            """
            {
                "download_url": "https://example.com/speedcam.db"
            }
            """.trimIndent()

        val manifest = DatabaseSyncEngine.parseManifestJson(json)

        assertEquals("", manifest.version)
        assertEquals(0, manifest.cameraCount)
        assertNull(manifest.sha256)
        assertEquals("https://example.com/speedcam.db", manifest.downloadUrl)
    }

    @Test
    fun `sha256 calculation matches expected digest`() {
        val testFile = File(tempDir, "test.bin")
        val data = "SpeedCam SQLite test payload".toByteArray()
        testFile.writeBytes(data)

        val expectedSha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(data)
                .joinToString("") { "%02x".format(it) }

        val actualSha =
            testFile.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1024)
                var r: Int
                while (input.read(buf).also { r = it } != -1) {
                    digest.update(buf, 0, r)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }

        assertEquals(expectedSha, actualSha)
    }
}
