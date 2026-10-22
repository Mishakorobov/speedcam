package com.speedcam.app.map

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * High-speed local SQLite cache for OpenStreetMap / Carto map tiles.
 *
 * Persists tiles permanently on disk, enabling instantaneous 60/120 FPS map
 * rendering with zero network access in tunnels, country roads, and offline environments.
 */
class OsmTileCache(
    context: Context,
) : AutoCloseable {
    private val dbFile =
        File(context.filesDir, "map_cache/osm_tiles.db").apply {
            parentFile?.mkdirs()
        }
    private var db: SQLiteDatabase? = null

    init {
        open()
    }

    private fun open() {
        try {
            db =
                SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
                    execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS osm_tiles (
                            zoom INTEGER,
                            x INTEGER,
                            y INTEGER,
                            data BLOB,
                            updated_at INTEGER,
                            PRIMARY KEY (zoom, x, y)
                        )
                        """.trimIndent(),
                    )
                    execSQL("CREATE INDEX IF NOT EXISTS idx_osm_tiles_coords ON osm_tiles(zoom, x, y)")
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize OsmTileCache SQLite database", e)
        }
    }

    /**
     * Reads tile blob from SQLite cache.
     */
    fun getTile(
        zoom: Int,
        x: Int,
        y: Int,
    ): ByteArray? {
        val database = db ?: return null
        if (!database.isOpen) return null

        return try {
            database
                .rawQuery(
                    "SELECT data FROM osm_tiles WHERE zoom = ? AND x = ? AND y = ? LIMIT 1",
                    arrayOf(zoom.toString(), x.toString(), y.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getBlob(0) else null
                }
        } catch (e: Throwable) {
            Log.w(TAG, "Error reading cached tile ($zoom, $x, $y)", e)
            null
        }
    }

    /**
     * Inserts or updates tile in SQLite cache.
     */
    fun putTile(
        zoom: Int,
        x: Int,
        y: Int,
        data: ByteArray,
    ) {
        val database = db ?: return
        if (!database.isOpen) return

        try {
            val cv =
                ContentValues().apply {
                    put("zoom", zoom)
                    put("x", x)
                    put("y", y)
                    put("data", data)
                    put("updated_at", System.currentTimeMillis())
                }
            database.insertWithOnConflict("osm_tiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Throwable) {
            Log.w(TAG, "Error writing cached tile ($zoom, $x, $y)", e)
        }
    }

    /**
     * Returns true if tile exists in local SQLite cache.
     */
    fun hasTile(
        zoom: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val database = db ?: return false
        if (!database.isOpen) return false

        return try {
            database
                .rawQuery(
                    "SELECT 1 FROM osm_tiles WHERE zoom = ? AND x = ? AND y = ? LIMIT 1",
                    arrayOf(zoom.toString(), x.toString(), y.toString()),
                ).use { cursor ->
                    cursor.moveToFirst()
                }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns total number of cached tiles.
     */
    fun getTileCount(): Long {
        val database = db ?: return 0L
        if (!database.isOpen) return 0L

        return try {
            database.rawQuery("SELECT COUNT(*) FROM osm_tiles", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Returns the disk size of the cache database file in bytes.
     */
    fun getCacheSizeBytes(): Long = if (dbFile.exists()) dbFile.length() else 0L

    /**
     * Clears all cached tiles from SQLite database and vacuums.
     */
    fun clearCache(): Boolean {
        val database = db ?: return false
        if (!database.isOpen) return false

        return try {
            database.execSQL("DELETE FROM osm_tiles")
            database.execSQL("VACUUM")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear OsmTileCache", e)
            false
        }
    }

    override fun close() {
        runCatching {
            db?.close()
        }
        db = null
    }

    companion object {
        private const val TAG = "OsmTileCache"
    }
}
