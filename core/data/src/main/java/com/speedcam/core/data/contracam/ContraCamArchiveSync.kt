package com.speedcam.core.data.contracam

import android.util.Log
import com.speedcam.core.data.CameraDao
import com.speedcam.core.detect.CameraSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Package record parsed from ContraCam `maplst.txt`. */
data class ContraCamPackage(
    val sizeBytes: Long,
    val country: String,
    val region: String,
    val version: String,
    val format: String,
    val relativePath: String,
)

/**
 * Downloads and synchronizes proprietary binary speed camera archives from ContraCam HTTP mirrors.
 */
class ContraCamArchiveSync(
    private val cameraDao: CameraDao,
    private val cacheDir: File,
) {
    /**
     * Fetches the ContraCam asset catalog (`maplst.txt`) from [mirror].
     */
    suspend fun fetchCatalog(mirror: String = DEFAULT_MIRRORS[0]): Result<List<ContraCamPackage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val catalogUrl = "$mirror/v$API_VERSION/prod/maplst.txt"
                val text = downloadText(catalogUrl)
                parseCatalog(text)
            }
        }

    /**
     * Downloads and imports the national or regional speedcam archive [pkg] into Room database.
     *
     * @param mirror active HTTP mirror host
     * @param pkg target package (defaults to national `Russia/RussiaSpeedcams`)
     * @param onProgress progress callback in [0.0, 1.0]
     * @return number of imported cameras
     */
    suspend fun downloadAndImport(
        mirror: String = DEFAULT_MIRRORS[0],
        pkg: ContraCamPackage? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            val targetPkg =
                pkg ?: ContraCamPackage(
                    sizeBytes = 10_000_000L,
                    country = "Russia",
                    region = "RussiaSpeedcams",
                    version = "current",
                    format = "0600",
                    relativePath = "Russia/RussiaSpeedcams/map.dat",
                )

            val mirrorsToTry = listOf(mirror) + (DEFAULT_MIRRORS - mirror)
            var lastError: Throwable? = null

            for (currMirror in mirrorsToTry) {
                val downloadUrl = "$currMirror/v$API_VERSION/prod/${targetPkg.relativePath}"
                val tempFile = File(cacheDir, "contracam-${System.currentTimeMillis()}.dat")

                try {
                    downloadFileWithRedirects(downloadUrl, tempFile, onProgress)
                    val checkDate = LocalDate.now().toString()
                    val cameras = ContraCamBinaryParser.parse(tempFile, checkDate)

                    if (cameras.isEmpty()) {
                        throw IOException("No valid speed cameras found in ContraCam archive")
                    }

                    // Chunker for Room SQLite bind variable limit
                    cameraDao.replaceSource(CameraSource.CONTRACAM.ordinal, cameras)
                    Log.i(TAG, "Successfully imported ${cameras.size} ContraCam cameras from $currMirror")
                    return@withContext Result.success(cameras.size)
                } catch (e: Throwable) {
                    Log.w(TAG, "Mirror $currMirror failed: ${e.message}")
                    lastError = e
                } finally {
                    tempFile.delete()
                }
            }
            Result.failure(lastError ?: IOException("All ContraCam mirrors failed"))
        }

    private fun parseCatalog(text: String): List<ContraCamPackage> =
        text.lines().mapNotNull { line ->
            val tokens = line.trim().split('/')
            if (tokens.size >= 8) {
                val size = tokens[0].toLongOrNull() ?: 0L
                val version = tokens[4]
                val format = tokens[5]
                val country = tokens[6]
                val region = tokens[7]
                ContraCamPackage(
                    sizeBytes = size,
                    country = country,
                    region = region,
                    version = version,
                    format = format,
                    relativePath = "$country/$region/map.dat",
                )
            } else {
                null
            }
        }

    private fun downloadText(url: String): String {
        val conn = openFollowRedirects(url)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFileWithRedirects(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val conn = openFollowRedirects(url)
        try {
            val totalBytes = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { output ->
                conn.inputStream.use { input ->
                    copyStream(input, output, totalBytes, onProgress)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun copyStream(
        input: InputStream,
        output: FileOutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            bytesRead += read
            if (totalBytes > 0) {
                onProgress((bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f))
            }
        }
        output.fd.sync()
    }

    private fun openFollowRedirects(
        initialUrl: String,
        maxHops: Int = MAX_REDIRECTS,
    ): HttpURLConnection {
        var curUrl = initialUrl
        repeat(maxHops) {
            val conn =
                (URL(curUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            val code = conn.responseCode
            if (code in 200..299) {
                return conn
            }
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in REDIRECT_CODES && !loc.isNullOrBlank()) {
                curUrl =
                    if (loc.startsWith("http://") || loc.startsWith("https://")) {
                        loc
                    } else {
                        URL(URL(curUrl), loc).toString()
                    }
            } else {
                throw IOException("HTTP $code from $curUrl")
            }
        }
        throw IOException("Too many redirects (max $maxHops) for $initialUrl")
    }

    companion object {
        private const val TAG = "ContraCamArchiveSync"
        private const val API_VERSION = "6.0"
        private const val TIMEOUT_MS = 25_000
        private const val BUFFER_SIZE = 16_384
        private const val MAX_REDIRECTS = 6
        private val REDIRECT_CODES = 300..308
        private const val USER_AGENT = "ContraCam/4.0.135 (SpeedCam-Bridge)"

        val DEFAULT_MIRRORS =
            listOf(
                "http://contracam.ru",
                "http://contracam.org",
                "http://mybedy.website",
                "http://contracam.website",
                "http://contracam.online",
            )
    }
}
