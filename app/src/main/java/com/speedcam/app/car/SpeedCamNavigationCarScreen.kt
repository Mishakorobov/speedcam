package com.speedcam.app.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.speedcam.app.R
import com.speedcam.app.hud.HudOverlayManager
import com.speedcam.app.pip.formatCameraKindLabel
import com.speedcam.app.pip.formatPipDistance
import com.speedcam.app.util.toRussianTitle
import com.speedcam.core.data.HazardReportType
import com.speedcam.service.DetectionState
import com.speedcam.service.DetectionUiState
import com.speedcam.service.ServiceController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Automotive Android Auto navigation map screen using [NavigationTemplate].
 *
 * Replaces the static informational dashboard with a real-time hardware-rendered
 * mapping canvas, concentric radar distance rings, active speed camera enforcement
 * alerts, steering-wheel controls, and 1-tap crowdsource reporting buttons.
 */
class SpeedCamNavigationCarScreen(
    carContext: CarContext,
) : Screen(carContext),
    SurfaceCallback {
    private var state: DetectionUiState = DetectionState.state.value
    private val renderer = CarMapSurfaceRenderer(carContext)

    private val navManagerCallback =
        object : NavigationManagerCallback {
            override fun onStopNavigation() {
                ServiceController.stop(carContext)
                DetectionState.update { it.copy(running = false) }
            }
        }

    init {
        // Register Head Unit Surface rendering callback
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        // Bind automotive navigation lifecycle
        val navManager = carContext.getCarService(NavigationManager::class.java)
        navManager.setNavigationManagerCallback(navManagerCallback)

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching { navManager.navigationStarted() }
                    lifecycleScope.launch {
                        DetectionState.state.collectLatest { newState ->
                            state = newState
                            renderer.render(newState)
                            invalidate()
                        }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    runCatching { navManager.navigationEnded() }
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    renderer.detachSurface()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val navInfo = buildNavigationInfo(state)
        val actionStrip = buildActionStrip(state)
        val mapActionStrip = buildMapActionStrip(state)

        return NavigationTemplate
            .Builder()
            .setNavigationInfo(navInfo)
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { isInPanMode ->
                renderer.setPanMode(isInPanMode)
                renderer.render(state)
                invalidate()
            }.setBackgroundColor(CarColor.DEFAULT)
            .build()
    }

    // ----------------------------------------------------------------- SurfaceCallback Handlers

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        renderer.attachSurface(surfaceContainer)
        renderer.render(state)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer.detachSurface()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        renderer.updateVisibleArea(visibleArea)
        renderer.render(state)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        renderer.render(state)
    }

    override fun onScroll(
        distanceX: Float,
        distanceY: Float,
    ) {
        renderer.onScroll(distanceX, distanceY)
        renderer.render(state)
    }

    override fun onScale(
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
    ) {
        renderer.onScale(scaleFactor)
        renderer.render(state)
    }

    // ----------------------------------------------------------------- Navigation & Actions

    private fun buildNavigationInfo(state: DetectionUiState): NavigationTemplate.NavigationInfo {
        val activeSection = state.activeSection
        val nearest = state.nearest

        return when {
            activeSection != null -> {
                val remaining = formatPipDistance(activeSection.remainingM)
                val avgSpeed = activeSection.averageKmh.roundToInt()
                MessageInfo
                    .Builder("КСС · Лимит ${activeSection.limitKmh} км/ч")
                    .setText("Средняя: $avgSpeed км/ч · осталось $remaining")
                    .setImage(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_car_warning),
                            ).build(),
                    ).build()
            }
            nearest != null -> {
                val limitStr = nearest.limitKmh?.let { "$it км/ч" } ?: "Без лимита"
                val dirStr = if (nearest.behind) "В спину" else "Впереди"
                val distStr = formatPipDistance(nearest.distanceM)
                val warningStatus = if (state.overLimit) "СНИЖАЙТЕ СКОРОСТЬ!" else "В норме"
                MessageInfo
                    .Builder("${formatCameraKindLabel(nearest.kind)} · $distStr")
                    .setText("Лимит: $limitStr · $dirStr · $warningStatus")
                    .setImage(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_car_camera),
                            ).build(),
                    ).build()
            }
            else -> {
                val speedDesc = if (state.running) "${state.speedKmh.roundToInt()} км/ч" else "Стоит"
                val subtitle =
                    if (state.running) {
                        "Скорость: $speedDesc · Загружено ${state.cameraCount} камер"
                    } else {
                        "Радар на паузе · Нажмите Старт для поиска"
                    }
                MessageInfo
                    .Builder("Радар SpeedCam активен")
                    .setText(subtitle)
                    .setImage(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_speedcam_logo),
                            ).build(),
                    ).build()
            }
        }
    }

    /**
     * Top-level Action Strip (max 4 actions: Mute + 3 one-tap crowdsource reporting buttons).
     */
    private fun buildActionStrip(state: DetectionUiState): ActionStrip =
        ActionStrip
            .Builder()
            .addAction(buildMuteAction(state))
            .addAction(buildReportAction(HazardReportType.DPS_PATROL, "ДПС", R.drawable.ic_car_police))
            .addAction(buildReportAction(HazardReportType.MOBILE_TRIPOD, "Тренога", R.drawable.ic_car_camera))
            .addAction(buildReportAction(HazardReportType.ROAD_ACCIDENT, "ДТП", R.drawable.ic_car_warning))
            .build()

    /**
     * Map Action Strip (max 4 actions with icons: Zoom In, Zoom Out, Center, Toggle Radar).
     */
    private fun buildMapActionStrip(state: DetectionUiState): ActionStrip =
        ActionStrip
            .Builder()
            .addAction(
                Action
                    .Builder()
                    .setIcon(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_in),
                            ).build(),
                    ).setOnClickListener {
                        renderer.zoomIn()
                        renderer.render(state)
                    }.build(),
            ).addAction(
                Action
                    .Builder()
                    .setIcon(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_out),
                            ).build(),
                    ).setOnClickListener {
                        renderer.zoomOut()
                        renderer.render(state)
                    }.build(),
            ).addAction(
                Action
                    .Builder()
                    .setIcon(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(carContext, R.drawable.ic_car_my_location),
                            ).build(),
                    ).setOnClickListener {
                        renderer.resetCenter()
                        renderer.render(state)
                        invalidate()
                    }.build(),
            ).addAction(
                Action
                    .Builder()
                    .setIcon(
                        CarIcon
                            .Builder(
                                IconCompat.createWithResource(
                                    carContext,
                                    if (state.running) R.drawable.ic_pip_stop else R.drawable.ic_pip_play,
                                ),
                            ).build(),
                    ).setOnClickListener {
                        toggleRadarService()
                    }.build(),
            ).build()

    private fun buildMuteAction(state: DetectionUiState): Action {
        val iconRes = if (state.muted) R.drawable.ic_pip_volume_up else R.drawable.ic_pip_volume_off
        val title = if (state.muted) "Вкл. звук" else "Без звука"
        return Action
            .Builder()
            .setTitle(title)
            .setIcon(
                CarIcon
                    .Builder(
                        IconCompat.createWithResource(carContext, iconRes),
                    ).build(),
            ).setOnClickListener {
                HudOverlayManager.muteNearest(carContext)
                val isMuted = DetectionState.state.value.muted
                val msg = if (isMuted) "Звук отключен" else "Звук включен"
                CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
                invalidate()
            }.build()
    }

    private fun buildReportAction(
        type: HazardReportType,
        shortTitle: String,
        iconRes: Int,
    ): Action =
        Action
            .Builder()
            .setTitle(shortTitle)
            .setIcon(
                CarIcon
                    .Builder(
                        IconCompat.createWithResource(carContext, iconRes),
                    ).build(),
            ).setOnClickListener {
                HudOverlayManager.reportSpecificHazard(carContext, type)
                CarToast
                    .makeText(
                        carContext,
                        "Отметка «${type.toRussianTitle()}» отправлена!",
                        CarToast.LENGTH_SHORT,
                    ).show()
                renderer.render(state)
            }.build()

    private fun toggleRadarService() {
        val willRun = !state.running
        if (willRun) {
            ServiceController.start(carContext)
            CarToast.makeText(carContext, "Радар SpeedCam запущен", CarToast.LENGTH_SHORT).show()
        } else {
            ServiceController.stop(carContext)
            CarToast.makeText(carContext, "Радар SpeedCam остановлен", CarToast.LENGTH_SHORT).show()
        }
        DetectionState.update { it.copy(running = willRun) }
        invalidate()
    }
}
