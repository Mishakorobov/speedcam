package com.speedcam.core.data

import android.content.Context
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.DetectionEngine
import com.speedcam.core.detect.SectionQuery
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.geo.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The assembly point for the camera data layer.
 *
 * Everything the app needs from persistence already existed — [SpeedCamDatabase], the DAOs,
 * [CameraQueryAdapter] and the pure [DetectionEngine] — but nothing constructed the chain, so
 * the detector had no way to reach real data. This class is that composition root.
 *
 * Written as manual constructor injection rather than a DI framework. The graph is five
 * objects deep and completely static; a framework would add an annotation processor and a
 * compile-time dependency to express something a constructor already says.
 *
 * Deliberately *not* a singleton. The database is an open file handle and its lifetime belongs
 * to whoever opens it, so the caller decides — an application-scoped instance for the running
 * app, a fresh one per test. A static holder over an application context would leak the handle
 * for the life of the process and make the tests order-dependent.
 */
class CameraStore(
    private val database: SpeedCamDatabase,
) : AutoCloseable {
    val cameraDao: CameraDao get() = database.cameraDao()

    val sectionDao: SectionDao get() = database.sectionDao()

    val overrideDao: OverrideDao get() = database.overrideDao()

    val metaDao: MetaDao get() = database.metaDao()

    val hazardReportDao: HazardReportDao get() = database.hazardReportDao()

    /** Submits a new crowdsourced hazard report to the local outbox. */
    suspend fun submitReport(report: HazardReportEntity): Long {
        val outcome = runCatching { hazardReportDao.insert(report) }
        return outcome.getOrDefault(-1L)
    }

    /** Number of hazard reports currently waiting in the offline outbox. */
    suspend fun pendingReportCount(): Int = runCatching { hazardReportDao.pendingCount() }.getOrDefault(0)

    /** Observes the pending outbox count as a stream. */
    fun observePendingReportCount(): Flow<Int> = hazardReportDao.observePendingCount()

    /** Observes all reports stored in the local outbox. */
    fun observeReports(): Flow<List<HazardReportEntity>> = hazardReportDao.observeAll()

    /** Whether to query cameras from the proprietary ContraCam database instead of OSM/SpeedCam. */
    @Volatile
    var useContraCamDb: Boolean = false

    /**
     * The adapter is created once and reused: Room caches the generated DAO instance, so this
     * is a stable view over the same underlying query rather than a per-call allocation.
     */
    private val query: CameraQueryAdapter =
        CameraQueryAdapter(database.cameraDao()) { useContraCamDb }

    /**
     * The query with the user's overrides applied.
     *
     * This, not [cameraQuery], is what detection must read through: a blocked camera that
     * still produces hazards means the driver's decision to silence it is quietly ignored.
     */
    private val blockingQuery = BlockingCameraQuery(query)

    /** How many cameras are loaded, for the settings screen. */
    suspend fun cameraCount(): Int =
        withContext(Dispatchers.IO) {
            val hasContra = runCatching { database.cameraDao().hasContraCamCameras() }.getOrDefault(false)
            val active = if (useContraCamDb && hasContra) 1 else 0
            runCatching { database.cameraDao().countActive(active) }.getOrDefault(0)
        }

    /**
     * Every camera, for the browser screen.
     *
     * A snapshot rather than an observable, because the database is rebuilt out of band by
     * tools/import and the screen re-reads on resume; a live query would repaint the list
     * for changes the driver did not make.
     *
     * Suspend because Room refuses main-thread access and the UI calls this on resume.
     * Keyset pagination is used to prevent Android CursorWindow (2 MB) allocation limits with 50k+ cameras.
     */
    suspend fun allCameras(): List<Camera> =
        withContext(Dispatchers.IO) {
            val hasContra = runCatching { database.cameraDao().hasContraCamCameras() }.getOrDefault(false)
            val active = if (useContraCamDb && hasContra) 1 else 0
            val results = ArrayList<CameraEntity>()
            var lastId = 0L
            val chunkSize = 2000
            val maxLimit = 100_000
            while (results.size < maxLimit) {
                val chunk =
                    runCatching {
                        database.cameraDao().camerasActivePaged(active, lastId, chunkSize)
                    }.getOrDefault(emptyList())
                if (chunk.isEmpty()) break
                results.addAll(chunk)
                lastId = chunk.last().id
                if (chunk.size < chunkSize) break
            }
            results.map(CameraMappers::toDomain)
        }

    /** Returns cameras inside [box] matching the active database source (ContraCam vs OSM). */
    suspend fun camerasInBox(
        box: BoundingBox,
        limit: Int = 2000,
    ): List<Camera> =
        withContext(Dispatchers.IO) {
            val hasContra = runCatching { database.cameraDao().hasContraCamCameras() }.getOrDefault(false)
            val active = useContraCamDb && hasContra
            runCatching {
                database.cameraDao().inBoxActiveSource(box, active, limit).map(CameraMappers::toDomain)
            }.getOrDefault(emptyList())
        }

    /** The override set currently in force. */
    @Volatile
    var overrides: OverrideSet = OverrideSet()
        private set

    /** Active average-speed corridors currently loaded in memory. */
    @Volatile
    var sections: List<SectionRecord> = emptyList()
        private set

    /** Re-reads `override_` and applies it to later queries. */
    suspend fun refreshOverrides() {
        val rows = runCatching { database.overrideDao().all() }.getOrElse { emptyList() }
        overrides = OverrideSet.from(rows)
        blockingQuery.update(overrides)
    }

    /** Re-reads active average-speed corridors from `section`. */
    suspend fun refreshSections() {
        val rows = runCatching { database.sectionDao().active() }.getOrElse { emptyList() }
        sections = SectionMappers.toDomainList(rows)
    }

    /** Returns the timestamp when the active database was built/imported, or null if unknown. */
    suspend fun databaseBuiltAt(): String? = runCatching { metaDao.get("built_at") }.getOrNull()

    /** Returns how many SPECS corridors are loaded in the database. */
    suspend fun sectionCount(): Int = runCatching { database.sectionDao().count() }.getOrDefault(0)

    /** Records a block at [position] and applies it immediately. */
    suspend fun block(
        position: com.speedcam.core.geo.LatLon,
        note: String? = null,
        created: String = "",
    ) {
        database
            .overrideDao()
            .upsert(
                OverrideEntity(
                    lat = position.lat,
                    lon = position.lon,
                    action = OverrideAction.BLOCK.ordinal,
                    note = note,
                    created = created,
                ),
            )
        refreshOverrides()
    }

    /** Adds a user-reported temporary hazard or mobile camera. */
    suspend fun addCamera(
        lat: Double,
        lon: Double,
        kind: com.speedcam.core.detect.CameraKind = com.speedcam.core.detect.CameraKind.MOBILE,
        limitKmh: Int? = null,
        note: String? = null,
        created: String? = null,
    ) {
        val entity =
            CameraEntity(
                source = com.speedcam.core.detect.CameraSource.MANUAL.ordinal,
                externalId = "manual-${System.currentTimeMillis()}-${(100..999).random()}",
                lat = lat,
                lon = lon,
                limitKmh = limitKmh,
                kind = kind.ordinal,
                facingDeg = null,
                dirCount = 2,
                operator = null,
                sourceDetail = note,
                checkDate = created,
            )
        database.cameraDao().upsertAll(listOf(entity))
    }

    /**
     * Removes overrides covering [position], unblocking whatever it silenced.
     *
     * Deletes by proximity rather than by exact coordinates: a block is matched with a
     * radius, so unblocking has to use the same radius or the row that took effect would not
     * be the one removed.
     */
    suspend fun unblock(position: com.speedcam.core.geo.LatLon) {
        val box =
            com.speedcam.core.geo.Geo
                .boundingBox(position, OverrideSet.MATCH_RADIUS_M)
        database
            .overrideDao()
            .inBox(box.minLat, box.minLon, box.maxLat, box.maxLon)
            .filter {
                com.speedcam.core.geo.Geo
                    .haversineMeters(
                        com.speedcam.core.geo
                            .LatLon(it.lat, it.lon),
                        position,
                    ) <=
                    OverrideSet.MATCH_RADIUS_M
            }.forEach { database.overrideDao().delete(it.id) }
        refreshOverrides()
    }

    /**
     * A detector wired to the live database.
     *
     * Returned fresh per call because [DetectionEngine] is stateless: it holds only the query
     * it reads through, so sharing one instance would imply shared mutable state that does not
     * exist. Callers that detect on every location update may cache the result.
     */
    fun detector(): DetectionEngine = DetectionEngine(query)

    /**
     * The underlying query, for callers that run their own detection pipeline.
     *
     * [com.speedcam.service.DetectionPipeline] arbitrates fixes before detecting, so it needs
     * the query rather than a detector that would re-arbitrate nothing and lose access to the
     * accepted fix.
     */
    fun cameraQuery(): CameraQuery = blockingQuery

    /**
     * Supplies active average-speed corridors to [com.speedcam.core.detect.AverageSpeedTracker].
     *
     * Backed by the in-memory [sections] snapshot refreshed at startup so the detection loop
     * never blocks on a SQLite read per fix.
     */
    fun sectionQuery(): SectionQuery = SectionQuery { sections }

    override fun close() {
        database.close()
    }

    companion object {
        /** Opens the database in the app's private storage. The caller owns the result. */
        fun open(context: Context): CameraStore = CameraStore(SpeedCamDatabase.build(context))

        /** Builds a store over an already-open database, for tests and alternate storage. */
        fun of(database: SpeedCamDatabase): CameraStore = CameraStore(database)
    }
}
