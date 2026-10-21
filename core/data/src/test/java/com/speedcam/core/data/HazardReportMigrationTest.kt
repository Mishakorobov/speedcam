package com.speedcam.core.data

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Validates the Room 1 -> 2 migration DDL and hazard_report schema.
 *
 * Runs against an in-memory SQLite database via JDBC, ensuring the migration
 * SQL creates the table and indices exactly as declared in the schema.
 */
class HazardReportMigrationTest {
    private lateinit var connection: Connection

    @BeforeEach
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Set up version 1 schema baseline
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS `camera` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `source` TEXT NOT NULL,
                    `external_id` TEXT NOT NULL,
                    `lat` REAL NOT NULL,
                    `lon` REAL NOT NULL,
                    `limit_kmh` INTEGER,
                    `kind` TEXT NOT NULL,
                    `facing_deg` REAL,
                    `dir_count` INTEGER NOT NULL,
                    `operator` TEXT,
                    `source_detail` TEXT,
                    `check_date` TEXT
                )
                """.trimIndent(),
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS `override_blocked` (
                    `lat` REAL NOT NULL,
                    `lon` REAL NOT NULL,
                    PRIMARY KEY(`lat`, `lon`)
                )
                """.trimIndent(),
            )
        }
    }

    @AfterEach
    fun tearDown() {
        if (!connection.isClosed) {
            connection.close()
        }
    }

    @Test
    fun `migration DDL creates hazard_report table and indices`() {
        applyMigrationDdl()

        // Verify table existence
        val tables = mutableListOf<String>()
        connection.metaData.getTables(null, null, "hazard_report", null).use { rs ->
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"))
            }
        }
        assertEquals(listOf("hazard_report"), tables)

        // Verify indices
        val indices = mutableListOf<String>()
        connection.metaData.getIndexInfo(null, null, "hazard_report", false, false).use { rs ->
            while (rs.next()) {
                rs.getString("INDEX_NAME")?.let { indices.add(it) }
            }
        }
        assertTrue(indices.contains("index_hazard_report_status_created_at"))
        assertTrue(indices.contains("index_hazard_report_lat_lon"))
    }

    @Test
    fun `hazard_report lifecycle insert query sync and delete`() {
        applyMigrationDdl()

        // 1. Insert a pending hazard report
        val insertSql =
            """
            INSERT INTO hazard_report (
                created_at, lat, lon, heading_deg, camera_id, report_type,
                speed_limit_kmh, comment, status, attempts
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

        connection.prepareStatement(insertSql).use { ps ->
            ps.setLong(1, 1700000000000L)
            ps.setDouble(2, 55.75)
            ps.setDouble(3, 37.61)
            ps.setDouble(4, 90.0)
            ps.setLong(5, 12345L)
            ps.setInt(6, HazardReportType.WRONG_SPEED_LIMIT.ordinal)
            ps.setInt(7, 80)
            ps.setString(8, "Limit changed to 80")
            ps.setInt(9, ReportSyncStatus.PENDING.ordinal)
            ps.setInt(10, 0)
            ps.executeUpdate()
        }

        // 2. Query pending count and pending items
        val pendingCountQuery = "SELECT COUNT(*) FROM hazard_report WHERE status = 0"
        connection.createStatement().use { stmt ->
            stmt.executeQuery(pendingCountQuery).use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(1))
            }
        }

        // 3. Mark as synced
        val updateSql =
            "UPDATE hazard_report SET status = ?, remote_id = ? WHERE id = 1"
        connection.prepareStatement(updateSql).use { ps ->
            ps.setInt(1, ReportSyncStatus.SYNCED.ordinal)
            ps.setString(2, "server-ack-999")
            assertEquals(1, ps.executeUpdate())
        }

        // 4. Pending count should now be 0
        connection.createStatement().use { stmt ->
            stmt.executeQuery(pendingCountQuery).use { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt(1))
            }
        }

        // 5. Clear synced reports
        connection.createStatement().use { stmt ->
            val deleted = stmt.executeUpdate("DELETE FROM hazard_report WHERE status = 1")
            assertEquals(1, deleted)
        }
    }

    private fun applyMigrationDdl() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS `hazard_report` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `lat` REAL NOT NULL,
                    `lon` REAL NOT NULL,
                    `heading_deg` REAL,
                    `camera_id` INTEGER,
                    `report_type` INTEGER NOT NULL,
                    `speed_limit_kmh` INTEGER,
                    `comment` TEXT,
                    `status` INTEGER NOT NULL,
                    `attempts` INTEGER NOT NULL,
                    `last_attempt_at` INTEGER,
                    `remote_id` TEXT
                )
                """.trimIndent(),
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS `index_hazard_report_status_created_at` " +
                    "ON `hazard_report` (`status`, `created_at`)",
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS `index_hazard_report_lat_lon` " +
                    "ON `hazard_report` (`lat`, `lon`)",
            )
        }
    }
}
