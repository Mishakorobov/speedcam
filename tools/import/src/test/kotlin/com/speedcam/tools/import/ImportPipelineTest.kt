package com.speedcam.tools.import

import com.speedcam.core.detect.CameraSource
import com.speedcam.tools.import.db.DatabaseWriter
import com.speedcam.tools.import.source.BoundingBox
import com.speedcam.tools.import.source.OverpassClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the full fetch → parse → merge → write pipeline against a local stub.
 *
 * The public Overpass instances degrade, rate-limit and return HTTP 500 intermittently, so a
 * live test would be flaky for reasons that have nothing to do with this code. A stub on
 * localhost verifies what actually matters: the request/response contract, the JSON decoding,
 * the bounding-box query, and the resulting database.
 */
class ImportPipelineTest {
    @TempDir
    lateinit var tempDir: Path

    /** A minimal but realistic Overpass response: two cameras and their tags. */
    private val camerasResponse =
        """
        {"version":0.6,"generator":"stub","osm3s":{"timestamp_osm_base":"2026-05-31T22:37:44Z"},
        "elements":[
          {"type":"node","id":101,"lat":55.755327,"lon":37.617274,
           "tags":{"highway":"speed_camera","maxspeed":"60","direction":"180","operator":"ГИБДД"}},
          {"type":"node","id":102,"lat":55.700000,"lon":37.500000,
           "tags":{"highway":"speed_camera","maxspeed":"80 km/h","check_date":"2025-11-02"}}
        ]}
        """.trimIndent()

    private val sectionsResponse =
        """
        {"version":0.6,"elements":[
          {"type":"relation","id":9001,
           "tags":{"type":"enforcement","enforcement":"average_speed","maxspeed":"80"},
           "members":[
             {"type":"node","ref":501,"role":"from","lat":55.10,"lon":37.40},
             {"type":"node","ref":502,"role":"to","lat":55.12,"lon":37.42}
           ]},
          {"type":"node","id":501,"lat":55.10,"lon":37.40,"tags":{}},
          {"type":"node","id":502,"lat":55.12,"lon":37.42,"tags":{}}
        ]}
        """.trimIndent()

    @Test
    fun `fetches, merges and writes a database the app can open`() {
        val requests = AtomicInteger()

        val endpoint =
            stubServer { query ->
                requests.incrementAndGet()
                if (query.contains("relation")) sectionsResponse else camerasResponse
            }

        // One tile, so the whole area resolves in a single request per endpoint.
        val area = BoundingBox(55.5, 37.0, 56.0, 38.0)

        val cameras = OverpassClient(listOf(endpoint)).use { it.fetchCameras(area) }
        val sections = OverpassClient(listOf(endpoint)).use { it.fetchSections(area) }

        assertEquals(2, cameras.size, "expected both camera nodes")
        assertEquals(1, sections.size, "expected the average-speed corridor")

        // Spot-check the decoded values rather than only the count.
        val first = cameras.first { it.externalId == "node/101" }
        assertEquals(60, first.limitKmh)
        assertEquals(180.0, first.facingDeg!!, 1e-9)
        assertEquals("ГИБДД", first.operator)
        assertEquals(CameraSource.OSM, first.source)

        val second = cameras.first { it.externalId == "node/102" }
        assertEquals(80, second.limitKmh, "the unit suffix must be stripped")
        assertEquals("2025-11-02", second.checkDate)
        assertEquals(null, second.facingDeg, "no direction tag means no facing")

        assertEquals(80, sections.single().limitKmh)

        // Now write it and confirm the database is well-formed.
        val merged = Deduplicator().merge(cameras)
        val db = tempDir.resolve("pipeline.db").toString()

        DatabaseWriter.create(db).use { writer ->
            writer.open()
            writer.createSchema()
            writer.replaceCameras(merged)
            writer.replaceSections(sections)
            writer.putMeta("built_at", "test")
        }

        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM camera").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM section").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
                // The identity hash is what lets Room open this file later.
                st.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { rs ->
                    assertTrue(rs.next(), "room_master_table must be seeded")
                    assertEquals(DatabaseWriter.IDENTITY_HASH, rs.getString(1))
                }
            }
        }

        assertTrue(requests.get() >= 2, "expected at least one request per query type")
    }

    @Test
    fun `a failing endpoint falls through to the next and is retried`() {
        val endpoint =
            stubServer { query ->
                if (query.contains("relation")) sectionsResponse else camerasResponse
            }
        // A port nothing is listening on, to simulate a dead mirror.
        val dead = "http://127.0.0.1:1/api/interpreter"

        val cameras =
            OverpassClient(listOf(dead, endpoint), maxAttempts = 2).use {
                it.fetchCameras(BoundingBox(55.5, 37.0, 56.0, 38.0))
            }

        assertEquals(2, cameras.size, "the healthy endpoint should still serve the data")
    }

    @Test
    fun `a tile that fails everywhere is reported rather than silently dropped`() {
        val dead = "http://127.0.0.1:1/api/interpreter"

        val client = OverpassClient(listOf(dead), maxAttempts = 1)
        val cameras = client.use { it.fetchCameras(BoundingBox(55.5, 37.0, 56.0, 38.0)) }

        assertTrue(cameras.isEmpty())
        assertEquals(1, client.failedTiles.size, "the skipped tile must be reported to the caller")
    }

    /** Starts a loopback server that answers every POST with [respond]. */
    private fun stubServer(respond: (String) -> String): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/interpreter") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            // The client form-encodes the query, so it arrives as `data=<urlencoded>`.
            val query =
                body
                    .substringAfter("data=", "")
                    .replace("+", " ")
                    .let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }

            val payload = respond(query).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.executor = null
        server.start()

        Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
        return "http://127.0.0.1:${server.address.port}/api/interpreter"
    }
}
