package com.speedcam.tools.import.db

import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * Writes the camera database that `core:data` opens at runtime.
 *
 * The DDL below is copied verbatim from Room's own exported schema
 * (`core/data/schemas/com.speedcam.core.data.SpeedCamDatabase/1.json`). Room validates the
 * schema of any database it opens by comparing an identity hash, so the importer must
 * reproduce that schema exactly and seed `room_master_table` with the matching hash. A
 * mismatch surfaces at runtime as IllegalStateException on first query, which is a miserable
 * way to discover an importer bug.
 *
 * `SchemaParityTest` asserts this DDL against the exported schema, so the two cannot drift.
 */
class DatabaseWriter private constructor(
    private val connection: Connection,
) : AutoCloseable {
    /**
     * Opens the database at [path].
     *
     * Any existing file is deleted rather than appended to, because the importer is the sole
     * author of this file. A full rebuild from the sources takes seconds and removes a whole
     * class of stale-state bugs; incremental refresh is not a goal here.
     */
    fun open(): DatabaseWriter {
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        connection.createStatement().use { it.execute("PRAGMA synchronous=OFF") }
        return this
    }

    fun createSchema() {
        connection.createStatement().use { st ->
            DDL.forEach { st.execute(it.trimIndent()) }
        }
        seedRoomMasterTable()
    }

    private fun seedRoomMasterTable() {
        connection.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            st.execute("DELETE FROM room_master_table")
            // 42 is Room's SCHEMA_OBJECT_ID; Room looks the hash up by this key.
            st.execute("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$IDENTITY_HASH')")
        }
    }

    /**
     * Writes [cameras], replacing all existing rows for the sources they belong to.
     *
     * The source is taken from the records themselves rather than passed in separately. An
     * earlier signature accepted both a `source` argument and a list of records, which allowed
     * the two to disagree: the DELETE targeted one source while the INSERT stamped another,
     * silently corrupting the partition with no error anywhere.
     */
    fun replaceCameras(cameras: Collection<Camera>): Int {
        if (cameras.isEmpty()) return 0

        val bySource = cameras.groupBy { it.source }
        inTransaction {
            deleteSources(bySource.keys)
            connection.prepareStatement(INSERT_CAMERA).use { ps ->
                for (records in bySource.values) {
                    records.forEach {
                        bindCamera(ps, it)
                        ps.addBatch()
                    }
                }
                ps.executeBatch()
            }
        }
        return cameras.size
    }

    /** Runs [body] atomically, rolling back on failure. */
    private inline fun inTransaction(body: () -> Unit) {
        connection.autoCommit = false
        try {
            body()
            connection.commit()
        } catch (e: SQLException) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun deleteSources(sources: Set<CameraSource>) {
        connection.createStatement().use { st ->
            sources.forEach { st.execute("DELETE FROM camera WHERE source = ${it.ordinal}") }
        }
    }

    /** Writes average-speed corridors, replacing any previous import. */
    fun replaceSections(sections: Collection<SectionRecord>): Int {
        if (sections.isEmpty()) return 0

        inTransaction {
            connection.createStatement().use { it.execute("DELETE FROM section") }
            connection.prepareStatement(INSERT_SECTION).use { ps ->
                sections.forEach { s ->
                    ps.setDouble(1, s.from.lat)
                    ps.setDouble(2, s.from.lon)
                    ps.setDouble(3, s.to.lat)
                    ps.setDouble(4, s.to.lon)
                    ps.setInt(5, s.limitKmh)
                    ps.setInt(6, if (s.active) 1 else 0)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return sections.size
    }

    /** Records a key/value pair in the metadata table (source snapshots, build stamps). */
    fun putMeta(
        key: String,
        value: String,
    ) {
        connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)").use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    /** Applies pragmas that materially shrink the file, then folds the WAL back in. */
    fun optimise() {
        connection.createStatement().use { st ->
            st.execute("ANALYZE")
            st.execute("PRAGMA optimize")
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            st.execute("VACUUM")
        }
    }

    private fun bindCamera(
        ps: PreparedStatement,
        c: Camera,
    ) {
        // Assigned to locals first: `limitKmh` and `facingDeg` are properties on a type from
        // another module, so Kotlin will not smart-cast them for a null check.
        val limit = c.limitKmh
        val facing = c.facingDeg

        ps.setInt(1, c.source.ordinal)
        ps.setString(2, c.externalId)
        ps.setDouble(3, c.position.lat)
        ps.setDouble(4, c.position.lon)
        if (limit == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, limit)
        ps.setInt(6, c.kind.ordinal)
        if (facing == null) ps.setNull(7, java.sql.Types.REAL) else ps.setDouble(7, facing)
        ps.setInt(8, c.dirCount)
        ps.setString(9, c.operator)
        ps.setString(10, c.sourceDetail)
        ps.setString(11, c.checkDate)
    }

    override fun close() = connection.close()

    companion object {
        /**
         * Identity hash of the schema in `core:data`, version 1.
         *
         * Regenerate with `./gradlew :core:data:kspDebugKotlin` and copy the value from the
         * exported schema JSON whenever the entities change.
         */
        const val IDENTITY_HASH = "0585e653089d42d2ca2c716b9f1541ac"

        fun create(path: String): DatabaseWriter {
            // Delete any previous database so each run produces the file from scratch.
            // DriverManager would otherwise open the existing file and `CREATE TABLE IF NOT
            // EXISTS` would silently preserve whatever schema was there before.
            listOf(path, "$path-wal", "$path-shm").forEach { java.io.File(it).delete() }
            return DatabaseWriter(DriverManager.getConnection("jdbc:sqlite:$path"))
        }

        /** Verbatim from Room's exported schema. See the class comment. */
        private val DDL =
            listOf(
                """
            CREATE TABLE IF NOT EXISTS `camera` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `source` INTEGER NOT NULL,
              `external_id` TEXT,
              `lat` REAL NOT NULL,
              `lon` REAL NOT NULL,
              `limit_kmh` INTEGER,
              `kind` INTEGER NOT NULL,
              `facing_deg` REAL,
              `dir_count` INTEGER NOT NULL,
              `operator` TEXT,
              `source_detail` TEXT,
              `check_date` TEXT
            )
            """,
                "CREATE INDEX IF NOT EXISTS `index_camera_lat_lon` ON `camera` (`lat`, `lon`)",
                """
            CREATE TABLE IF NOT EXISTS `section` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `from_lat` REAL NOT NULL,
              `from_lon` REAL NOT NULL,
              `to_lat` REAL NOT NULL,
              `to_lon` REAL NOT NULL,
              `limit_kmh` INTEGER NOT NULL,
              `active` INTEGER NOT NULL
            )
            """,
                "CREATE INDEX IF NOT EXISTS `index_section_from_lat_from_lon` ON `section` (`from_lat`, `from_lon`)",
                """
            CREATE TABLE IF NOT EXISTS `override_` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `lat` REAL NOT NULL,
              `lon` REAL NOT NULL,
              `action` INTEGER NOT NULL,
              `note` TEXT,
              `created` TEXT NOT NULL
            )
            """,
                """
            CREATE TABLE IF NOT EXISTS `meta` (
              `key` TEXT NOT NULL,
              `value` TEXT NOT NULL,
              PRIMARY KEY(`key`)
            )
            """,
            )

        private val INSERT_CAMERA =
            """
            INSERT INTO camera
              (source, external_id, lat, lon, limit_kmh, kind, facing_deg, dir_count, operator, source_detail, check_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        private val INSERT_SECTION =
            """
            INSERT INTO section (from_lat, from_lon, to_lat, to_lon, limit_kmh, active)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
    }
}
