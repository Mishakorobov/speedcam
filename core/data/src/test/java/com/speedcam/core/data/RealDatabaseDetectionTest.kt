package com.speedcam.core.data

import com.speedcam.core.detect.AlertProfile
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.DetectionEngine
import com.speedcam.core.detect.Fix
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.Geo
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs the real detection engine against the real imported database.
 *
 * Every other test in this project constructs its own fixtures, which is the right default:
 * they are deterministic and they pin down specific rules. The weakness of fixtures is that
 * they only exercise the shapes their author imagined. This class closes that gap by driving
 * [DetectionEngine] with rows actually parsed out of OpenStreetMap, through the production
 * [CameraMappers] and the same bounding-box SQL that [CameraDao] issues.
 *
 * It is therefore the test that fails if the schema, the importer, the mapper and the engine
 * ever drift apart — none of which a fixture can detect.
 *
 * The database is gitignored and about 1 MB, so it is absent on a fresh clone and in CI. The
 * suite skips itself rather than failing when the file is missing.
 */
class RealDatabaseDetectionTest {
    companion object {
        private const val METRES_PER_DEGREE = 111_320.0
        private const val INDEX_NAME = "index_camera_lat_lon"
        private const val SELECT_ALL =
            "SELECT id, source, external_id, lat, lon, limit_kmh, kind, facing_deg, " +
                "dir_count, operator, source_detail, check_date FROM camera"

        /** A single-direction camera with a recorded facing, in the dense Moscow area. */
        private const val DIRECTIONAL_NEAR_MOSCOW =
            "facing_deg IS NOT NULL AND dir_count = 1 " +
                "AND lat BETWEEN 55.70 AND 55.81 AND lon BETWEEN 37.55 AND 37.68"

        /** Two-direction camera with no facing, so only the back-shot rule can apply to it. */
        private const val BIDIRECTIONAL_NEAR_MOSCOW =
            "facing_deg IS NULL AND dir_count >= 2 " +
                "AND lat BETWEEN 55.70 AND 55.81 AND lon BETWEEN 37.55 AND 37.68"

        private lateinit var connection: Connection
        private lateinit var query: CameraQuery

        /**
         * `data/russia.db` sits at the project root while this test runs with the module
         * directory as its working directory, so the file is located by walking upwards
         * rather than by hardcoding a relative path.
         */
        private fun locateDatabase(): File? {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "data/russia.db")
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            return null
        }

        @BeforeAll
        @JvmStatic
        fun openDatabase() {
            val file = locateDatabase()
            assumeTrue(
                file != null,
                "data/russia.db is not present; run tools/import/update-osm.bat to build it",
            )
            val properties =
                Properties().apply {
                    // READONLY, so a bug in this test cannot damage the user's database.
                    setProperty("open_mode", "1")
                }
            val url = "jdbc:sqlite:" + file!!.absolutePath.replace('\\', '/')
            connection = DriverManager.getConnection(url, properties)
            query = JdbcCameraQuery(connection)
        }

        @AfterAll
        @JvmStatic
        fun closeDatabase() {
            if (::connection.isInitialized) connection.close()
        }
    }

    private val engine = DetectionEngine(query)

    /** Moscow city centre. Dense enough that the test always has real cameras to work with. */
    private val moscow = LatLon(55.7522, 37.6156)

    // ------------------------------------------------------------- data sanity

    @Test
    fun `the database holds a plausible number of cameras`() {
        // Guards against a truncated or half-imported file making every other assertion here
        // vacuously true.
        assertTrue(countAll() > 10_000, "expected a full country import but found only ${countAll()}")
    }

    @Test
    fun `every stored camera maps into a usable domain model`() {
        val rows =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, source, kind FROM camera").use { rs ->
                    val out = ArrayList<Triple<Long, Int, Int>>()
                    while (rs.next()) {
                        out += Triple(rs.getLong("id"), rs.getInt("source"), rs.getInt("kind"))
                    }
                    out
                }
            }
        assertTrue(rows.isNotEmpty(), "the camera table is empty")

        // The importer is a separate JVM program that writes these columns as raw integers.
        // If its idea of the enum orderings ever drifts from the app's, CameraMappers silently
        // falls back to OSM/UNKNOWN and every row is mislabelled rather than failing loudly.
        // Asserting on the stored ordinals is what makes that drift visible here.
        rows.forEach { (id, source, kind) ->
            assertTrue(
                source in CameraSource.entries.indices,
                "row $id has source ordinal $source, which this build cannot interpret",
            )
            assertTrue(
                kind in CameraKind.entries.indices,
                "row $id has kind ordinal $kind, which this build cannot interpret",
            )
        }
    }

    @Test
    fun `every stored camera has usable coordinates and a provenance key`() {
        allCameras().forEach { c ->
            assertTrue(c.position.lat in -90.0..90.0, "latitude out of range on row ${c.id}")
            assertTrue(c.position.lon in -180.0..180.0, "longitude out of range on row ${c.id}")
            assertNotNull(c.externalId, "row ${c.id} lost its provenance key")
        }
    }

    @Test
    fun `the compound index serves the candidate query`() {
        // Plan.md 5.5 justifies skipping an R-tree on the grounds that the (lat, lon) index
        // makes each query cheap. That claim is only worth making if SQLite picks it.
        val plan =
            connection
                .prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT id FROM camera " +
                        "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                ).use { ps ->
                    ps.setDouble(1, moscow.lat - 0.02)
                    ps.setDouble(2, moscow.lat + 0.02)
                    ps.setDouble(3, moscow.lon - 0.04)
                    ps.setDouble(4, moscow.lon + 0.04)
                    ps.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString("detail")) } }
                }
        assertTrue(
            plan.contains(INDEX_NAME),
            "expected the planner to use $INDEX_NAME but the plan was: $plan",
        )
    }

    @Test
    fun `the bounding box query returns nothing outside the box`() {
        val box = Geo.boundingBox(moscow, 2_000.0)
        val candidates = query.inBox(box)
        assumeTrue(candidates.isNotEmpty(), "no cameras within 2 km of the Moscow anchor")

        candidates.forEach { c ->
            assertTrue(c.position in box, "camera ${c.id} at ${c.position} escaped the box $box")
        }
    }

    @Test
    fun `the bounding box query is far more selective than the whole table`() {
        val candidates = query.inBox(Geo.boundingBox(moscow, 2_000.0))
        assumeTrue(candidates.isNotEmpty(), "no cameras within 2 km of the Moscow anchor")
        assertTrue(
            candidates.size < countAll() / 100,
            "a 2 km box returned ${candidates.size} of ${countAll()} rows; the index is not doing its job",
        )
    }

    // ------------------------------------------------------- detection, for real

    @Test
    fun `detects a real camera approached from the side it faces`() {
        val picked = pickCamera(DIRECTIONAL_NEAR_MOSCOW)
        assumeTrue(picked != null, "no single-direction camera near the Moscow anchor")
        val target = picked ?: return
        assumeTrue(target.facingDeg != null, "the fixture query must select a camera with a direction")
        val facing = target.facingDeg ?: return

        val hazards = engine.detect(approachFor(target, facing, 300.0), WIDE_ENOUGH)
        val match = hazards.firstOrNull { it.camera.id == target.id }

        assertNotNull(
            match,
            "camera ${target.id} at 300 m was not reported; ${hazards.size} other hazards were",
        )
        assertTrue(
            abs(match!!.distanceM - 300.0) < 20.0,
            "expected roughly 300 m but measured ${match.distanceM}",
        )
        assertTrue(
            abs(match.relativeBearingDeg) < 5.0,
            "expected the camera dead ahead but it was at ${match.relativeBearingDeg} degrees",
        )
    }

    @Test
    fun `suppresses a real camera that faces the other way`() {
        val picked = pickCamera(DIRECTIONAL_NEAR_MOSCOW)
        assumeTrue(picked != null, "no single-direction camera near the Moscow anchor")
        val target = picked ?: return

        // Same camera, same distance, approached from the other side: it photographs the
        // opposite stream and must not warn us.
        val hazards = engine.detect(approachFor(target, target.facingDeg!! + 180.0, 300.0), WIDE_ENOUGH)
        assertTrue(
            hazards.none { it.camera.id == target.id },
            "camera ${target.id} faces ${target.facingDeg} and must not warn an approach from behind",
        )
    }

    @Test
    fun `backshotOnly suppresses a bidirectional camera that is behind us`() {
        val picked = pickCamera(BIDIRECTIONAL_NEAR_MOSCOW)
        assumeTrue(picked != null, "no two-direction camera near the Moscow anchor")
        val target = picked ?: return

        // Fix 300 m north of the camera heading south, which puts the camera behind us.
        val headingSouth = fixAt(step(target.position, 180.0, 300.0), 180.0)

        assertTrue(
            engine.detect(headingSouth, WIDE_ENOUGH).any { it.camera.id == target.id },
            "the camera must be reported when back-shot warnings are enabled",
        )
        assertTrue(
            engine.detect(headingSouth, WIDE_ENOUGH.copy(backshotOnly = true)).none { it.camera.id == target.id },
            "backshotOnly must drop a camera behind the vehicle",
        )
    }

    @Test
    fun `no camera is reported beyond the capture distance`() {
        val picked = pickCamera(BIDIRECTIONAL_NEAR_MOSCOW)
        assumeTrue(picked != null, "no two-direction camera near the Moscow anchor")
        val target = picked ?: return

        // The fix sits on top of a camera, so the result set cannot be empty. Anchoring on a
        // fixed city-centre point instead left this test silently skipped, because no camera
        // happened to fall inside the radius from there.
        val hazards = engine.detect(fixAt(target.position, 0.0), WIDE_ENOUGH)
        assertTrue(
            hazards.any { it.camera.id == target.id },
            "a camera under the vehicle must be reported",
        )

        hazards.forEach { h ->
            assertTrue(
                h.distanceM <= WIDE_ENOUGH.captureDistanceKm * 1000.0,
                "camera ${h.camera.id} at ${h.distanceM} m exceeded the radius",
            )
        }
    }

    @Test
    fun `hazards come back sorted nearest first`() {
        val picked = pickCamera(BIDIRECTIONAL_NEAR_MOSCOW)
        assumeTrue(picked != null, "no two-direction camera near the Moscow anchor")
        val target = picked ?: return

        val wide = AlertProfile(captureDistanceKm = 2.0)
        val hazards = engine.detect(fixAt(target.position, 0.0), wide)
        assertTrue(hazards.size >= 2, "need at least two cameras around ${target.position}")

        val distances = hazards.map { it.distanceM }
        assertEquals(distances.sorted(), distances, "hazards must be ordered nearest first")
    }

    // ------------------------------------------------------------- test helpers

    /**
     * Places a fix [metres] from [camera] so that the vehicle travels the way the camera
     * photographs, and returns it.
     *
     * A camera facing `f` watches the stream running at `f + 180`, so the driver sits along `f`
     * from the camera and heads `f + 180`. Getting this backwards would test the facing gate
     * against a camera that genuinely should warn.
     */
    private fun approachFor(
        camera: Camera,
        facingDeg: Double,
        metres: Double,
    ): Fix = fixAt(step(camera.position, facingDeg, metres), Geo.normalize360(facingDeg + 180.0))

    private fun fixAt(
        position: LatLon,
        headingDeg: Double,
    ) = Fix(
        position = position,
        timestampMillis = 0L,
        accuracyM = 5f,
        speedMs = (60.0 / 3.6).toFloat(),
        headingDeg = headingDeg,
    )

    /** Moves [metres] from [from] along [bearingDeg]. Flat-earth approximation over a few km. */
    private fun step(
        from: LatLon,
        bearingDeg: Double,
        metres: Double,
    ): LatLon {
        val theta = bearingDeg * Math.PI / 180.0
        val dLat = (metres * cos(theta)) / METRES_PER_DEGREE
        val cosLat = cos(from.lat * Math.PI / 180.0)
        val dLon =
            if (abs(cosLat) < 1e-9) 0.0 else (metres * sin(theta)) / (METRES_PER_DEGREE * cosLat)
        return LatLon(from.lat + dLat, from.lon + dLon)
    }

    private fun pickCamera(where: String): Camera? =
        connection.createStatement().use { statement ->
            statement.executeQuery("$SELECT_ALL WHERE $where LIMIT 1").use { rs ->
                if (rs.next()) readCamera(rs) else null
            }
        }

    private fun countAll(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM camera").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun allCameras(): List<Camera> =
        connection.createStatement().use { statement ->
            statement.executeQuery(SELECT_ALL).use { rs ->
                val out = ArrayList<Camera>()
                while (rs.next()) out += readCamera(rs)
                out
            }
        }

    private fun readCamera(rs: ResultSet): Camera =
        CameraMappers.toDomain(
            CameraEntity(
                id = rs.getLong("id"),
                source = rs.getInt("source"),
                externalId = rs.getString("external_id"),
                lat = rs.getDouble("lat"),
                lon = rs.getDouble("lon"),
                limitKmh = rs.intOrNull("limit_kmh"),
                kind = rs.getInt("kind"),
                facingDeg = rs.doubleOrNull("facing_deg"),
                dirCount = rs.getInt("dir_count"),
                operator = rs.getString("operator"),
                sourceDetail = rs.getString("source_detail"),
                checkDate = rs.getString("check_date"),
            ),
        )
}

private val WIDE_ENOUGH = AlertProfile(captureDistanceKm = 0.5)

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).let { if (wasNull()) null else it }

private fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).let { if (wasNull()) null else it }

/**
 * Reads candidates straight from the SQLite file.
 *
 * Intentionally re-implements the bounding-box query rather than reusing [CameraDao]: this is
 * a plain JVM unit test, and Room's generated implementation needs a device or an emulator. The
 * SQL matches [CameraDao.queryBox] — if the schema drifts, this query stops returning the real
 * columns and the tests fail loudly instead of quietly passing against a stub.
 */
private class JdbcCameraQuery(
    private val connection: Connection,
) : CameraQuery {
    override fun inBox(box: BoundingBox): List<Camera> {
        val sql =
            if (box.crossesAntimeridian) {
                "SELECT id, source, external_id, lat, lon, limit_kmh, kind, facing_deg, " +
                    "dir_count, operator, source_detail, check_date FROM camera " +
                    "WHERE lat BETWEEN ? AND ? AND (lon >= ? OR lon <= ?)"
            } else {
                "SELECT id, source, external_id, lat, lon, limit_kmh, kind, facing_deg, " +
                    "dir_count, operator, source_detail, check_date FROM camera " +
                    "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"
            }
        return connection
            .prepareStatement(sql)
            .use { ps ->
                ps.setDouble(1, box.minLat)
                ps.setDouble(2, box.maxLat)
                ps.setDouble(3, box.minLon)
                ps.setDouble(4, box.maxLon)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Camera>()
                    while (rs.next()) out += readCameraRow(rs)
                    out
                }
            }
    }

    private fun readCameraRow(rs: ResultSet): Camera =
        CameraMappers.toDomain(
            CameraEntity(
                id = rs.getLong("id"),
                source = rs.getInt("source"),
                externalId = rs.getString("external_id"),
                lat = rs.getDouble("lat"),
                lon = rs.getDouble("lon"),
                limitKmh = rs.intOrNull("limit_kmh"),
                kind = rs.getInt("kind"),
                facingDeg = rs.doubleOrNull("facing_deg"),
                dirCount = rs.getInt("dir_count"),
                operator = rs.getString("operator"),
                sourceDetail = rs.getString("source_detail"),
                checkDate = rs.getString("check_date"),
            ),
        )
}
