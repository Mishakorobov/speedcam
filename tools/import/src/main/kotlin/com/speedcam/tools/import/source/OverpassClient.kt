package com.speedcam.tools.import.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches camera data from an Overpass instance.
 *
 * Country-scale queries against the public instances are unreliable: a bbox the size of
 * Russia reliably times out or returns HTTP 500. Two things make this workable —
 *
 *  - the region is split into tiles sized to stay inside an instance's per-query budget, and
 *  - a tile that still fails after [maxAttempts] is recorded in [failedTiles] and skipped,
 *    rather than aborting the import.
 *
 * Tiles are bounded, so a persistent failure degrades coverage in one area instead of losing
 * the whole country. The caller is expected to surface [failedTiles].
 */
class OverpassClient(
    endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val maxAttempts: Int = 3,
    private val requestTimeout: Duration = Duration.ofSeconds(180),
    private val throttle: Duration = DEFAULT_THROTTLE,
) : AutoCloseable {
    /**
     * Public mirrors go down and rate-limit unpredictably; a client pinned to one endpoint
     * fails wholesale when that instance is degraded. Each attempt advances to the next
     * endpoint, so a single outage costs one retry rather than the whole import.
     */
    private val endpoints: List<String> = endpoints.ifEmpty { DEFAULT_ENDPOINTS }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    /** Tiles that failed every attempt. Empty after a fully successful run. */
    val failedTiles = mutableListOf<BoundingBox>()

    /** Timestamp of the last completed request, used to pace consecutive calls. */
    private var lastRequestAt = 0L

    fun fetchCameras(
        bbox: BoundingBox,
        tile: BoundingBox = TILE,
    ): List<Camera> {
        val out = mutableListOf<Camera>()
        for (cell in bbox.tiles(tile)) {
            val body = fetchTile(cell, ::cameraQuery) ?: continue
            out += OverpassJson.parseCameras(body)
        }
        return out
    }

    fun fetchSections(
        bbox: BoundingBox,
        tile: BoundingBox = TILE,
    ): List<SectionRecord> {
        val out = mutableListOf<SectionRecord>()
        for (cell in bbox.tiles(tile)) {
            val body = fetchTile(cell, ::sectionQuery) ?: continue
            out += OverpassJson.parseSections(body)
        }
        return out
    }

    /**
     * Returns the response body, or null after exhausting retries for this tile.
     *
     * Returns rather than throws: one unavailable tile must not abort an import that has
     * already collected tens of thousands of records.
     */
    private fun fetchTile(
        cell: BoundingBox,
        query: (BoundingBox) -> String,
    ): String? {
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            try {
                return post(endpoints[attempt % endpoints.size], query(cell))
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1))
            }
        }

        failedTiles += cell
        System.err.println("  tile ${cell.describe()} failed after $maxAttempts attempts: ${lastError?.message}")
        return null
    }

    /**
     * Spaces out consecutive requests.
     *
     * These instances are donated infrastructure shared with thousands of other users. A
     * country-wide import is hundreds of requests; firing them back to back is how a client
     * earns a rate-limit ban and degrades the service for everyone else.
     */
    private fun paceRequest() {
        if (lastRequestAt == 0L) {
            lastRequestAt = System.nanoTime()
            return
        }
        val elapsedMs = (System.nanoTime() - lastRequestAt) / 1_000_000
        val waitMs = throttle.toMillis() - elapsedMs
        if (waitMs > 0) Thread.sleep(waitMs)
        lastRequestAt = System.nanoTime()
    }

    private fun post(
        endpoint: String,
        query: String,
    ): String {
        paceRequest()
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8)
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "HTTP ${response.statusCode()}: ${response.body().take(300)}"
        }
        return response.body()
    }

    private fun cameraQuery(b: BoundingBox): String =
        "[out:json][timeout:180];node[\"highway\"=\"speed_camera\"]" +
            "(${b.south},${b.west},${b.north},${b.east});out body;"

    private fun sectionQuery(b: BoundingBox): String =
        "[out:json][timeout:180];" +
            "relation[\"type\"=\"enforcement\"][\"enforcement\"=\"average_speed\"]" +
            "(${b.south},${b.west},${b.north},${b.east});out body;"

    /**
     * No-op on purpose.
     *
     * `java.net.http.HttpClient` only became `AutoCloseable` in Java 21; on 17 its selector
     * thread is a daemon that does not keep the JVM alive, which is what a CLI wants. The
     * method exists so call sites can use `use { }` uniformly.
     */
    override fun close() = Unit

    companion object {
        const val DEFAULT_ENDPOINT = "https://overpass.kumi.systems/api/interpreter"

        /**
         * Mirrors tried in rotation.
         *
         * The public instances degrade and rate-limit independently and unpredictably. A
         * single-endpoint client fails wholesale when one is unhealthy, which during a
         * country-wide import means losing everything.
         */
        val DEFAULT_ENDPOINTS =
            listOf(
                DEFAULT_ENDPOINT,
                "https://overpass.private.coffee/api/interpreter",
                "https://overpass-api.de/api/interpreter",
            )

        /** Overpass asks clients to identify themselves; anonymous agents get throttled. */
        private const val USER_AGENT = "speedcam-import/1.0 (personal use)"

        private const val RETRY_BACKOFF_MILLIS = 4_000L

        /**
         * Minimum gap between consecutive requests.
         *
         * A country-wide import is hundreds of requests against donated infrastructure.
         */
        val DEFAULT_THROTTLE: Duration = Duration.ofMillis(750)

        /**
         * Tile size: 5 degrees of latitude by 10 of longitude.
         *
         * Sized so a typical request returns a few hundred nodes, which the public instances
         * handle comfortably, while a full Russia import still resolves in a manageable
         * number of requests.
         */
        val TILE = BoundingBox(0.0, 0.0, 5.0, 10.0)
    }
}

/** A latitude/longitude window, used both as a query area and as a tiling unit. */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south < north) { "south ($south) must be below north ($north)" }
        require(west < east) { "west ($west) must be left of east ($east)" }
        require(south >= -90.0 && north <= 90.0) { "latitude out of range" }
        require(west >= -180.0 && east <= 180.0) { "longitude out of range" }
    }

    /**
     * Splits this box into cells no larger than [tile].
     *
     * Cells stay rectangular because that is what Overpass expects. A box crossing the
     * antimeridian is therefore not representable, which is why Kaliningrad is queried
     * separately from the mainland rather than being folded into one bbox.
     */
    fun tiles(tile: BoundingBox): List<BoundingBox> {
        val latStep = tile.north - tile.south
        val lonStep = tile.east - tile.west
        val out = mutableListOf<BoundingBox>()

        var lat = south
        while (lat < north - EPSILON) {
            val latNext = minOf(lat + latStep, north)
            var lon = west
            while (lon < east - EPSILON) {
                val lonNext = minOf(lon + lonStep, east)
                out += BoundingBox(lat, lon, latNext, lonNext)
                lon = lonNext
            }
            lat = latNext
        }
        return out
    }

    fun describe(): String = "(%.3f,%.3f)-(%.3f,%.3f)".format(south, west, north, east)

    companion object {
        private const val EPSILON = 1e-9

        /**
         * Russia, west edge set just outside Kaliningrad Oblast (19.5 E) and east edge just
         * short of the antimeridian.
         *
         * The country is a single rectangle at this scale. Kaliningrad is an exclave in the
         * west but sits inside this bounding box, so it needs no separate query.
         */
        val RUSSIA = BoundingBox(41.0, 19.0, 82.0, 179.99)
    }
}

/** Decodes Overpass JSON responses into domain types. */
object OverpassJson {
    fun parseCameras(json: String): List<Camera> =
        elements(json).mapNotNull { element ->
            val tags = element.tags()
            val lat = element.double("lat") ?: return@mapNotNull null
            val lon = element.double("lon") ?: return@mapNotNull null
            val id = element.double("id")?.toLong() ?: return@mapNotNull null

            Camera(
                id = 0L,
                position = LatLon(lat, lon),
                source = CameraSource.OSM,
                externalId = "node/$id",
                limitKmh = OverpassMapper.parseLimit(tags["maxspeed"]),
                kind = OverpassMapper.kindFrom(tags),
                facingDeg = OverpassMapper.parseDirection(tags["direction"]),
                dirCount = if (tags["direction"] != null) 1 else 2,
                operator = tags["operator"],
                sourceDetail = null,
                checkDate = tags["check_date"] ?: tags["survey:date"],
            )
        }

    /**
     * Extracts average-speed corridors from enforcement relations.
     *
     * A relation names a `from` and a `to` node rather than repeating coordinates, so the
     * member nodes are expected to be resolved by the caller. A relation whose endpoints were
     * not resolved is skipped rather than guessed.
     */
    fun parseSections(json: String): List<SectionRecord> {
        val byId = elements(json).associateBy { it.double("id")?.toLong() }
        return elements(json)
            .asSequence()
            .filter(::isAverageSpeedRelation)
            .mapNotNull { buildSection(it, byId) }
            .toList()
    }

    private fun isAverageSpeedRelation(el: JsonObject): Boolean {
        if (el.string("type") != "relation") return false
        val tags = el.tags()
        return tags["type"] == "enforcement" && tags["enforcement"] == "average_speed"
    }

    private fun buildSection(
        relation: JsonObject,
        byId: Map<Long?, JsonObject>,
    ): SectionRecord? {
        val (fromNode, toNode) = extractEndpoints(relation, byId) ?: return null
        val limit = resolveSectionLimit(relation.tags(), fromNode.tags(), toNode.tags()) ?: return null
        val (from, to) = extractEndpointsCoordinates(fromNode, toNode) ?: return null
        return SectionRecord(from, to, limit)
    }

    private fun extractEndpointsCoordinates(
        fromNode: JsonObject,
        toNode: JsonObject,
    ): Pair<LatLon, LatLon>? {
        val from = extractPoint(fromNode) ?: return null
        val to = extractPoint(toNode) ?: return null
        return if (from != to) from to to else null
    }

    private fun extractEndpoints(
        relation: JsonObject,
        byId: Map<Long?, JsonObject>,
    ): Pair<JsonObject, JsonObject>? {
        val members = relation.array("members") ?: return null
        var from: JsonObject? = null
        var to: JsonObject? = null
        for (m in members) {
            val role = m.string("role")
            val ref = m.double("ref")?.toLong()
            if (role == "from" && from == null) from = byId[ref]
            if (role == "to" && to == null) to = byId[ref]
        }
        return if (from != null && to != null) from to to else null
    }

    private fun extractPoint(node: JsonObject): LatLon? {
        val lat = node.double("lat") ?: return null
        val lon = node.double("lon") ?: return null
        return LatLon(lat, lon)
    }

    private fun resolveSectionLimit(
        relTags: Map<String, String>,
        fromTags: Map<String, String>,
        toTags: Map<String, String>,
    ): Int? =
        OverpassMapper.parseLimit(relTags["maxspeed"])
            ?: OverpassMapper.parseLimit(fromTags["maxspeed"])
            ?: OverpassMapper.parseLimit(toTags["maxspeed"])

    private fun elements(json: String): List<JsonObject> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.getAsJsonArray("elements")?.map { it.asJsonObject } ?: emptyList()
    }

    private fun JsonObject.tags(): Map<String, String> {
        val obj = getAsJsonObject("tags") ?: return emptyMap()
        return obj.entrySet().associate { (k, v) -> k to v.asString }
    }

    private fun JsonObject.array(name: String): List<JsonObject>? = getAsJsonArray(name)?.map { it.asJsonObject }

    private fun JsonObject.double(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive }?.asDouble

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

/** Interprets the OSM tags on a camera node. */
object OverpassMapper {
    /** Bounds of a plausible road speed limit, in km/h. */
    private const val MIN_LIMIT_KMH = 5
    private const val MAX_LIMIT_KMH = 185

    /**
     * Parses `maxspeed`, which is free-form: `"60"`, `"60 km/h"`, `"30 mph"`, `"RU:urban"`.
     *
     * Anything not a plain number is treated as unknown rather than guessed. A named zone such
     * as `RU:urban` encodes a country default that changes over time and by region; guessing
     * would embed a policy the source deliberately left open, and a wrong limit silently
     * corrupts excess-based suppression.
     */
    internal fun parseLimit(raw: String?): Int? {
        val text =
            raw
                ?.trim()
                ?.lowercase()
                ?.removeSuffix("km/h")
                ?.removeSuffix("км/ч")
                ?.removeSuffix("кмч")
                ?.removeSuffix("kmh")
                ?.removeSuffix("kph")
                ?.removeSuffix("mph")
                ?.trim()
                ?: return null

        val value = text.toIntOrNull()
        return if (value != null && value in MIN_LIMIT_KMH..MAX_LIMIT_KMH) value else null
    }

    /**
     * Cardinal and intercardinal bearings, in the forms OSM accepts — English and Russian.
     */
    private val CARDINALS: Map<String, Double> =
        mapOf(
            "n" to 0.0,
            "north" to 0.0,
            "север" to 0.0,
            "с" to 0.0,
            "nne" to 22.5,
            "ne" to 45.0,
            "northeast" to 45.0,
            "северо-восток" to 45.0,
            "северовосток" to 45.0,
            "св" to 45.0,
            "ene" to 67.5,
            "e" to 90.0,
            "east" to 90.0,
            "восток" to 90.0,
            "в" to 90.0,
            "ese" to 112.5,
            "se" to 135.0,
            "southeast" to 135.0,
            "юго-восток" to 135.0,
            "юговосток" to 135.0,
            "юв" to 135.0,
            "sse" to 157.5,
            "s" to 180.0,
            "south" to 180.0,
            "юг" to 180.0,
            "ю" to 180.0,
            "ssw" to 202.5,
            "sw" to 225.0,
            "southwest" to 225.0,
            "юго-запад" to 225.0,
            "югозапад" to 225.0,
            "юз" to 225.0,
            "wsw" to 247.5,
            "w" to 270.0,
            "west" to 270.0,
            "запад" to 270.0,
            "з" to 270.0,
            "wnw" to 292.5,
            "nw" to 315.0,
            "northwest" to 315.0,
            "северо-запад" to 315.0,
            "северозапад" to 315.0,
            "сз" to 315.0,
            "nnw" to 337.5,
        )

    /**
     * Parses `direction`, which is either a bearing in degrees or a cardinal word.
     *
     * Both forms appear in the wild for OSM, and both are accepted.
     */
    internal fun parseDirection(raw: String?): Double? {
        val text = raw?.trim()?.lowercase() ?: return null
        val numeric = text.toDoubleOrNull()

        return when {
            numeric != null -> numeric.takeIf { it in 0.0..360.0 }
            else -> CARDINALS[text]
        }
    }

    internal fun kindFrom(tags: Map<String, String>): com.speedcam.core.detect.CameraKind =
        when {
            tags["dummy"] == "yes" || tags["enforcement"] == "dummy" -> com.speedcam.core.detect.CameraKind.DUMMY
            tags["enforcement"] == "average_speed" -> com.speedcam.core.detect.CameraKind.AVERAGE_ENTRY
            tags["enforcement"] == "traffic_signals" -> com.speedcam.core.detect.CameraKind.RED_LIGHT
            tags["man_made"] == "surveillance" -> com.speedcam.core.detect.CameraKind.COMPLEX
            else -> com.speedcam.core.detect.CameraKind.FIXED
        }
}
