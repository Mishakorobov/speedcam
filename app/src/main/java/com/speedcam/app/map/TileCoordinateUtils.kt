package com.speedcam.app.map

import com.speedcam.core.geo.MapViewport
import com.speedcam.core.geo.MercatorProjection

/** Coordinate identifier for an individual map tile in Web Mercator (EPSG:3857). */
data class TileCoord(
    val zoom: Int,
    val x: Int,
    val y: Int,
) {
    override fun toString(): String = "$zoom/$x/$y"
}

/** Bounding range of visible tiles at a specific zoom level. */
data class TileRange(
    val zoom: Int,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
) {
    val count: Int
        get() = (maxX - minX + 1).coerceAtLeast(0) * (maxY - minY + 1).coerceAtLeast(0)
}

/** Quadrant within a parent tile: (0,0) top-left, (1,0) top-right, (0,1) bottom-left, (1,1) bottom-right. */
data class TileQuadrant(
    val x: Int,
    val y: Int,
)

/**
 * High-performance mathematics for Web Mercator tile coordinates and MBTiles TMS translation.
 */
object TileCoordinateUtils {
    /**
     * Converts longitude in degrees to tile column X coordinate in [0, 2^zoom - 1].
     */
    fun lonToTileX(
        lon: Double,
        zoom: Int,
    ): Int {
        if (zoom <= 0) return 0
        val maxTile = (1 shl zoom) - 1
        val normX = MercatorProjection.longitudeToX(lon)
        return (normX * (1 shl zoom)).toInt().coerceIn(0, maxTile)
    }

    /**
     * Converts latitude in degrees to tile row Y coordinate in [0, 2^zoom - 1] (OSM Slippy XYZ convention).
     */
    fun latToTileY(
        lat: Double,
        zoom: Int,
    ): Int {
        if (zoom <= 0) return 0
        val maxTile = (1 shl zoom) - 1
        val normY = MercatorProjection.latitudeToY(lat)
        return (normY * (1 shl zoom)).toInt().coerceIn(0, maxTile)
    }

    /**
     * Converts tile column X back to western longitude in degrees.
     */
    fun tileXToLongitude(
        x: Int,
        zoom: Int,
    ): Double {
        if (zoom <= 0) return -180.0
        val normX = x.toDouble() / (1 shl zoom)
        return MercatorProjection.xToLongitude(normX)
    }

    /**
     * Converts tile row Y back to northern latitude in degrees.
     */
    fun tileYToLatitude(
        y: Int,
        zoom: Int,
    ): Double {
        if (zoom <= 0) return MercatorProjection.MAX_LATITUDE
        val normY = y.toDouble() / (1 shl zoom)
        return MercatorProjection.yToLatitude(normY)
    }

    /**
     * Translates standard OSM Slippy Y coordinate to OSGeo TMS (Tile Map Service) row coordinate
     * used by standard MBTiles specifications.
     */
    fun toTmsRow(
        y: Int,
        zoom: Int,
    ): Int {
        if (zoom <= 0) return 0
        return ((1 shl zoom) - 1) - y
    }

    /**
     * Translates OSGeo TMS row coordinate back to standard OSM Slippy Y coordinate.
     */
    fun fromTmsRow(
        tmsRow: Int,
        zoom: Int,
    ): Int {
        if (zoom <= 0) return 0
        return ((1 shl zoom) - 1) - tmsRow
    }

    /**
     * Computes the parent tile coordinate (at zoom - 1) and the quadrant position within it.
     * Returns null if [coord] is already at zoom 0.
     */
    fun getParentTile(coord: TileCoord): Pair<TileCoord, TileQuadrant>? {
        if (coord.zoom <= 0) return null
        val parentZoom = coord.zoom - 1
        val parentX = coord.x shr 1
        val parentY = coord.y shr 1
        val quadrantX = coord.x and 1
        val quadrantY = coord.y and 1
        return Pair(
            TileCoord(parentZoom, parentX, parentY),
            TileQuadrant(quadrantX, quadrantY),
        )
    }

    /**
     * Determines all visible tiles for the current [viewport] considering rotation, center, and zoom.
     */
    fun getVisibleTileRange(
        viewport: MapViewport,
        marginFactor: Double = 1.35,
    ): TileRange {
        val intZoom = viewport.zoom.toInt().coerceIn(0, 18)
        if (intZoom == 0) {
            return TileRange(zoom = 0, minX = 0, maxX = 0, minY = 0, maxY = 0)
        }

        val box = MercatorProjection.viewportBoundingBox(viewport, marginFactor)
        val maxCoord = (1 shl intZoom) - 1

        val rawMinX = lonToTileX(box.minLon, intZoom)
        val rawMaxX = lonToTileX(box.maxLon, intZoom)
        // Higher latitude is smaller Y in Mercator
        val rawMinY = latToTileY(box.maxLat, intZoom)
        val rawMaxY = latToTileY(box.minLat, intZoom)

        val minX = minOf(rawMinX, rawMaxX).coerceIn(0, maxCoord)
        val maxX = maxOf(rawMinX, rawMaxX).coerceIn(0, maxCoord)
        val minY = minOf(rawMinY, rawMaxY).coerceIn(0, maxCoord)
        val maxY = maxOf(rawMinY, rawMaxY).coerceIn(0, maxCoord)

        // Safety clamp: never allow a tile range to exceed 8x8 (64 tiles) in one viewport
        val maxSpan = 7
        val clampedMaxX = if (maxX - minX > maxSpan) minX + maxSpan else maxX
        val clampedMaxY = if (maxY - minY > maxSpan) minY + maxSpan else maxY

        return TileRange(
            zoom = intZoom,
            minX = minX,
            maxX = clampedMaxX,
            minY = minY,
            maxY = clampedMaxY,
        )
    }
}
