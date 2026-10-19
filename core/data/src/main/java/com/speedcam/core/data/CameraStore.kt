package com.speedcam.core.data

import android.content.Context
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraQuery
import com.speedcam.core.detect.DetectionEngine

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

    /**
     * The adapter is created once and reused: Room caches the generated DAO instance, so this
     * is a stable view over the same underlying query rather than a per-call allocation.
     */
    private val query: CameraQueryAdapter = CameraQueryAdapter(database.cameraDao())

    /**
     * The query with the user's overrides applied.
     *
     * This, not [cameraQuery], is what detection must read through: a blocked camera that
     * still produces hazards means the driver's decision to silence it is quietly ignored.
     */
    private val blockingQuery = BlockingCameraQuery(query)

    /** How many cameras are loaded, for the settings screen. */
    suspend fun cameraCount(): Int = runCatching { database.cameraDao().count() }.getOrDefault(0)

    /**
     * Every camera, for the browser screen.
     *
     * A snapshot rather than an observable, because the database is rebuilt out of band by
     * 	ools/import and the screen re-reads on resume; a live query would repaint the list
     * for changes the driver did not make.
     *
     * Suspend because Room refuses main-thread access and the UI calls this on resume.
     */
    suspend fun allCameras(): List<Camera> =
        runCatching { database.cameraDao().allCameras() }
            .getOrDefault(emptyList())
            .map(CameraMappers::toDomain)

    /** The override set currently in force. */
    @Volatile
    var overrides: OverrideSet = OverrideSet()
        private set

    /** Re-reads `override_` and applies it to later queries. */
    suspend fun refreshOverrides() {
        val rows = runCatching { database.overrideDao().all() }.getOrElse { emptyList() }
        overrides = OverrideSet.from(rows)
        blockingQuery.update(overrides)
    }

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
