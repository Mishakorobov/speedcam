package com.speedcam.app.map

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Road classification hierarchy for vector styling. */
enum class RoadClass(
    val strokeWidthDp: Float,
    val zIndex: Int,
) {
    MOTORWAY(5.5f, 5),
    TRUNK(4.5f, 4),
    PRIMARY(3.8f, 3),
    SECONDARY(2.8f, 2),
    RESIDENTIAL(1.8f, 1),
}

/** Vector representation of a road segment. */
data class VectorRoad(
    val id: Long,
    val name: String?,
    val kind: RoadClass,
    val points: List<LatLon>,
)

/**
 * Lightweight repository for vector road geometries.
 *
 * Caches roads in memory and on disk. When online, optionally retrieves road geometries
 * from OpenStreetMap Overpass API for the active bounding box.
 */
class VectorRoadRepository(
    private val context: Context,
) {
    private val cacheDir = File(context.cacheDir, "vector_roads").apply { mkdirs() }
    private val memoryCache = ConcurrentHashMap<String, List<VectorRoad>>()
    private val inFlightQueries = ConcurrentHashMap.newKeySet<String>()

    /**
     * Retrieves vector roads for [box]. Returns cached roads immediately,
     * and triggers a background query if network is available and zoom is high enough.
     */
    suspend fun getRoadsForBox(
        box: BoundingBox,
        zoom: Float,
    ): List<VectorRoad> =
        withContext(Dispatchers.IO) {
            if (zoom < MIN_ROAD_ZOOM) return@withContext emptyList()

            val tileKey = computeTileKey(box)
            val cached = memoryCache[tileKey]
            if (cached != null) return@withContext cached

            val diskCached = readFromDiskCache(tileKey)
            if (diskCached != null) {
                memoryCache[tileKey] = diskCached
                return@withContext diskCached
            }

            // Trigger non-blocking fetch
            if (!inFlightQueries.contains(tileKey)) {
                inFlightQueries.add(tileKey)
                fetchOverpassRoads(box, tileKey)
                inFlightQueries.remove(tileKey)
            }

            memoryCache[tileKey] ?: emptyList()
        }

    private fun computeTileKey(box: BoundingBox): String {
        // Discretize to ~0.02 degree grid cells (~2km)
        val latCell = (box.minLat * 50).toInt()
        val lonCell = (box.minLon * 50).toInt()
        return "roads_${latCell}_$lonCell"
    }

    private fun readFromDiskCache(tileKey: String): List<VectorRoad>? {
        val file = File(cacheDir, "$tileKey.json")
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val json = file.readText()
            parseOverpassJson(json)
        }.getOrNull()
    }

    private fun saveToDiskCache(
        tileKey: String,
        json: String,
    ) {
        runCatching {
            File(cacheDir, "$tileKey.json").writeText(json)
        }
    }

    private fun fetchOverpassRoads(
        box: BoundingBox,
        tileKey: String,
    ) {
        runCatching {
            val query =
                "[out:json][timeout:6];" +
                    "(way[\"highway\"~\"motorway|trunk|primary|secondary|tertiary\"]" +
                    "(${box.minLat},${box.minLon},${box.maxLat},${box.maxLon}););out geom qt 200;"
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
            val conn =
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "SpeedCam-VectorMap/1.0")
                }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val roads = parseOverpassJson(json)
                if (roads.isNotEmpty()) {
                    memoryCache[tileKey] = roads
                    saveToDiskCache(tileKey, json)
                }
            }
        }.onFailure {
            Log.d(TAG, "Overpass road query skipped or unavailable: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "VectorRoadRepository"
        const val MIN_ROAD_ZOOM = 13.0f

        fun parseOverpassJson(json: String): List<VectorRoad> {
            val root = JsonParser.parseString(json).asJsonObject
            val elements = root.getAsJsonArray("elements") ?: return emptyList()
            return elements.mapNotNull { parseRoadElement(it.asJsonObject) }
        }

        private fun parseRoadElement(obj: com.google.gson.JsonObject): VectorRoad? {
            if (obj.get("type")?.asString != "way") return null
            val id = obj.get("id")?.asLong
            val tags = obj.getAsJsonObject("tags")
            val highway = tags?.get("highway")?.asString
            val geometry = obj.getAsJsonArray("geometry")
            if (id == null || highway == null || geometry == null) return null

            val points = parsePoints(geometry)
            if (points.size < 2) return null

            return VectorRoad(
                id = id,
                name = tags.get("name")?.asString,
                kind = parseRoadClass(highway),
                points = points,
            )
        }

        private fun parseRoadClass(highway: String): RoadClass =
            when (highway) {
                "motorway", "motorway_link" -> RoadClass.MOTORWAY
                "trunk", "trunk_link" -> RoadClass.TRUNK
                "primary", "primary_link" -> RoadClass.PRIMARY
                "secondary", "secondary_link" -> RoadClass.SECONDARY
                else -> RoadClass.RESIDENTIAL
            }

        private fun parsePoints(geometry: com.google.gson.JsonArray): List<LatLon> {
            val points = mutableListOf<LatLon>()
            for (pt in geometry) {
                val ptObj = pt.asJsonObject
                val lat = ptObj.get("lat")?.asDouble
                val lon = ptObj.get("lon")?.asDouble
                if (lat != null && lon != null) {
                    points.add(LatLon(lat, lon))
                }
            }
            return points
        }
    }
}
