package com.speedcam.tools.import.db

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Generates verified JSON metadata manifests for released camera databases.
 */
object ManifestGenerator {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    data class ManifestData(
        @SerializedName("version")
        val version: String,
        @SerializedName("built_at")
        val builtAt: String,
        @SerializedName("camera_count")
        val cameraCount: Int,
        @SerializedName("section_count")
        val sectionCount: Int,
        @SerializedName("download_url")
        val downloadUrl: String,
        @SerializedName("sha256")
        val sha256: String,
    )

    /**
     * Calculates the SHA-256 hash of [file] as lowercase hex.
     */
    fun sha256(file: File): String {
        require(file.isFile) { "File does not exist: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads camera and section counts from an existing SQLite database.
     */
    fun readDatabaseCounts(dbFile: File): Pair<Int, Int> {
        require(dbFile.isFile) { "Database file not found: ${dbFile.absolutePath}" }
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                val cameras =
                    st.executeQuery("SELECT COUNT(*) FROM camera").use {
                        if (it.next()) it.getInt(1) else 0
                    }
                val sections =
                    st.executeQuery("SELECT COUNT(*) FROM section").use {
                        if (it.next()) it.getInt(1) else 0
                    }
                return cameras to sections
            }
        }
    }

    /**
     * Reads `built_at` timestamp from the `meta` table of [dbFile], or returns current ISO instant.
     */
    fun readDatabaseBuiltAt(dbFile: File): String =
        try {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT value FROM meta WHERE key = 'built_at'").use {
                        if (it.next()) it.getString(1) else Instant.now().toString()
                    }
                }
            }
        } catch (_: Exception) {
            Instant.now().toString()
        }

    /**
     * Creates and writes a verified [ManifestData] to [manifestFile].
     */
    fun writeManifest(
        dbFile: File,
        manifestFile: File,
        version: String? = null,
        downloadUrl: String? = null,
        cameraCount: Int? = null,
        sectionCount: Int? = null,
        builtAt: String? = null,
    ): ManifestData {
        val hash = sha256(dbFile)
        val (detectedCameras, detectedSections) =
            if (cameraCount == null || sectionCount == null) {
                readDatabaseCounts(dbFile)
            } else {
                cameraCount to sectionCount
            }

        val effectiveBuiltAt = builtAt ?: readDatabaseBuiltAt(dbFile)
        val effectiveVersion =
            version?.takeIf { it.isNotBlank() }
                ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))

        val effectiveDownloadUrl =
            downloadUrl?.takeIf { it.isNotBlank() }
                ?: "https://github.com/Mishakorobov/speedcam/releases/download/db-$effectiveVersion/russia.db"

        val manifest =
            ManifestData(
                version = effectiveVersion,
                builtAt = effectiveBuiltAt,
                cameraCount = cameraCount ?: detectedCameras,
                sectionCount = sectionCount ?: detectedSections,
                downloadUrl = effectiveDownloadUrl,
                sha256 = hash,
            )

        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(gson.toJson(manifest) + "\n", Charsets.UTF_8)
        return manifest
    }
}
