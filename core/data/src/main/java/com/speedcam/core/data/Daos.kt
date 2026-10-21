package com.speedcam.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.BoundingBox
import com.speedcam.core.geo.LatLon
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    /**
     * Bounding-box candidate lookup.
     *
     * A compound index on (lat, lon) makes the leading `lat BETWEEN` clause selective, which
     * reduces each query to on the order of 100 rows at the ~80k scale this database operates
     * at. An R-tree is deliberately not used at this size — see Plan.md §5.5.
     *
     * The four coordinates are passed as separate parameters rather than as a [BoundingBox]:
     * Room binds query arguments positionally by name and does not expand a POJO parameter
     * into individual bind variables.
     */
    @Query(
        """
        SELECT * FROM camera
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """,
    )
    fun queryBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<CameraEntity>

    /** Unannotated bridge that unpacks the domain bounding box into the SQL bind arguments. */
    fun inBox(box: BoundingBox): List<CameraEntity> = queryBox(box.minLat, box.minLon, box.maxLat, box.maxLon)

    @Query("SELECT * FROM camera WHERE id = :id")
    suspend fun byId(id: Long): CameraEntity?

    @Query("SELECT COUNT(*) FROM camera")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM camera WHERE source = :source")
    suspend fun countBySource(source: Int): Int

    @Query("SELECT * FROM camera ORDER BY lat DESC, lon DESC LIMIT :limit")
    fun observeMostRecent(limit: Int): Flow<List<CameraEntity>>

    @Query("SELECT * FROM camera WHERE kind = :kind ORDER BY lat, lon")
    fun observeByKind(kind: Int): Flow<List<CameraEntity>>

    /**
     * Every camera, for the browser screen.
     *
     * Suspend because Room refuses main-thread access and the UI calls this on resume. A
     * blocking variant would either throw or push the problem onto the caller, which is
     * exactly the mistake this exists to prevent.
     */
    @Query("SELECT * FROM camera ORDER BY id")
    suspend fun allCameras(): List<CameraEntity>

    @Query("SELECT * FROM camera WHERE limit_kmh >= :minLimit ORDER BY limit_kmh ASC")
    fun observeAtOrAboveLimit(minLimit: Int): Flow<List<CameraEntity>>

    /**
     * Idempotent upsert keyed on (source, external_id).
     *
     * `tools:import` reruns against unchanged upstream data; without this the database would
     * accumulate duplicates on every scheduled refresh.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cameras: List<CameraEntity>)

    @Query("DELETE FROM camera WHERE source = :source")
    suspend fun deleteBySource(source: Int)

    @Transaction
    suspend fun replaceSource(
        source: Int,
        cameras: List<CameraEntity>,
    ) {
        deleteBySource(source)
        upsertAll(cameras)
    }
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM section WHERE active = 1")
    suspend fun active(): List<SectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sections: List<SectionEntity>)

    @Query("DELETE FROM section")
    suspend fun clear()
}

@Dao
interface OverrideDao {
    @Query("SELECT * FROM override_")
    fun observeAll(): Flow<List<OverrideEntity>>

    /** One-shot read, for applying the block list to detection without collecting a flow. */
    @Query("SELECT * FROM override_")
    suspend fun all(): List<OverrideEntity>

    /**
     * Overrides within [radiusM] of a point.
     *
     * A bounding box rather than a distance function, because SQLite has no trig and the
     * metres-per-degree conversion depends on latitude. The caller widens the box by the
     * margin below before matching exactly.
     */
    @Query(
        """
        SELECT * FROM override_
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """,
    )
    suspend fun inBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<OverrideEntity>

    /** An override exactly at a position, which is how a block is removed by tapping again. */
    @Query("SELECT * FROM override_ WHERE lat = :lat AND lon = :lon")
    suspend fun at(
        lat: Double,
        lon: Double,
    ): List<OverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: OverrideEntity)

    @Query("DELETE FROM override_ WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Database metadata: which upstream snapshot is currently loaded. */
@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM meta WHERE key = :key")
    suspend fun row(key: String): MetaRow?

    @Query("DELETE FROM meta WHERE key = :key")
    suspend fun delete(key: String)

    /**
     * Insert takes an [MetaRow] rather than two scalars: Room binds `@Insert` arguments to a
     * single entity, so a key/value pair cannot be spread over two method parameters.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: MetaRow)
}

/**
 * Adapts [CameraDao] to the domain's [CameraQuery] contract.
 *
 * The DAO cannot implement [CameraQuery] directly: the contract yields domain [Camera]
 * values, while Room can only yield [CameraEntity] rows. The direction of the dependency
 * is what matters — the pure domain declares what it needs, and this adapter supplies it
 * from persistence without `core:detect` ever depending on Room.
 */
class CameraQueryAdapter(
    private val dao: CameraDao,
) : CameraQuery {
    override fun inBox(box: BoundingBox): List<Camera> = CameraMappers.toDomainList(dao.inBox(box))
}

/**
 * Maps persistence rows into the pure domain types the detection engine consumes.
 *
 * Enum mapping goes through `enumValues` rather than `valueOf(ordinal)` so that a corrupted or
 * forward-versioned ordinal degrades to a default instead of throwing.
 */
object CameraMappers {
    fun toDomain(e: CameraEntity): Camera =
        Camera(
            id = e.id,
            position = LatLon(e.lat, e.lon),
            source = enumOrDefault(CameraSource.entries, e.source, CameraSource.OSM),
            externalId = e.externalId,
            limitKmh = e.limitKmh,
            kind = enumOrDefault(CameraKind.entries, e.kind, CameraKind.UNKNOWN),
            facingDeg = e.facingDeg,
            dirCount = e.dirCount,
            operator = e.operator,
            sourceDetail = e.sourceDetail,
            checkDate = e.checkDate,
        )

    fun toDomainList(entities: List<CameraEntity>): List<Camera> = entities.map(::toDomain)

    fun toEntity(c: Camera): CameraEntity =
        CameraEntity(
            id = c.id,
            source = c.source.ordinal,
            externalId = c.externalId,
            lat = c.position.lat,
            lon = c.position.lon,
            limitKmh = c.limitKmh,
            kind = c.kind.ordinal,
            facingDeg = c.facingDeg,
            dirCount = c.dirCount,
            operator = c.operator,
            sourceDetail = c.sourceDetail,
            checkDate = c.checkDate,
        )

    private inline fun <T> enumOrDefault(
        values: List<T>,
        ordinal: Int,
        fallback: T,
    ): T = values.getOrElse(ordinal) { fallback }
}

/** Maps [SectionEntity] rows into the pure domain [SectionRecord] consumed by [com.speedcam.core.detect.AverageSpeedTracker]. */
object SectionMappers {
    fun toDomainOrNull(e: SectionEntity): SectionRecord? {
        if (e.limitKmh <= 0) return null
        return SectionRecord(
            from = LatLon(e.fromLat, e.fromLon),
            to = LatLon(e.toLat, e.toLon),
            limitKmh = e.limitKmh,
            active = e.active != 0,
        )
    }

    fun toDomainList(entities: List<SectionEntity>): List<SectionRecord> = entities.mapNotNull(::toDomainOrNull)

    fun toEntity(s: SectionRecord): SectionEntity =
        SectionEntity(
            fromLat = s.from.lat,
            fromLon = s.from.lon,
            toLat = s.to.lat,
            toLon = s.to.lon,
            limitKmh = s.limitKmh,
            active = if (s.active) 1 else 0,
        )
}
