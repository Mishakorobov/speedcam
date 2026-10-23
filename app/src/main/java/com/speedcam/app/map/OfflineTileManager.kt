package com.speedcam.app.map

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.speedcam.service.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified offline cartography engine coordinating MBTiles archives,
 * high-speed local SQLite tile cache, and online tile streaming.
 *
 * Ensures 100% offline map functionality in tunnels and remote roads,
 * bypassing Overpass API network dependency.
 */
class OfflineTileManager(
    private val context: Context,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profiles = ProfileStore(context)

    val mapsDir = File(context.filesDir, "maps").apply { mkdirs() }
    val osmTileCache = OsmTileCache(context)

    var activeMbTiles: MbTilesDatabase? = null
        private set

    // In-memory cache holding up to 128 decoded tile bitmaps (~8-16 MB)
    private val memoryCache = LruCache<String, ImageBitmap>(128)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Observable state revision incremented whenever background tiles finish loading. */
    val revision = mutableLongStateOf(0L)

    init {
        migrateTileCache()
        restoreActiveMbTiles()
    }

    private fun migrateTileCache() {
        if (profiles.tileCacheVersion < TILE_CACHE_SCHEMA_VERSION) {
            Log.i(
                TAG,
                "Migrating tile cache from v${profiles.tileCacheVersion} " +
                    "to v$TILE_CACHE_SCHEMA_VERSION (clearing legacy Carto tiles)",
            )
            osmTileCache.clearCache()
            memoryCache.evictAll()
            profiles.tileCacheVersion = TILE_CACHE_SCHEMA_VERSION
        }
    }

    private fun restoreActiveMbTiles() {
        val savedPath = profiles.activeMbTilesPath
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists() && MbTilesDatabase.isValidMbTiles(file)) {
                val db = MbTilesDatabase(file)
                if (db.open()) {
                    activeMbTiles = db
                    Log.i(TAG, "Restored active MBTiles: ${file.name} (${db.getTileCount()} tiles)")
                    return
                }
            }
        }

        // Auto-discover any .mbtiles file placed in files/maps
        val discovered = mapsDir.listFiles { f -> f.extension.equals("mbtiles", ignoreCase = true) }
        discovered?.firstOrNull()?.let { file ->
            if (MbTilesDatabase.isValidMbTiles(file)) {
                val db = MbTilesDatabase(file)
                if (db.open()) {
                    activeMbTiles = db
                    profiles.activeMbTilesPath = file.absolutePath
                    Log.i(TAG, "Auto-loaded MBTiles: ${file.name}")
                }
            }
        }
    }

    fun isNetworkAvailable(): Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)

    /**
     * Retrieves the decoded [ImageBitmap] for tile at [zoom], [x], [y].
     * Returns cached bitmap immediately if present. If missing, initiates
     * asynchronous load from MBTiles, SQLite cache, or network.
     */
    fun getTileBitmap(
        zoom: Int,
        x: Int,
        y: Int,
    ): ImageBitmap? {
        val key = "$zoom/$x/$y"
        val cached = memoryCache.get(key)
        if (cached != null) return cached

        if (!inFlight.contains(key)) {
            inFlight.add(key)
            scope.launch {
                try {
                    loadTileInternal(zoom, x, y, key)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    private suspend fun loadTileInternal(
        zoom: Int,
        x: Int,
        y: Int,
        key: String,
    ) = withContext(Dispatchers.IO) {
        // 1. Try active MBTiles archive
        var bytes = activeMbTiles?.getTile(zoom, x, y)

        // 2. Try local SQLite tile cache
        if (bytes == null) {
            bytes = osmTileCache.getTile(zoom, x, y)
        }

        // 3. Fallback: Download from high-contrast tile server if network is available
        if (bytes == null && isNetworkAvailable()) {
            bytes = downloadTile(zoom, x, y)
            if (bytes != null && bytes.isNotEmpty()) {
                osmTileCache.putTile(zoom, x, y, bytes)
            }
        }

        // 4. Decode image and cache in memory
        if (bytes != null && bytes.isNotEmpty()) {
            val bitmap =
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()

            if (bitmap != null) {
                val img = bitmap.asImageBitmap()
                memoryCache.put(key, img)
                revision.longValue++
            }
        }
    }

    /**
     * Attempts to find a parent tile in memory cache at [zoom] - 1 or [zoom] - 2.
     * Returns Triple of (ImageBitmap, quadrantX, quadrantY) for seamless visual scaling.
     */
    fun getParentTileFallback(
        zoom: Int,
        x: Int,
        y: Int,
    ): Triple<ImageBitmap, Int, Int>? {
        if (zoom <= 0) return null

        // Parent at zoom - 1
        val pZoom = zoom - 1
        val pX = x shr 1
        val pY = y shr 1
        val parentKey = "$pZoom/$pX/$pY"
        val parentImg = memoryCache.get(parentKey)
        if (parentImg != null) {
            return Triple(parentImg, x and 1, y and 1)
        }

        // Grandparent at zoom - 2
        if (zoom > 1) {
            val gpZoom = zoom - 2
            val gpX = x shr 2
            val gpY = y shr 2
            val gpKey = "$gpZoom/$gpX/$gpY"
            val gpImg = memoryCache.get(gpKey)
            if (gpImg != null) {
                return Triple(gpImg, (x shr 1) and 1, (y shr 1) and 1)
            }
        }

        return null
    }

    private fun downloadTile(
        zoom: Int,
        x: Int,
        y: Int,
    ): ByteArray? {
        val customTemplate = profiles.customTileServerUrl.trim()
        val apiKey = profiles.tileApiKey.trim()

        val urls = mutableListOf<String>()

        // 1. User-defined custom tile server template (with optional API key placeholder)
        if (customTemplate.isNotEmpty()) {
            val formatted =
                customTemplate
                    .replace("{z}", zoom.toString(), ignoreCase = true)
                    .replace("{x}", x.toString(), ignoreCase = true)
                    .replace("{y}", y.toString(), ignoreCase = true)
                    .replace("{apiKey}", apiKey, ignoreCase = true)
                    .replace("{key}", apiKey, ignoreCase = true)
            urls.add(formatted)
        }

        // 2. High-speed, 100% free, public OSM servers (Zero API key required)
        urls.add("https://tile.openstreetmap.org/$zoom/$x/$y.png")
        urls.add("https://a.tile.openstreetmap.fr/osmfr/$zoom/$x/$y.png")
        urls.add("https://tile.openstreetmap.de/$zoom/$x/$y.png")

        // 3. Fallback: Key-authenticated third-party tile providers (ONLY if user supplied an API key)
        if (apiKey.isNotEmpty()) {
            urls.add("https://a.basemaps.cartocdn.com/dark_all/$zoom/$x/$y.png?api_key=$apiKey")
        }

        for (endpoint in urls) {
            val result =
                runCatching {
                    val conn =
                        (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3000
                            readTimeout = 3500
                            setRequestProperty(
                                "User-Agent",
                                "SpeedCamApp/1.0 (Android; +https://github.com/Mishakorobov/speedcam)",
                            )
                            setRequestProperty("Accept", "image/png,image/webp,image/*")
                        }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        conn.inputStream.use { it.readBytes() }
                    } else {
                        null
                    }
                }.getOrNull()

            if (result != null && result.isNotEmpty()) {
                return result
            }
        }
        return null
    }

    /**
     * Imports an MBTiles file selected by the user via Storage Access Framework (SAF).
     */
    suspend fun importMbTilesFromUri(uri: Uri): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "import_map_${System.currentTimeMillis()}.tmp")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!tempFile.exists() || tempFile.length() < 1024L) {
                    return@withContext Pair(false, "Выбранный файл пуст или поврежден.")
                }

                if (!MbTilesDatabase.isValidMbTiles(tempFile)) {
                    return@withContext Pair(false, "Файл не является валидным архивом MBTiles (SQLite).")
                }

                val targetFile = File(mapsDir, "offline_map.mbtiles")
                activeMbTiles?.close()
                activeMbTiles = null

                if (targetFile.exists()) targetFile.delete()
                tempFile.copyTo(targetFile, overwrite = true)

                val newDb = MbTilesDatabase(targetFile)
                if (newDb.open()) {
                    activeMbTiles = newDb
                    profiles.activeMbTilesPath = targetFile.absolutePath
                    memoryCache.evictAll()
                    revision.longValue++
                    Pair(true, "Карта MBTiles '${newDb.name}' успешно подключена (${newDb.getTileCount()} тайлов)")
                } else {
                    Pair(false, "Не удалось открыть архив MBTiles.")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error importing MBTiles from uri", e)
                Pair(false, "Ошибка импорта: ${e.localizedMessage ?: e.message}")
            } finally {
                tempFile.delete()
            }
        }

    /**
     * Disconnects active MBTiles archive.
     */
    fun clearActiveMbTiles() {
        activeMbTiles?.close()
        activeMbTiles = null
        profiles.activeMbTilesPath = null
        memoryCache.evictAll()
        revision.longValue++
    }

    /**
     * Clears local SQLite OSM tile cache and in-memory cache.
     */
    fun clearCache(): Boolean {
        memoryCache.evictAll()
        val cleared = osmTileCache.clearCache()
        revision.longValue++
        return cleared
    }

    /**
     * Returns formatted cache statistics for display in settings.
     */
    fun getTileCacheStats(): String {
        val count = osmTileCache.getTileCount()
        val bytes = osmTileCache.getCacheSizeBytes()
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return "%.1f МБ (%d тайлов)".format(mb, count)
    }

    override fun close() {
        activeMbTiles?.close()
        activeMbTiles = null
        osmTileCache.close()
        memoryCache.evictAll()
    }

    companion object {
        private const val TAG = "OfflineTileManager"
        private const val TILE_CACHE_SCHEMA_VERSION = 2
    }
}
