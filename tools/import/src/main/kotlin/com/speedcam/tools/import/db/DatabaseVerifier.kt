package com.speedcam.tools.import.db

import com.google.gson.JsonParser
import java.io.File
import java.sql.DriverManager

/**
 * Validates database integrity, Room schema compatibility and manifest checksum parity.
 */
object DatabaseVerifier {
    data class VerifyResult(
        val ok: Boolean,
        val integrity: String,
        val schemaHash: String,
        val cameraCount: Int,
        val sectionCount: Int,
        val sha256: String,
        val manifestVerified: Boolean,
        val errors: List<String> = emptyList(),
    ) {
        fun formatReport(): String =
            buildString {
                appendLine("Database verification:")
                appendLine("  Integrity: $integrity")
                appendLine("  Schema hash: $schemaHash (expected ${DatabaseWriter.IDENTITY_HASH})")
                appendLine("  Cameras: $cameraCount")
                appendLine("  Sections: $sectionCount")
                appendLine("  SHA-256: $sha256")
                appendLine("  Manifest verified: $manifestVerified")
                if (errors.isNotEmpty()) {
                    appendLine("  ERRORS:")
                    errors.forEach { appendLine("   - $it") }
                } else {
                    appendLine("  Status: PASSED OK")
                }
            }
    }

    /**
     * Verifies the SQLite database at [dbFile] and optional [manifestFile].
     */
    fun verify(
        dbFile: File,
        manifestFile: File? = null,
    ): VerifyResult {
        val errors = mutableListOf<String>()
        if (!dbFile.isFile || dbFile.length() == 0L) {
            return VerifyResult(
                ok = false,
                integrity = "FILE_MISSING",
                schemaHash = "",
                cameraCount = 0,
                sectionCount = 0,
                sha256 = "",
                manifestVerified = false,
                errors = listOf("Database file missing or empty: ${dbFile.absolutePath}"),
            )
        }

        var integrity = "unknown"
        var schemaHash = ""
        var cameraCount = 0
        var sectionCount = 0

        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    integrity =
                        st.executeQuery("PRAGMA integrity_check;").use {
                            if (it.next()) it.getString(1) else "no_response"
                        }
                    if (!integrity.equals("ok", ignoreCase = true)) {
                        errors += "SQLite integrity check failed: $integrity"
                    }

                    schemaHash =
                        st.executeQuery("SELECT identity_hash FROM room_master_table WHERE id=42;").use {
                            if (it.next()) it.getString(1) else "missing"
                        }
                    if (schemaHash != DatabaseWriter.IDENTITY_HASH) {
                        errors += "Room schema identity hash mismatch: got '$schemaHash', expected '${DatabaseWriter.IDENTITY_HASH}'"
                    }

                    cameraCount =
                        st.executeQuery("SELECT COUNT(*) FROM camera;").use {
                            if (it.next()) it.getInt(1) else 0
                        }
                    if (cameraCount == 0) {
                        errors += "Database contains 0 cameras"
                    }

                    sectionCount =
                        st.executeQuery("SELECT COUNT(*) FROM section;").use {
                            if (it.next()) it.getInt(1) else 0
                        }
                }
            }
        } catch (e: Exception) {
            errors += "Database access error: ${e.message}"
        }

        val actualSha256 =
            try {
                ManifestGenerator.sha256(dbFile)
            } catch (e: Exception) {
                errors += "SHA-256 calculation failed: ${e.message}"
                ""
            }

        var manifestVerified = false
        if (manifestFile != null) {
            if (!manifestFile.isFile) {
                errors += "Manifest file does not exist: ${manifestFile.absolutePath}"
            } else {
                try {
                    val json = JsonParser.parseString(manifestFile.readText()).asJsonObject
                    val manifestSha256 = json.get("sha256")?.asString ?: ""
                    val manifestCameras = json.get("camera_count")?.asInt ?: json.get("cameraCount")?.asInt ?: -1
                    val manifestSections = json.get("section_count")?.asInt ?: json.get("sectionCount")?.asInt ?: -1

                    if (!actualSha256.equals(manifestSha256.trim(), ignoreCase = true)) {
                        errors += "Manifest SHA-256 ($manifestSha256) does not match database SHA-256 ($actualSha256)"
                    }
                    if (manifestCameras != cameraCount) {
                        errors += "Manifest camera count ($manifestCameras) does not match database ($cameraCount)"
                    }
                    if (manifestSections != sectionCount) {
                        errors += "Manifest section count ($manifestSections) does not match database ($sectionCount)"
                    }
                    if (errors.isEmpty()) {
                        manifestVerified = true
                    }
                } catch (e: Exception) {
                    errors += "Failed to parse manifest: ${e.message}"
                }
            }
        }

        return VerifyResult(
            ok = errors.isEmpty(),
            integrity = integrity,
            schemaHash = schemaHash,
            cameraCount = cameraCount,
            sectionCount = sectionCount,
            sha256 = actualSha256,
            manifestVerified = manifestVerified,
            errors = errors,
        )
    }
}
