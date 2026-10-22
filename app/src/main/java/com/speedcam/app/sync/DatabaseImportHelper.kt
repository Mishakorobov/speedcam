package com.speedcam.app.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.JsonParser
import com.speedcam.app.SpeedCamApp
import com.speedcam.core.data.CameraDatabaseInstaller
import com.speedcam.core.data.CameraEntity
import com.speedcam.core.data.contracam.ContraCamBinaryParser
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * Handles importing camera databases from local files picked by the user via
 * Storage Access Framework (SAF) FilePicker.
 *
 * Supported formats:
 * 1. SQLite database (.db) -> replaces active database via [DatabaseSyncEngine.applyCandidateDatabase].
 * 2. ContraCam binary archives (.dat / .bin) -> parsed via [ContraCamBinaryParser] and upserted into Room.
 * 3. JSON files (.json) -> manifest (syncs download_url) or camera list (upserted directly).
 */
class DatabaseImportHelper(
    private val context: Context,
    private val syncEngine: DatabaseSyncEngine = DatabaseSyncEngine(context),
) {
    /**
     * Imports database or cameras from the provided SAF content [uri].
     */
    suspend fun importFromUri(uri: Uri): SyncResult =
        withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val tempFile = File(cacheDir, "import-${System.currentTimeMillis()}.tmp")

            try {
                val copied = copyUriToFile(uri, tempFile)
                if (!copied || tempFile.length() == 0L) {
                    return@withContext SyncResult(
                        outcome = SyncOutcome.FAILED,
                        message = "Не удалось прочитать выбранный файл или он пуст.",
                    )
                }

                importFromFile(tempFile)
            } catch (e: Throwable) {
                Log.e(TAG, "Import error", e)
                SyncResult(
                    outcome = SyncOutcome.FAILED,
                    message = "Ошибка при импорте файла: ${e.localizedMessage ?: e.message}",
                )
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Imports from an existing local [file].
     */
    suspend fun importFromFile(file: File): SyncResult =
        withContext(Dispatchers.IO) {
            // 1. Try SQLite database (.db)
            if (CameraDatabaseInstaller.looksLikeSqliteDatabase(file)) {
                Log.i(TAG, "Importing as SQLite database")
                return@withContext syncEngine.applyCandidateDatabase(file)
            }

            // 2. Try ContraCam binary archive (.dat / .bin)
            val contraCamParsed =
                runCatching {
                    ContraCamBinaryParser.parse(file, checkDate = LocalDate.now().toString())
                }.getOrNull()

            if (!contraCamParsed.isNullOrEmpty()) {
                Log.i(TAG, "Importing ${contraCamParsed.size} cameras from ContraCam binary archive")
                val store = SpeedCamApp.cameraStore(context)
                store.cameraDao.replaceSource(CameraSource.CONTRACAM.ordinal, contraCamParsed)
                val profiles = ProfileStore(context)
                profiles.contraCamCameraCount = contraCamParsed.size
                profiles.lastContraCamStatus = "Успешно импортировано (${contraCamParsed.size} камер)"
                profiles.lastContraCamSyncAt = LocalDate.now().toString()
                profiles.useContraCamDb = true
                store.useContraCamDb = true
                ServiceController.reloadDatabase(context)
                return@withContext SyncResult(
                    outcome = SyncOutcome.UPDATED,
                    cameraCount = contraCamParsed.size,
                    message = "Успешно импортирован архив ContraCam: ${contraCamParsed.size} камер",
                )
            }

            // 3. Try JSON file (.json)
            val jsonText =
                runCatching {
                    file.readText(Charsets.UTF_8).trim()
                }.getOrNull()

            if (jsonText != null && (jsonText.startsWith("{") || jsonText.startsWith("["))) {
                val parsedJson = parseAndImportJson(jsonText)
                if (parsedJson != null) {
                    return@withContext parsedJson
                }
            }

            SyncResult(
                outcome = SyncOutcome.FAILED,
                message =
                    "Не удалось распознать формат файла. Поддерживаются базы SQLite (.db), " +
                        "архивы ContraCam (.dat) и JSON (.json).",
            )
        }

    private suspend fun parseAndImportJson(jsonText: String): SyncResult? {
        val downloadUrl = extractManifestDownloadUrl(jsonText)
        if (downloadUrl != null) {
            Log.i(TAG, "JSON is a manifest pointing to $downloadUrl")
            return syncEngine.sync(downloadUrl)
        }

        val entities = parseCamerasJson(jsonText)
        if (entities.isEmpty()) return null

        val store = SpeedCamApp.cameraStore(context)
        store.cameraDao.upsertAll(entities)
        val newCount = store.cameraCount()
        val profiles = ProfileStore(context)
        profiles.knownCameraCount = newCount
        ServiceController.reloadDatabase(context)

        return SyncResult(
            outcome = SyncOutcome.UPDATED,
            cameraCount = entities.size,
            message = "Импортировано ${entities.size} камер из файла JSON",
        )
    }

    private fun copyUriToFile(
        uri: Uri,
        dest: File,
    ): Boolean =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            true
        }.getOrDefault(false)

    companion object {
        private const val TAG = "DatabaseImportHelper"

        /** Extracts download_url from a JSON manifest string, or null if not a manifest. */
        fun extractManifestDownloadUrl(jsonText: String): String? {
            val root = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return null
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            val url = obj.get("download_url")?.asString ?: obj.get("downloadUrl")?.asString
            return url?.takeIf { it.isNotBlank() }
        }

        /** Parses a JSON string containing an array of cameras or `{"cameras": [...]}`. */
        fun parseCamerasJson(jsonText: String): List<CameraEntity> {
            val root = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return emptyList()
            val cameraArray =
                when {
                    root.isJsonArray -> root.asJsonArray
                    root.isJsonObject && root.asJsonObject.has("cameras") ->
                        root.asJsonObject.getAsJsonArray("cameras")
                    else -> null
                } ?: return emptyList()

            val entities = mutableListOf<CameraEntity>()
            for (elem in cameraArray) {
                if (!elem.isJsonObject) continue
                val c = elem.asJsonObject
                val lat = c.get("lat")?.asDouble ?: continue
                val lon = c.get("lon")?.asDouble ?: continue
                val limitKmh = c.get("limit_kmh")?.asInt ?: c.get("limitKmh")?.asInt ?: 0
                val kindOrdinal = c.get("kind")?.asInt ?: CameraKind.FIXED.ordinal
                val facingDeg = c.get("facing_deg")?.asDouble ?: c.get("facingDeg")?.asDouble
                val externalId =
                    c.get("external_id")?.asString ?: c.get("id")?.asString ?: "imported_${entities.size + 1}"

                entities.add(
                    CameraEntity(
                        source = CameraSource.OSM.ordinal,
                        externalId = externalId,
                        lat = lat,
                        lon = lon,
                        limitKmh = limitKmh,
                        kind = kindOrdinal,
                        facingDeg = facingDeg,
                        dirCount = 1,
                        operator = c.get("operator")?.asString,
                        sourceDetail = "Local JSON import",
                        checkDate = LocalDate.now().toString(),
                    ),
                )
            }
            return entities
        }
    }
}
