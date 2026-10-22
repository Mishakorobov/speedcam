package com.speedcam.app.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `formatErrorMessage formats network errors into readable Russian messages`() {
        val unknownHost = java.net.UnknownHostException("raw.githubusercontent.com")
        val offlineMsg = DatabaseSyncEngine.formatErrorMessage(unknownHost)
        assertTrue(offlineMsg.contains("отсутствует подключение") || offlineMsg.contains("Не удалось подключиться"))

        val timeout = java.net.SocketTimeoutException("timeout")
        val timeoutMsg = DatabaseSyncEngine.formatErrorMessage(timeout)
        assertTrue(timeoutMsg.contains("Время ожидания"))

        val http404 = java.io.IOException("HTTP 404 from https://example.com/manifest.json")
        val notFoundMsg = DatabaseSyncEngine.formatErrorMessage(http404)
        assertTrue(notFoundMsg.contains("404") && notFoundMsg.contains("не найден"))

        val http503 = java.io.IOException("HTTP 503 from https://example.com/manifest.json")
        val serverErrorMsg = DatabaseSyncEngine.formatErrorMessage(http503)
        assertTrue(serverErrorMsg.contains("временно недоступен"))

        val security = SecurityException("SHA-256 mismatch")
        val securityMsg = DatabaseSyncEngine.formatErrorMessage(security)
        assertTrue(securityMsg.contains("целостности") || securityMsg.contains("SHA-256"))
    }

    @Test
    fun `known mirrors contain default manifest url and alternative mirror`() {
        assertTrue(DatabaseSyncEngine.KNOWN_MIRRORS.isNotEmpty())
        assertEquals(DatabaseSyncEngine.DEFAULT_MANIFEST_URL, DatabaseSyncEngine.KNOWN_MIRRORS[0])
        assertTrue(DatabaseSyncEngine.KNOWN_MIRRORS.size >= 2)
    }
}
