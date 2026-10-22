package com.speedcam.app.map

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * High-performance SQLite reader for OpenStreetMap MBTiles archive files.
 *
 * Implements the standard Mapbox MBTiles specification:
 * - Reads metadata (name, format, zoom bounds, bounding box).
 * - Queries tile blobs by (zoom, x, y) with automatic TMS-to-XYZ translation
 *   and XYZ fallback for non-standard MBTiles exports.
 * - Thread-safe read-only SQLite operations.
 */
class MbTilesDatabase(
    val file: File,
) : AutoCloseable {
    private var db: SQLiteDatabase? = null

    var name: String = file.nameWithoutExtension
        private set

    var format: String = "png"
        private set

    var minZoom: Int = 0
        private set

    var maxZoom: Int = 18
        private set

    var bounds: String? = null
        private set

    val isOpen: Boolean
        get() = db?.isOpen == true

    /**
     * Opens the MBTiles SQLite database in read-only mode and reads archive metadata.
     */
    fun open(): Boolean {
        if (!file.exists() || file.length() < 1024L) return false

        return try {
            db =
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            readMetadata()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open MBTiles database at ${file.absolutePath}", e)
            close()
            false
        }
    }

    private fun readMetadata() {
        val database = db ?: return
        runCatching {
            database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val valueIdx = cursor.getColumnIndex("value")
                if (nameIdx >= 0 && valueIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(nameIdx)
                        val value = cursor.getString(valueIdx)
                        when (key.lowercase()) {
                            "name" -> name = value
                            "format" -> format = value.lowercase()
                            "minzoom" -> minZoom = value.toIntOrNull() ?: 0
                            "maxzoom" -> maxZoom = value.toIntOrNull() ?: 18
                            "bounds" -> bounds = value
                        }
                    }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Could not load complete MBTiles metadata for ${file.name}", e)
        }
    }

    /**
     * Retrieves the binary image blob for tile at [zoom], [x], [y] (OSM Slippy coordinates).
     */
    fun getTile(
        zoom: Int,
        x: Int,
        y: Int,
    ): ByteArray? {
        val database = db ?: return null
        if (!database.isOpen) return null

        val tmsY = TileCoordinateUtils.toTmsRow(y, zoom)

        // 1. Primary standard query with TMS row
        try {
            database
                .rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                    arrayOf(zoom.toString(), x.toString(), tmsY.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getBlob(0)
                    }
                }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying TMS tile ($zoom, $x, $tmsY)", e)
        }

        // 2. Fallback query with raw XYZ row for non-standard MBTiles exports
        try {
            database
                .rawQuery(
                    "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                    arrayOf(zoom.toString(), x.toString(), y.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getBlob(0)
                    }
                }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying fallback XYZ tile ($zoom, $x, $y)", e)
        }

        return null
    }

    /**
     * Total number of tiles stored in this archive.
     */
    fun getTileCount(): Long {
        val database = db ?: return 0L
        if (!database.isOpen) return 0L
        return runCatching {
            database.rawQuery("SELECT COUNT(*) FROM tiles", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrDefault(0L)
    }

    override fun close() {
        runCatching {
            db?.close()
        }
        db = null
    }

    companion object {
        private const val TAG = "MbTilesDatabase"

        /**
         * Validates whether [file] is a valid SQLite MBTiles database.
         */
        fun isValidMbTiles(file: File): Boolean {
            if (!file.exists() || !file.isFile || file.length() < 1024L) return false

            return try {
                SQLiteDatabase
                    .openDatabase(
                        file.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    ).use { db ->
                        // Verify presence of tiles view or table
                        db
                            .rawQuery(
                                "SELECT name FROM sqlite_master WHERE (type='table' OR type='view') AND name='tiles'",
                                null,
                            ).use { it.moveToFirst() }
                    }
            } catch (_: Throwable) {
                false
            }
        }
    }
}
