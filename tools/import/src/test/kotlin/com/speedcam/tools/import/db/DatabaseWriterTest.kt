package com.speedcam.tools.import.db

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class DatabaseWriterTest {
    @TempDir
    lateinit var tempDir: Path

    private fun db(name: String = "test.db"): DatabaseWriter =
        DatabaseWriter.create(tempDir.resolve(name).toString()).also {
            it.open()
            it.createSchema()
        }

    @Test
    fun `creates every table the schema declares`() {
        db().use { it.putMeta("k", "v") }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            val tables = tableNames(c)
            for (expected in listOf("camera", "section", "override_", "meta", "room_master_table")) {
                assertTrue(expected in tables, "missing table $expected; found $tables")
            }
        }
    }

    @Test
    fun `seeds the room master table with the identity hash`() {
        db().use { it.putMeta("k", "v") }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT identity_hash FROM room_master_table WHERE id = 42").use { rs ->
                    assertTrue(rs.next(), "room_master_table row 42 is missing")
                    assertEquals(DatabaseWriter.IDENTITY_HASH, rs.getString(1))
                }
            }
        }
    }

    @Test
    fun `writes and reads back a camera row`() {
        val camera =
            Camera(
                id = 0L,
                position = LatLon(55.755327, 37.617274),
                source = CameraSource.OSM,
                externalId = "node/12345",
                limitKmh = 60,
                kind = CameraKind.RED_LIGHT,
                facingDeg = 180.0,
                dirCount = 1,
                operator = "ГИБДД",
                sourceDetail = null,
                checkDate = "2026-01-15",
            )
        db().use { it.replaceCameras(listOf(camera)) }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT lat, lon, limit_kmh, kind, facing_deg, check_date FROM camera").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(55.755327, rs.getDouble("lat"), 1e-9)
                    assertEquals(37.617274, rs.getDouble("lon"), 1e-9)
                    assertEquals(60, rs.getInt("limit_kmh"))
                    assertEquals(CameraKind.RED_LIGHT.ordinal, rs.getInt("kind"))
                    assertEquals(180.0, rs.getDouble("facing_deg"), 1e-9)
                    assertEquals("2026-01-15", rs.getString("check_date"))
                }
            }
        }
    }

    @Test
    fun `null optional fields round-trip as null`() {
        val camera =
            Camera(
                id = 0L,
                position = LatLon(55.0, 37.0),
                source = CameraSource.SPEEDCAM,
                externalId = "sc:55.0,37.0",
                limitKmh = null,
                kind = CameraKind.UNKNOWN,
                facingDeg = null,
                dirCount = 1,
                operator = null,
                sourceDetail = "0,60,2",
                checkDate = null,
            )
        db().use { it.replaceCameras(listOf(camera)) }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT limit_kmh, facing_deg, check_date, source_detail FROM camera").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(null, rs.getObject("limit_kmh"))
                    assertEquals(null, rs.getObject("facing_deg"))
                    assertEquals(null, rs.getObject("check_date"))
                    assertEquals("0,60,2", rs.getString("source_detail"))
                }
            }
        }
    }

    @Test
    fun `rewriting a source replaces its rows rather than appending`() {
        db().use { writer ->
            writer.replaceCameras(listOf(sample(1L), sample(2L)))
            writer.replaceCameras(listOf(sample(3L)))
        }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM camera").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rewriting one source leaves another source untouched`() {
        db().use { writer ->
            writer.replaceCameras(listOf(sample(1L, source = CameraSource.OSM)))
            writer.replaceCameras(listOf(sample(2L, source = CameraSource.SPEEDCAM)))
            writer.replaceCameras(listOf(sample(3L, source = CameraSource.OSM)))
        }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT source, COUNT(*) FROM camera GROUP BY source ORDER BY source").use { rs ->
                    val counts = mutableMapOf<Int, Int>()
                    while (rs.next()) counts[rs.getInt(1)] = rs.getInt(2)
                    assertEquals(1, counts[CameraSource.OSM.ordinal])
                    assertEquals(1, counts[CameraSource.SPEEDCAM.ordinal])
                }
            }
        }
    }

    @Test
    fun `writes an average-speed section`() {
        db().use { it.replaceSections(listOf(SectionRecord(LatLon(55.0, 37.0), LatLon(55.1, 37.1), 80))) }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT from_lat, to_lat, limit_kmh, active FROM section").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(55.0, rs.getDouble("from_lat"), 1e-9)
                    assertEquals(55.1, rs.getDouble("to_lat"), 1e-9)
                    assertEquals(80, rs.getInt("limit_kmh"))
                    assertEquals(1, rs.getInt("active"))
                }
            }
        }
    }

    @Test
    fun `the bounding-box query returns rows inside the window and excludes those outside`() {
        db().use { writer ->
            writer.replaceCameras(
                listOf(
                    sample(1L, LatLon(55.0, 37.0)),
                    sample(2L, LatLon(56.0, 38.0)),
                ),
            )
        }

        DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve("test.db")}").use { c ->
            val inside =
                c
                    .prepareStatement(
                        "SELECT COUNT(*) FROM camera WHERE lat BETWEEN 54.5 AND 55.5 AND lon BETWEEN 36.5 AND 37.5",
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
            assertEquals(1, inside, "exactly one camera falls in the window")
        }
    }

    private fun sample(
        id: Long,
        position: LatLon = LatLon(55.0, 37.0),
        source: CameraSource = CameraSource.OSM,
    ) = Camera(
        id = 0L,
        position = position,
        source = source,
        externalId = "$source:$id",
        limitKmh = 60,
        kind = CameraKind.FIXED,
    )

    private fun tableNames(c: Connection): Set<String> =
        c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
}

/**
 * Guards the contract between the importer and Room.
 *
 * The importer writes the database that Room later opens, and Room refuses to open a database
 * whose schema it does not recognise. That failure surfaces as an IllegalStateException at
 * runtime, on a phone, with no connection to the importer change that caused it.
 *
 * This test asserts the importer's DDL against the schema Room itself exported, so the two
 * cannot drift silently.
 */
class SchemaParityTest {
    private val exportedSchema: String by lazy {
        val file = locateExportedSchema()
        file.readText()
    }

    @Test
    fun `the exporter schema file exists`() {
        assertTrue(exportedSchema.isNotBlank(), "Room's exported schema must be readable")
    }

    @Test
    fun `the identity hash matches the exported schema`() {
        val declared = Regex(""""identityHash"\s*:\s*"([a-f0-9]+)"""").find(exportedSchema)!!.groupValues[1]
        assertEquals(
            declared,
            DatabaseWriter.IDENTITY_HASH,
            "Room's schema changed. Regenerate it with :core:data:kspDebugKotlin and copy the new " +
                "identityHash into DatabaseWriter.IDENTITY_HASH.",
        )
    }

    @Test
    fun `every exported table exists in the importer schema`() {
        val exported =
            Regex(""""tableName"\s*:\s*"([^"]+)"""")
                .findAll(exportedSchema)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(exported.isNotEmpty())

        withDb { c ->
            val actual =
                c.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            // room_master_table is Room's own bookkeeping, not part of the exported schema.
            for (table in exported) {
                assertTrue(table in actual, "importer schema is missing exported table '$table'")
            }
        }
    }

    @Test
    fun `every exported column exists with the same name and affinity`() {
        // The exported JSON uses camelCase field paths; SQLite uses the snake_case names
        // Room generated, so the mapping is by position rather than by name.
        val expected = parseExportedColumns()
        assertTrue(expected.isNotEmpty())

        withDb { c ->
            for ((table, columns) in expected) {
                val actual = actualColumns(c, table)
                assertEquals(
                    columns.toSet(),
                    actual,
                    "column mismatch in table '$table'. The importer DDL is a verbatim copy of " +
                        "Room's exported schema; if Room's entities changed, update DatabaseWriter.DDL.",
                )
            }
        }
    }

    private fun withDb(block: (Connection) -> Unit) {
        val dir = Files.createTempDirectory("schema-parity")
        val path = dir.resolve("parity.db").toString()
        DatabaseWriter.create(path).use {
            it.open()
            it.createSchema()
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use(block)
        dir.toFile().deleteRecursively()
    }

    private fun actualColumns(
        c: Connection,
        table: String,
    ): Set<Pair<String, String>> =
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString("name").lowercase() to rs.getString("type").lowercase())
                }
            }
        }

    /** Reads Room's exported schema JSON into table -> list of (column, affinity). */
    private fun parseExportedColumns(): Map<String, List<Pair<String, String>>> {
        val root =
            com.google.gson.JsonParser
                .parseString(exportedSchema)
                .asJsonObject
        val database = root.getAsJsonObject("database")
        val out = mutableMapOf<String, List<Pair<String, String>>>()

        database.getAsJsonArray("entities").forEach { element ->
            val entity = element.asJsonObject
            val tableName = entity.get("tableName").asString.lowercase()

            val columns =
                entity
                    .getAsJsonArray("fields")
                    .map { it.asJsonObject }
                    .map { field ->
                        val path = field.get("fieldPath").asString
                        // columnName is a plain string in the schema, not a nested object.
                        val column = field.get("columnName")?.asString ?: camelToSnake(path)
                        val affinity = field.get("affinity").asString.lowercase()
                        column.lowercase() to affinity
                    }

            out[tableName] = columns
        }
        return out
    }

    private fun camelToSnake(name: String): String =
        name
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()

    private fun locateExportedSchema(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        val relative = "core/data/schemas/com.speedcam.core.data.SpeedCamDatabase/1.json"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate $relative walking up from ${System.getProperty("user.dir")}. " +
                "Run `./gradlew :core:data:kspDebugKotlin` to export it.",
        )
    }
}

private typealias Files = java.nio.file.Files
