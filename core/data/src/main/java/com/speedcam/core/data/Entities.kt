package com.speedcam.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence model for a camera record.
 *
 * Deliberately separate from `com.speedcam.core.detect.Camera`: the Room entity carries
 * nullable columns and raw enums-as-ints, and the mapper in [CameraMappers] converts it into
 * the pure domain type the detection engine consumes.
 *
 * Schema notes (Plan.md §4):
 *  - `source` + `externalId` are required for idempotent re-import and for reconciling
 *    user reports against what the upstream source previously claimed.
 *  - `checkDate` records freshness; absent means unknown, not current.
 */
@Entity(
    tableName = "camera",
    indices = [Index(value = ["lat", "lon"])],
)
data class CameraEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** [com.speedcam.core.detect.CameraSource] ordinal. */
    @ColumnInfo(name = "source")
    val source: Int,
    /** OSM node/way id, or the source-native key. Null for user-added rows. */
    @ColumnInfo(name = "external_id")
    val externalId: String?,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    /** Enforced limit in km/h. Null means the source recorded none; it does not mean "no limit". */
    @ColumnInfo(name = "limit_kmh")
    val limitKmh: Int?,
    /** [com.speedcam.core.detect.CameraKind] ordinal. */
    @ColumnInfo(name = "kind")
    val kind: Int,
    /** Direction the device faces, degrees in [0, 360). Null when the source recorded none. */
    @ColumnInfo(name = "facing_deg")
    val facingDeg: Double?,
    @ColumnInfo(name = "dir_count")
    val dirCount: Int = 1,
    @ColumnInfo(name = "operator")
    val operator: String?,
    /**
     * The source record exactly as published, for rows whose format is not fully documented.
     *
     * The SpeedCam param line is a comma-separated record whose field meanings vary between
     * providers. Keeping it verbatim means a parser fix later does not require re-downloading
     * and re-parsing the whole corpus, which is a multi-megabyte fetch.
     */
    @ColumnInfo(name = "source_detail")
    val sourceDetail: String?,
    /** ISO-8601 date. Null means freshness is unknown. */
    @ColumnInfo(name = "check_date")
    val checkDate: String?,
)

/**
 * An average-speed corridor (SPECS), derived from `type=enforcement` relations with
 * `enforcement=average_speed`.
 *
 * The vehicle's average speed is computed over the straight-line distance between the two
 * endpoints. Note this is a simplification: the original computes it along the driven route.
 */
@Entity(
    tableName = "section",
    indices = [Index(value = ["from_lat", "from_lon"])],
)
data class SectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "from_lat")
    val fromLat: Double,
    @ColumnInfo(name = "from_lon")
    val fromLon: Double,
    @ColumnInfo(name = "to_lat")
    val toLat: Double,
    @ColumnInfo(name = "to_lon")
    val toLon: Double,
    @ColumnInfo(name = "limit_kmh")
    val limitKmh: Int,
    @ColumnInfo(name = "active")
    val active: Int = 1,
)

/**
 * User-supplied records: blocked locations and manual additions.
 *
 * `action` distinguishes the two. Kept in the same database as the imported data so that
 * overrides survive a re-import of the upstream sources.
 */
@Entity(tableName = "override_")
data class OverrideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    /** 0 = block, 1 = add. */
    @ColumnInfo(name = "action")
    val action: Int,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "created")
    val created: String,
)

/** Types of crowdsourced hazard reports a driver can submit. */
enum class HazardReportType {
    /** A camera exists on the road but is missing in the database. */
    NEW_CAMERA,

    /** A camera listed in the database does not exist on the ground. */
    REMOVED_CAMERA,

    /** The speed limit recorded in the database is incorrect. */
    WRONG_SPEED_LIMIT,

    /** False alarm / warning in the wrong direction or inapplicable. */
    FALSE_POSITIVE,
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): HazardReportType = entries.getOrElse(ordinal) { NEW_CAMERA }
    }
}

/** Synchronization state of a locally filed hazard report. */
enum class ReportSyncStatus {
    /** Report is stored in local outbox, waiting to be uploaded. */
    PENDING,

    /** Successfully delivered to remote backend. */
    SYNCED,

    /** Upload attempt failed; will be retried by worker with backoff. */
    FAILED,
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): ReportSyncStatus = entries.getOrElse(ordinal) { PENDING }
    }
}

/**
 * An offline outbox record for user-submitted camera corrections (AREAS_FOR_DEVELOPMENT.md [M-30]).
 *
 * Stored locally so that submissions in rural or dead-zone areas are never lost.
 */
@Entity(
    tableName = "hazard_report",
    indices = [
        Index(value = ["status", "created_at"]),
        Index(value = ["lat", "lon"]),
    ],
)
data class HazardReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "lat")
    val lat: Double,
    @ColumnInfo(name = "lon")
    val lon: Double,
    @ColumnInfo(name = "heading_deg")
    val headingDeg: Double? = null,
    /** Id of the existing camera being corrected, or null if reporting a new camera. */
    @ColumnInfo(name = "camera_id")
    val cameraId: Long? = null,
    /** [HazardReportType] ordinal. */
    @ColumnInfo(name = "report_type")
    val reportType: Int,
    /** Proposed or actual speed limit in km/h. */
    @ColumnInfo(name = "speed_limit_kmh")
    val speedLimitKmh: Int? = null,
    @ColumnInfo(name = "comment")
    val comment: String? = null,
    /** [ReportSyncStatus] ordinal. */
    @ColumnInfo(name = "status")
    val status: Int = ReportSyncStatus.PENDING.ordinal,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "remote_id")
    val remoteId: String? = null,
)
