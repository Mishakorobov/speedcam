package com.speedcam.app.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.speedcam.app.SpeedCamApp
import com.speedcam.app.sync.HazardReportSyncWorker
import com.speedcam.app.util.toRussianTitle
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
                onReportSpecificHazard = { type -> reportSpecificHazard(appContext, type) },
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
        Toast.makeText(appContext, "Позиция HUD сброшена по умолчанию", Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggles session muting for the current hazard or session.
     */
    fun muteNearest(context: Context) {
        DetectionState.update { it.copy(muted = !it.muted) }
        val muted = DetectionState.state.value.muted
        val message = if (muted) "Звук HUD отключен" else "Звук HUD включен"
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
                    Toast.makeText(context, "Камера #${nearest.cameraId} заблокирована", Toast.LENGTH_SHORT).show()
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
                comment = if (nearest != null) "Отметка через плавающий HUD" else "Новая камера через плавающий HUD",
            )

        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = SpeedCamApp.cameraStore(context)
                store.submitReport(report)
                HazardReportSyncWorker.triggerImmediate(context)
            }.onSuccess {
                mainHandler.post {
                    Toast.makeText(context, "Отметка об опасности поставлена в очередь из HUD", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.w(TAG, "Could not queue hazard report from HUD: ${e.message}")
            }
        }
    }

    /**
     * One-tap reporting for temporary driver hazards (DPS patrol, mobile tripod, accident).
     */
    fun reportSpecificHazard(
        context: Context,
        type: HazardReportType,
        customComment: String? = null,
    ) {
        val state = DetectionState.state.value
        val lat = state.currentLat ?: state.nearest?.latitude
        val lon = state.currentLon ?: state.nearest?.longitude
        if (lat == null || lon == null) {
            mainHandler.post {
                Toast.makeText(context, "Нет GPS координат для отметки", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val defaultComment =
            when (type) {
                HazardReportType.DPS_PATROL -> "Засада ДПС (быстрая отметка)"
                HazardReportType.MOBILE_TRIPOD -> "Мобильная тренога (быстрая отметка)"
                HazardReportType.ROAD_ACCIDENT -> "Авария на дороге (быстрая отметка)"
                else -> "Отметка через плавающий HUD"
            }

        val report =
            HazardReportEntity(
                lat = lat,
                lon = lon,
                headingDeg = state.currentBearingDeg,
                cameraId = state.nearest?.cameraId,
                reportType = type.ordinal,
                speedLimitKmh = state.nearest?.limitKmh ?: state.activeSection?.limitKmh,
                comment = customComment ?: defaultComment,
            )

        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = SpeedCamApp.cameraStore(context)
                store.submitReport(report)

                if (type == HazardReportType.DPS_PATROL || type == HazardReportType.MOBILE_TRIPOD) {
                    val timeStr =
                        java.text
                            .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date())
                    store.addCamera(
                        lat = lat,
                        lon = lon,
                        kind = com.speedcam.core.detect.CameraKind.MOBILE,
                        limitKmh = state.nearest?.limitKmh ?: state.activeSection?.limitKmh,
                        note = type.toRussianTitle(),
                        created = timeStr,
                    )
                }

                HazardReportSyncWorker.triggerImmediate(context)
            }.onSuccess {
                mainHandler.post {
                    val label = type.toRussianTitle()
                    Toast.makeText(context, "Отметка «$label» отправлена!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.w(TAG, "Could not queue hazard report: ${e.message}")
            }
        }
    }
}
