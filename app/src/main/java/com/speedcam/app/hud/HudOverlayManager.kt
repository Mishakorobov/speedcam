package com.speedcam.app.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.speedcam.app.SpeedCamApp
import com.speedcam.app.sync.HazardReportSyncWorker
import com.speedcam.core.data.HazardReportEntity
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionState
import com.speedcam.service.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manages the lifecycle and actions of the Floating HUD Overlay across the application.
 *
 * Ensures the HUD window floats above other apps (navigation, launcher, etc.) throughout
 * the driving session, surviving activity destruction and reacting to detection state changes.
 */
object HudOverlayManager {
    private const val TAG = "HudOverlayManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlay: HudOverlay? = null
    private var initialized = false

    val isShowing: Boolean
        get() = overlay?.isShowing == true

    /**
     * Initializes background observation of the detection pipeline.
     */
    fun init(appContext: Context) {
        if (initialized) return
        initialized = true

        scope.launch {
            DetectionState.state
                .map { it.running }
                .distinctUntilChanged()
                .collect {
                    sync(appContext)
                }
        }
    }

    /**
     * Synchronizes the floating HUD visibility with current settings and detection state.
     */
    fun sync(context: Context) {
        val appContext = context.applicationContext
        val profiles = ProfileStore(appContext)
        val canDraw = HudOverlay.canDrawOverlays(appContext)
        val isRunning = DetectionState.state.value.running

        val wanted = profiles.isEnabled && profiles.hudEnabled && canDraw && isRunning

        if (wanted && overlay?.isShowing != true) {
            show(appContext)
        } else if (!wanted && overlay?.isShowing == true) {
            hide()
        }
    }

    /**
     * Shows the floating HUD overlay window if permitted.
     */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!HudOverlay.canDrawOverlays(appContext)) {
            Log.w(TAG, "Cannot show HUD: SYSTEM_ALERT_WINDOW permission not granted")
            return false
        }

        if (overlay?.isShowing == true) return true

        val positions = HudPositionStore(appContext)
        val newOverlay =
            HudOverlay(
                context = appContext,
                positionStore = positions,
                onBlockNearest = { blockNearest(appContext) },
                onMuteNearest = { muteNearest(appContext) },
                onReportHazard = { reportHazard(appContext) },
            )

        val shown = newOverlay.show()
        if (shown) {
            overlay = newOverlay
            Log.i(TAG, "Floating HUD overlay displayed over other apps.")
        }
        return shown
    }

    /**
     * Removes the floating HUD overlay window.
     */
    fun hide() {
        overlay?.hide()
        overlay = null
        Log.i(TAG, "Floating HUD overlay hidden.")
    }

    /**
     * Toggles HUD overlay on or off.
     */
    fun toggle(context: Context) {
        val appContext = context.applicationContext
        val profiles = ProfileStore(appContext)
        profiles.hudEnabled = !profiles.hudEnabled
        sync(appContext)
    }

    /**
     * Sets HUD enabled state explicitly.
     */
    fun setHudEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val appContext = context.applicationContext
        ProfileStore(appContext).hudEnabled = enabled
        sync(appContext)
    }

    /**
     * Resets the floating HUD position to the default screen coordinates.
     */
    fun resetPosition(context: Context) {
        val appContext = context.applicationContext
        HudPositionStore(appContext).clear()
        overlay?.resetToDefaultPosition()
        Toast.makeText(appContext, "HUD position reset to default", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggles session muting for the current hazard or session.
     */
    fun muteNearest(context: Context) {
        DetectionState.update { it.copy(muted = !it.muted) }
        val muted = DetectionState.state.value.muted
        val message = if (muted) "HUD alert muted" else "HUD alert unmuted"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Permanently blocks alerts for the nearest camera.
     */
    fun blockNearest(context: Context) {
        val nearest = DetectionState.state.value.nearest ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = SpeedCamApp.cameraStore(context)
                store.block(LatLon(nearest.latitude, nearest.longitude))
            }.onSuccess {
                mainHandler.post {
                    Toast.makeText(context, "Camera #${nearest.cameraId} blocked", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.w(TAG, "Could not block camera from HUD: ${e.message}")
            }
        }
    }

    /**
     * Queues a user hazard report at the active camera or current position.
     */
    fun reportHazard(context: Context) {
        val state = DetectionState.state.value
        val nearest = state.nearest
        val lat = nearest?.latitude ?: state.currentLat ?: return
        val lon = nearest?.longitude ?: state.currentLon ?: return

        val report =
            HazardReportEntity(
                lat = lat,
                lon = lon,
                headingDeg = state.currentBearingDeg,
                cameraId = nearest?.cameraId,
                reportType =
                    if (nearest != null) {
                        HazardReportType.FALSE_POSITIVE.ordinal
                    } else {
                        HazardReportType.NEW_CAMERA.ordinal
                    },
                speedLimitKmh = state.activeSection?.limitKmh ?: nearest?.limitKmh,
                comment = if (nearest != null) "Reported via Floating HUD" else "New camera via Floating HUD",
            )

        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = SpeedCamApp.cameraStore(context)
                store.submitReport(report)
                HazardReportSyncWorker.triggerImmediate(context)
            }.onSuccess {
                mainHandler.post {
                    Toast.makeText(context, "Hazard report queued from HUD", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.w(TAG, "Could not queue hazard report from HUD: ${e.message}")
            }
        }
    }
}
