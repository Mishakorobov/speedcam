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
            .setTitle("SpeedCam Radar")
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildSpeedRow(state: DetectionUiState): Row {
        val speedStr = if (state.running) "${state.speedKmh.roundToInt()} km/h" else "Stopped"
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh ?: 0
        val limitDesc = if (limit > 0) "Limit: $limit km/h" else "No limit"
        val statusDesc =
            when {
                state.overLimit -> "OVER SPEED LIMIT!"
                state.hasFix -> "GPS Active"
                else -> "Searching GPS..."
            }

        return Row
            .Builder()
            .setTitle("Speed: $speedStr")
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
                    .setTitle("SPECS Zone · Limit ${activeSection.limitKmh} km/h")
                    .addText("Average $avgSpeed km/h · $remaining remaining")
                    .build()
            }
            nearest != null -> {
                val limitStr = nearest.limitKmh?.let { "$it km/h" } ?: "No limit"
                val dirStr = if (nearest.behind) "Behind" else "Ahead"
                val distStr = formatPipDistance(nearest.distanceM)
                Row
                    .Builder()
                    .setTitle("${formatCameraKindLabel(nearest.kind)} · $distStr")
                    .addText("Limit: $limitStr · $dirStr")
                    .build()
            }
            else -> {
                Row
                    .Builder()
                    .setTitle("Road Clear")
                    .addText(
                        if (state.running) {
                            "SpeedCam radar active · No hazards nearby"
                        } else {
                            "Radar is paused"
                        },
                    ).build()
            }
        }
    }

    private fun buildStatusRow(state: DetectionUiState): Row {
        val runStatus = if (state.running) "Active" else "Paused"
        val muteStatus = if (state.muted) "Muted" else "Sound Active"
        return Row
            .Builder()
            .setTitle("Radar: $runStatus")
            .addText("Alerts: $muteStatus · ${state.cameraCount} cameras loaded")
            .build()
    }

    private fun buildToggleAction(state: DetectionUiState): Action =
        Action
            .Builder()
            .setTitle(if (state.running) "Stop Radar" else "Start Radar")
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
        val title = if (state.muted) "Unmute" else "Mute"
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
