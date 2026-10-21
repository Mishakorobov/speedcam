package com.speedcam.app.sync

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.speedcam.app.SpeedCamApp
import com.speedcam.core.data.CameraDatabaseInstaller
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Metadata manifest published alongside an updated camera database. */
data class DatabaseManifest(
    val version: String,
    val builtAt: String,
    val cameraCount: Int,
    val sectionCount: Int = 0,
    val downloadUrl: String,
    val sha256: String? = null,
)

/** The result category of a database synchronization attempt. */
enum class SyncOutcome {
    /** A new database was successfully downloaded, verified and installed. */
    UPDATED,

    /** The local database is already at or newer than the remote build. */
    ALREADY_UP_TO_DATE,

    /** The sync operation failed (network error, invalid file, or verification failure). */
    FAILED,
}

/** Detailed result of a sync operation. */
data class SyncResult(
    val outcome: SyncOutcome,
    val cameraCount: Int = 0,
    val message: String = "",
)

/**
 * Executes remote database checks, downloads, integrity validation and atomic replacement.
 *
 * Implements Stage 8/9 requirements:
 *  - Remote manifest parsing with version and freshness comparison
 *  - Streaming download with on-the-fly SHA-256 checksum verification
 *  - User `override_` row preservation across database replacements
 *  - Atomic file swap via [CameraDatabaseInstaller] with automatic rollback on error
 *  - Notifies [ServiceController] so the running detection pipeline refreshes immediately
 */
class DatabaseSyncEngine(
    private val context: Context,
) {
    /**
     * Checks the remote URL and applies an update if a newer build is available.
     *
     * @param url remote manifest URL (.json) or direct database URL (.db).
     */
    suspend fun sync(url: String = DEFAULT_MANIFEST_URL): SyncResult {
        if (url.isBlank()) {
            return SyncResult(SyncOutcome.FAILED, message = "No update URL configured")
        }

        val cacheDir = context.cacheDir
        val tempCandidate = File(cacheDir, "speedcam-update-${System.currentTimeMillis()}.candidate")

        return try {
            val isDirectDb = url.endsWith(".db", ignoreCase = true)
            val (downloadUrl, expectedSha256) =
                if (isDirectDb) {
                    url to null
                } else {
                    val manifest = fetchManifest(url)
                    val currentBuiltAt = SpeedCamApp.cameraStore(context).databaseBuiltAt()
                    if (currentBuiltAt != null && currentBuiltAt.isNotBlank() && currentBuiltAt >= manifest.builtAt) {
                        return SyncResult(
                            outcome = SyncOutcome.ALREADY_UP_TO_DATE,
                            message = "Database is up to date (current: ${currentBuiltAt.take(10)})",
                        )
                    }
                    manifest.downloadUrl to manifest.sha256
                }

            val downloadResult = downloadFile(downloadUrl, tempCandidate, expectedSha256)
            if (downloadResult.isFailure) {
                tempCandidate.delete()
                return SyncResult(
                    outcome = SyncOutcome.FAILED,
                    message = "Download failed: ${downloadResult.exceptionOrNull()?.message}",
                )
            }

            applyCandidateDatabase(tempCandidate)
        } catch (e: Throwable) {
            Log.w(TAG, "Sync error: ${e.message}", e)
            SyncResult(outcome = SyncOutcome.FAILED, message = e.message ?: "Unknown sync error")
        } finally {
            tempCandidate.delete()
        }
    }

    /**
     * Replaces the active database with [candidateFile], preserving user overrides.
     *
     * Validates SQLite structure, closes Room handles, performs atomic rename, checks
     * resulting database viability, restores user blocks/additions, and rolls back
     * to the previous database if any step fails.
     */
    suspend fun applyCandidateDatabase(candidateFile: File): SyncResult {
        if (!CameraDatabaseInstaller.looksLikeSqliteDatabase(candidateFile)) {
            candidateFile.delete()
            return SyncResult(
                outcome = SyncOutcome.FAILED,
                message = "The candidate database is not a valid SQLite file.",
            )
        }

        // 1. Snapshot current user overrides before modifying database files
        val existingStore = SpeedCamApp.cameraStore(context)
        val preservedOverrides =
            runCatching { existingStore.overrideDao.all() }.getOrDefault(emptyList())

        // 2. Release open Room handles so file descriptors are freed
        SpeedCamApp.closeCameraStore(context)

        // 3. Atomically replace database file
        val installer = CameraDatabaseInstaller.forContext(context)
        val swapResult = installer.replaceWith(candidateFile)
        if (swapResult.isFailure) {
            SpeedCamApp.reloadCameraStore(context)
            return SyncResult(
                outcome = SyncOutcome.FAILED,
                message = "Failed to replace database: ${swapResult.exceptionOrNull()?.message}",
            )
        }

        // 4. Verify that Room can open the replacement database and restore overrides
        return try {
            val newStore = SpeedCamApp.reloadCameraStore(context)
            val newCount = newStore.cameraCount()
            check(newCount > 0) { "New database contains 0 cameras" }

            if (preservedOverrides.isNotEmpty()) {
                newStore.overrideDao.upsertAll(preservedOverrides)
            }
            newStore.refreshOverrides()
            newStore.refreshSections()

            installer.discardSupersededBackup()

            val profiles = ProfileStore(context)
            profiles.knownCameraCount = newCount

            ServiceController.reloadDatabase(context)

            SyncResult(
                outcome = SyncOutcome.UPDATED,
                cameraCount = newCount,
                message = "Successfully updated to $newCount cameras",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Validation of new database failed; initiating rollback", e)
            installer.rollback()
            SpeedCamApp.reloadCameraStore(context)
            ServiceController.reloadDatabase(context)
            SyncResult(
                outcome = SyncOutcome.FAILED,
                message = "Validation failed: ${e.message}; rolled back to previous database",
            )
        }
    }

    /** Fetches and parses a remote JSON manifest. */
    fun fetchManifest(url: String): DatabaseManifest {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("HTTP $responseCode from $url")
        }

        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return parseManifestJson(text)
    }

    /** Downloads a file from [url] into [dest] and verifies optional SHA-256. */
    fun downloadFile(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<File> =
        runCatching {
            val connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode from $url")
            }

            val totalLength = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L

            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesRead += read
                        if (totalLength > 0L) {
                            onProgress((bytesRead.toFloat() / totalLength).coerceIn(0f, 1f))
                        }
                    }
                    output.fd.sync()
                }
            }

            if (expectedSha256 != null) {
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(expectedSha256.trim(), ignoreCase = true)) {
                    dest.delete()
                    throw SecurityException("SHA-256 mismatch (expected $expectedSha256, got $hex)")
                }
            }

            dest
        }

    companion object {
        private const val TAG = "DatabaseSyncEngine"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val USER_AGENT = "SpeedCam-Android/1.0"

        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/mishakorobov1950/speedcam/main/data/manifest.json"

        /** Parses a manifest JSON string into [DatabaseManifest]. */
        fun parseManifestJson(jsonString: String): DatabaseManifest {
            val obj = JsonParser.parseString(jsonString).asJsonObject
            return DatabaseManifest(
                version = obj.get("version")?.asString ?: "",
                builtAt = obj.get("built_at")?.asString ?: obj.get("builtAt")?.asString ?: "",
                cameraCount = obj.get("camera_count")?.asInt ?: obj.get("cameraCount")?.asInt ?: 0,
                sectionCount = obj.get("section_count")?.asInt ?: obj.get("sectionCount")?.asInt ?: 0,
                downloadUrl = obj.get("download_url")?.asString ?: obj.get("downloadUrl")?.asString ?: "",
                sha256 = obj.get("sha256")?.asString?.takeIf { it.isNotBlank() },
            )
        }
    }
}
