package com.speedcam.app

import android.app.Application
import android.content.Context
import com.speedcam.app.hud.HudOverlayManager
import com.speedcam.app.map.OfflineTileManager
import com.speedcam.app.sync.DatabaseSyncWorker
import com.speedcam.app.sync.HazardReportSyncWorker
import com.speedcam.app.widget.SpeedCamWidgetManager
import com.speedcam.core.data.CameraStore
import com.speedcam.service.ProfileStore

/**
 * Holds the single [CameraStore] and [OfflineTileManager] for the life of the process.
 *
 * `SpeedCamApp` is the natural owner because the database handle must outlive any single
 * screen or service binding, and Android guarantees exactly one Application instance per
 * process. The store is created on first use rather than in `onCreate`, so that merely
 * launching the app does not open the database.
 */
class SpeedCamApp : Application() {
    private var store: CameraStore? = null
    private var tileManagerInstance: OfflineTileManager? = null

    val cameraStore: CameraStore
        get() =
            synchronized(this) {
                store ?: CameraStore.open(this).also {
                    it.useContraCamDb = ProfileStore(this).useContraCamDb
                    store = it
                }
            }

    val offlineTileManager: OfflineTileManager
        get() =
            synchronized(this) {
                tileManagerInstance ?: OfflineTileManager(this).also {
                    tileManagerInstance = it
                }
            }

    /** Closes any open handle and reopens the database from disk. */
    fun reloadCameraStore(): CameraStore =
        synchronized(this) {
            store?.close()
            val newStore =
                CameraStore.open(this).also {
                    it.useContraCamDb = ProfileStore(this).useContraCamDb
                }
            store = newStore
            newStore
        }

    /** Releases the database handle before disk swap operations. */
    fun closeCameraStore() =
        synchronized(this) {
            store?.close()
            store = null
        }

    override fun onCreate() {
        super.onCreate()
        if (ProfileStore(this).autoUpdateEnabled) {
            DatabaseSyncWorker.schedulePeriodic(this)
        }
        HazardReportSyncWorker.schedulePeriodic(this)
        HudOverlayManager.init(this)
        SpeedCamWidgetManager.init(this)
    }

    override fun onTerminate() {
        closeCameraStore()
        tileManagerInstance?.close()
        tileManagerInstance = null
        super.onTerminate()
    }

    companion object {
        private fun from(context: Context): SpeedCamApp = context.applicationContext as SpeedCamApp

        /** Reaches the process-wide store from a `Context` that is not the Application itself. */
        fun cameraStore(context: Context): CameraStore = from(context).cameraStore

        /** Reopens the process-wide store from disk after an update or rollback. */
        fun reloadCameraStore(context: Context): CameraStore = from(context).reloadCameraStore()

        /** Closes the database handle cleanly across the process. */
        fun closeCameraStore(context: Context) = from(context).closeCameraStore()

        /** Reaches the process-wide offline tile manager. */
        fun offlineTileManager(context: Context): OfflineTileManager = from(context).offlineTileManager
    }
}
