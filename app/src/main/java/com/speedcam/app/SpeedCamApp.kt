package com.speedcam.app

import android.app.Application
import android.content.Context
import com.speedcam.core.data.CameraStore

/**
 * Holds the single [CameraStore] for the life of the process.
 *
 * `SpeedCamApp` is the natural owner because the database handle must outlive any single
 * screen or service binding, and Android guarantees exactly one Application instance per
 * process. The store is created on first use rather than in `onCreate`, so that merely
 * launching the app does not open the database.
 *
 * Note that the database this opens is created empty on first run. Seeding it from the file
 * produced by `tools/import` is a packaging step that has not been done yet.
 */
class SpeedCamApp : Application() {
    private var store: CameraStore? = null

    val cameraStore: CameraStore
        get() = store ?: CameraStore.open(this).also { store = it }

    override fun onTerminate() {
        // Only ever invoked on an emulator, but closing the handle keeps the contract honest.
        store?.close()
        store = null
        super.onTerminate()
    }

    companion object {
        /** Reaches the process-wide store from a `Context` that is not the Application itself. */
        fun cameraStore(context: Context): CameraStore = (context.applicationContext as SpeedCamApp).cameraStore
    }
}
