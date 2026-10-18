package com.speedcam.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        CameraEntity::class,
        SectionEntity::class,
        OverrideEntity::class,
        MetaRow::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(MetaConverters::class)
abstract class SpeedCamDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao

    abstract fun sectionDao(): SectionDao

    abstract fun overrideDao(): OverrideDao

    abstract fun metaDao(): MetaDao

    companion object {
        const val NAME = "speedcam.db"

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
                // No fallbackToDestructiveMigration: a camera database lost to a bad migration
                // is a silent safety regression. Migrations must be written explicitly.
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
