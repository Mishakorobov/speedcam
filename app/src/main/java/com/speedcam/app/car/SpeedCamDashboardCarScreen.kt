package com.speedcam.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.speedcam.app.R
import com.speedcam.app.hud.HudOverlayManager
import com.speedcam.app.pip.formatCameraKindLabel
import com.speedcam.app.pip.formatPipDistance
import com.speedcam.service.DetectionState
import com.speedcam.service.DetectionUiState
import com.speedcam.service.ServiceController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Automotive Android Auto dashboard screen.
 *
 * Implements Google's Android for Cars Driver Distraction guidelines with glanceable
 * typography, speed telemetry, camera hazard warnings, and steering wheel / touch controls.
 */
class SpeedCamDashboardCarScreen(
    carContext: CarContext,
) : Screen(carContext) {
    private var state: DetectionUiState = DetectionState.state.value

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    lifecycleScope.launch {
                        DetectionState.state.collectLatest { newState ->
                            state = newState
                            invalidate()
                        }
                    }
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        paneBuilder.addRow(buildSpeedRow(state))
        paneBuilder.addRow(buildHazardRow(state))
        paneBuilder.addRow(buildStatusRow(state))

        paneBuilder.addAction(buildToggleAction(state))

        val actionStrip =
            ActionStrip
                .Builder()
                .addAction(buildMuteAction(state))
                .build()

        return PaneTemplate
            .Builder(paneBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .setTitle("Радар SpeedCam")
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildSpeedRow(state: DetectionUiState): Row {
        val speedStr = if (state.running) "${state.speedKmh.roundToInt()} км/ч" else "Стоит"
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh ?: 0
        val limitDesc = if (limit > 0) "Лимит: $limit км/ч" else "Без лимита"
        val statusDesc =
            when {
                state.overLimit -> "ПРЕВЫШЕНИЕ СКОРОСТИ!"
                state.hasFix -> "GPS активен"
                else -> "Поиск GPS..."
            }

        return Row
            .Builder()
            .setTitle("Скорость: $speedStr")
            .addText("$limitDesc · $statusDesc")
            .build()
    }

    private fun buildHazardRow(state: DetectionUiState): Row {
        val activeSection = state.activeSection
        val nearest = state.nearest
        return when {
            activeSection != null -> {
                val remaining = formatPipDistance(activeSection.remainingM)
                val avgSpeed = activeSection.averageKmh.roundToInt()
                Row
                    .Builder()
                    .setTitle("Зона КСС · Лимит ${activeSection.limitKmh} км/ч")
                    .addText("Средняя $avgSpeed км/ч · осталось $remaining")
                    .build()
            }
            nearest != null -> {
                val limitStr = nearest.limitKmh?.let { "$it км/ч" } ?: "Без лимита"
                val dirStr = if (nearest.behind) "В спину" else "Впереди"
                val distStr = formatPipDistance(nearest.distanceM)
                Row
                    .Builder()
                    .setTitle("${formatCameraKindLabel(nearest.kind)} · $distStr")
                    .addText("Лимит: $limitStr · $dirStr")
                    .build()
            }
            else -> {
                Row
                    .Builder()
                    .setTitle("Дорога свободна")
                    .addText(
                        if (state.running) {
                            "Радар SpeedCam активен · Опасностей рядом нет"
                        } else {
                            "Радар на паузе"
                        },
                    ).build()
            }
        }
    }

    private fun buildStatusRow(state: DetectionUiState): Row {
        val runStatus = if (state.running) "Активен" else "На паузе"
        val muteStatus = if (state.muted) "Без звука" else "Звук включен"
        return Row
            .Builder()
            .setTitle("Радар: $runStatus")
            .addText("Оповещения: $muteStatus · загружено ${state.cameraCount} камер")
            .build()
    }

    private fun buildToggleAction(state: DetectionUiState): Action =
        Action
            .Builder()
            .setTitle(if (state.running) "Остановить радар" else "Запустить радар")
            .setOnClickListener {
                val willRun = !state.running
                if (willRun) {
                    ServiceController.start(carContext)
                } else {
                    ServiceController.stop(carContext)
                }
                DetectionState.update { it.copy(running = willRun) }
            }.build()

    private fun buildMuteAction(state: DetectionUiState): Action {
        val iconRes = if (state.muted) R.drawable.ic_pip_volume_up else R.drawable.ic_pip_volume_off
        val title = if (state.muted) "Вкл. звук" else "Выкл. звук"
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
            }.build()
    }
}
