package com.speedcam.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CameraEntity::class,
        SectionEntity::class,
        OverrideEntity::class,
        MetaRow::class,
        HazardReportEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(MetaConverters::class)
abstract class SpeedCamDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    abstract fun sectionDao(): SectionDao

    abstract fun overrideDao(): OverrideDao

    abstract fun metaDao(): MetaDao

    abstract fun hazardReportDao(): HazardReportDao

    companion object {
        const val NAME = "speedcam.db"

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_hazard_report_status_created_at` " +
                            "ON `hazard_report` (`status`, `created_at`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_hazard_report_lat_lon` " +
                            "ON `hazard_report` (`lat`, `lon`)",
                    )
                }
            }

        /**
         * Opens the database, installing the bundled copy first if there is not one yet.
         *
         * Installation happens here rather than at the call site so that no code path can
         * reach Room with nothing in the file: an empty camera database does not fail, it
         * simply never warns, which is the most dangerous way this app can be broken.
         *
         * @throws java.io.IOException if a database is bundled but cannot be installed.
         */
        fun build(context: Context): SpeedCamDatabase {
            val app = context.applicationContext
            CameraDatabaseInstaller.forContext(app).installIfNeeded()

            return Room
                .databaseBuilder(app, SpeedCamDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}

/** Single-row key/value store for import provenance and freshness. */
@androidx.room.Entity(tableName = "meta")
data class MetaRow(
    @androidx.room.PrimaryKey
    @androidx.room.ColumnInfo(name = "key")
    val key: String,
    @androidx.room.ColumnInfo(name = "value")
    val value: String,
)

class MetaConverters {
    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value != 0
}
