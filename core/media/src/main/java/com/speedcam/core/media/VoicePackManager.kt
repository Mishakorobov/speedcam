package com.speedcam.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class VoicePackInfo(
    val id: String,
    val name: String,
    val dir: File,
    val clipCount: Int,
)

/**
 * Manages locally installed audio voice phrase packs.
 *
 * Checks for required audio clips for planned alert phrases and signals
 * fallback to TTS when specific clips (e.g. bus lane or road marking) are not present.
 */
class VoicePackManager(
    private val context: Context,
) {
    val packsDir = File(context.filesDir, "voice_packs").apply { mkdirs() }

    fun listInstalledPacks(): List<VoicePackInfo> {
        var dirs = packsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        if (dirs.none { countAudioFiles(it) > 0 }) {
            ensureBundledPacksInstalled()
            dirs = packsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        }
        return dirs.mapNotNull { dir ->
            val clips = countAudioFiles(dir)
            if (clips > 0) {
                VoicePackInfo(
                    id = dir.name,
                    name = dir.name.replace("_", " ").replaceFirstChar { it.uppercase() },
                    dir = dir,
                    clipCount = clips,
                )
            } else {
                null
            }
        }
    }

    fun ensureBundledPacksInstalled(forceRefresh: Boolean = false): List<VoicePackInfo> {
        val existing = packsDir.listFiles { f -> f.isDirectory && countAudioFiles(f) > 0 }
        if (forceRefresh || existing.isNullOrEmpty()) {
            runCatching {
                val assetVoiceFiles = context.assets.list("voice") ?: emptyArray()
                for (zipFile in assetVoiceFiles) {
                    if (zipFile.endsWith(".zip", ignoreCase = true)) {
                        val packId = zipFile.substringBeforeLast('.')
                        context.assets.open("voice/$zipFile").use { input ->
                            importPackFromZip(input, packId)
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed to extract bundled voice packs: ${it.message}")
            }
        }
        val dirs = packsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.mapNotNull { dir ->
            val clips = countAudioFiles(dir)
            if (clips > 0) {
                VoicePackInfo(
                    id = dir.name,
                    name = dir.name.replace("_", " ").replaceFirstChar { it.uppercase() },
                    dir = dir,
                    clipCount = clips,
                )
            } else {
                null
            }
        }
    }

    fun getPack(packId: String?): VoicePackInfo? {
        if (packId.isNullOrBlank()) return null
        var dir = File(packsDir, packId)
        if (!dir.exists() || !dir.isDirectory || countAudioFiles(dir) == 0) {
            runCatching {
                context.assets.open("voice/$packId.zip").use { input ->
                    importPackFromZip(input, packId)
                }
            }
            dir = File(packsDir, packId)
        }
        if (!dir.exists() || !dir.isDirectory) return null
        val clips = countAudioFiles(dir)
        return VoicePackInfo(
            id = dir.name,
            name = dir.name.replace("_", " ").replaceFirstChar { it.uppercase() },
            dir = dir,
            clipCount = clips,
        )
    }

    /**
     * Resolves matching audio files for all [keys] within [packDir].
     * Returns null if ANY of the required clips is missing, triggering automatic TTS fallback.
     */
    fun findClipsForKeys(
        packDir: File,
        keys: List<String>,
    ): List<File>? {
        val matchedFiles = ArrayList<File>(keys.size)
        val audioExtensions = listOf("mp3", "ogg", "wav", "m4a")

        for (key in keys) {
            var found: File? = null
            for (ext in audioExtensions) {
                val candidate = File(packDir, "$key.$ext")
                if (candidate.exists() && candidate.length() > 0L) {
                    found = candidate
                    break
                }
            }
            if (found != null) {
                matchedFiles.add(found)
            } else {
                // Missing specific clip -> signal TTS fallback
                return null
            }
        }
        return matchedFiles
    }

    /**
     * Resolves matching audio files for any of the [keys] within [packDir],
     * skipping missing clips instead of returning null.
     */
    fun findAvailableClips(
        packDir: File,
        keys: List<String>,
    ): List<File> {
        val matchedFiles = ArrayList<File>()
        val audioExtensions = listOf("mp3", "ogg", "wav", "m4a")

        for (key in keys) {
            for (ext in audioExtensions) {
                val candidate = File(packDir, "$key.$ext")
                if (candidate.exists() && candidate.length() > 0L) {
                    matchedFiles.add(candidate)
                    break
                }
            }
        }
        return matchedFiles
    }

    /**
     * Imports audio clips from a content URI (e.g. from SAF file picker) into a voice pack folder.
     */
    fun importPackFromUri(
        uri: Uri,
        defaultName: String = "voice_pack",
    ): Pair<Boolean, String> =
        try {
            var fileName = defaultName
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    val name = cursor.getString(nameIdx)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
            }
            val baseName = fileName.substringBeforeLast('.')
            val stream =
                context.contentResolver.openInputStream(uri)
                    ?: return Pair(false, "Не удалось открыть выбранный файл")
            stream.use {
                importPackFromZip(it, baseName)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import voice pack from Uri", e)
            Pair(false, "Ошибка импорта: ${e.localizedMessage ?: e.message}")
        }

    /**
     * Imports audio clips from a ZIP input stream into a voice pack folder.
     */
    fun importPackFromZip(
        inputStream: InputStream,
        packName: String,
    ): Pair<Boolean, String> =
        try {
            val cleanId =
                packName
                    .lowercase()
                    .replace(Regex("[^a-z0-9_]"), "_")
                    .trim('_')
                    .ifBlank { "custom_pack" }
            val targetDir = File(packsDir, cleanId).apply { mkdirs() }

            var extracted = 0
            val buffer = ByteArray(8192)
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = File(entry.name).name
                        val ext = fileName.substringAfterLast('.', "").lowercase()
                        if (ext in listOf("mp3", "ogg", "wav", "m4a", "json", "txt")) {
                            val outFile = File(targetDir, fileName)
                            FileOutputStream(outFile).use { fos ->
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                            if (ext in listOf("mp3", "ogg", "wav", "m4a")) {
                                extracted++
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extracted > 0) {
                Pair(true, "Голосовой пакет '$packName' успешно импортирован ($extracted аудио-клипов)")
            } else {
                Pair(false, "В архиве не найдено поддерживаемых аудиофайлов (.mp3, .ogg, .wav, .m4a)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import voice pack ZIP", e)
            Pair(false, "Ошибка импорта архива: ${e.localizedMessage ?: e.message}")
        }

    fun deletePack(packId: String): Boolean {
        val dir = File(packsDir, packId)
        return if (dir.exists() && dir.isDirectory) {
            dir.deleteRecursively()
        } else {
            false
        }
    }

    private fun countAudioFiles(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        val supported = setOf("mp3", "ogg", "wav", "m4a")
        return files.count { it.isFile && it.extension.lowercase() in supported }
    }

    companion object {
        const val VOICE_PACK_SCHEMA_VERSION = 2
        private const val TAG = "VoicePackManager"
    }
}
