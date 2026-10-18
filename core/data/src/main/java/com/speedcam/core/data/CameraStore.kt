package com.speedcam.core.data

import android.content.Context
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
    fun cameraQuery(): CameraQuery = query

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
