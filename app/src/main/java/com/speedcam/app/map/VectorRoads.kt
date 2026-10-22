package com.speedcam.app.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

/** Status of vector roads availability. */
enum class RoadDataStatus {
    OFFLINE,
    LOADING,
    LOADED,
}

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
 * Caches roads in memory and on disk. When online, retrieves road geometries
 * from OpenStreetMap Overpass API using multiple mirrors with failover and cooldown.
 */
class VectorRoadRepository(
    private val context: Context,
) {
    private val cacheDir = File(context.cacheDir, "vector_roads").apply { mkdirs() }
    private val memoryCache = ConcurrentHashMap<String, List<VectorRoad>>()
    private val inFlightQueries = ConcurrentHashMap.newKeySet<String>()
    private val failedQueries = ConcurrentHashMap<String, Long>()

    /**
     * Whether to query remote Overpass API mirrors.
     * Disabled by default in favor of high-performance offline raster/MBTiles cartography.
     */
    var enableOverpassQueries: Boolean = false

    fun isNetworkAvailable(): Boolean =
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)

    /**
     * Retrieves vector roads for [box]. Returns cached roads immediately,
     * and triggers a background query across mirrors if network is available.
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

            // Skip remote Overpass API queries if disabled in favor of offline tile cartography
            if (!enableOverpassQueries) {
                return@withContext emptyList()
            }

            // Respect cooldown period for failed boxes
            val cooldownUntil = failedQueries[tileKey]
            if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
                return@withContext emptyList()
            }

            // Trigger non-blocking fetch with failover
            if (!inFlightQueries.contains(tileKey)) {
                inFlightQueries.add(tileKey)
                try {
                    fetchOverpassRoads(box, tileKey, zoom)
                } finally {
                    inFlightQueries.remove(tileKey)
                }
            }

            memoryCache[tileKey] ?: emptyList()
        }

    private fun computeTileKey(box: BoundingBox): String {
        // Discretize to ~0.04 degree grid cells (~4km)
        val latCell = (box.minLat * 25).toInt()
        val lonCell = (box.minLon * 25).toInt()
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
        zoom: Float,
    ) {
        val highwayFilter =
            if (zoom < 13.0f) {
                "way[\"highway\"~\"motorway|trunk|primary\"]"
            } else {
                "way[\"highway\"~\"motorway|trunk|primary|secondary|tertiary\"]"
            }

        val query =
            "[out:json][timeout:5];" +
                "($highwayFilter" +
                "(${box.minLat},${box.minLon},${box.maxLat},${box.maxLon}););out geom qt 250;"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        for (mirror in OVERPASS_MIRRORS) {
            val success =
                runCatching {
                    val url = URL("$mirror?data=$encoded")
                    val conn =
                        (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 3500
                            readTimeout = 3500
                            setRequestProperty("User-Agent", "SpeedCam-VectorMap/1.0")
                        }

                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val json = conn.inputStream.bufferedReader().use { it.readText() }
                        val roads = parseOverpassJson(json)
                        if (roads.isNotEmpty()) {
                            memoryCache[tileKey] = roads
                            saveToDiskCache(tileKey, json)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }.getOrDefault(false)

            if (success) {
                failedQueries.remove(tileKey)
                return
            }
        }

        // All mirrors failed or timed out: enter 45-second cooldown
        failedQueries[tileKey] = System.currentTimeMillis() + 45_000L
        Log.d(TAG, "Overpass query across all mirrors skipped or unavailable for $tileKey")
    }

    companion object {
        private const val TAG = "VectorRoadRepository"
        const val MIN_ROAD_ZOOM = 11.0f

        val OVERPASS_MIRRORS =
            listOf(
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter",
                "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            )

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
