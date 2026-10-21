package com.speedcam.service

import com.speedcam.core.detect.CameraKind
import com.speedcam.core.detect.Hazard
import com.speedcam.core.detect.SectionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the interface should show about one camera. */
data class HazardView(
    val cameraId: Long,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Double,
    val limitKmh: Int?,
    val kind: CameraKind,
    val bearingDeg: Double,
    val behind: Boolean,
    /** Speed minus the camera's limit, or null when the camera has none. */
    val excessKmh: Double?,
)

/** Live or just-completed average-speed corridor state for the HUD and status screen. */
data class SectionUiState(
    val limitKmh: Int,
    val averageKmh: Double,
    val remainingM: Double,
    val completed: Boolean = false,
) {
    val overLimit: Boolean get() = limitKmh > 0 && averageKmh > limitKmh
}

/**
 * Everything the interface needs to draw itself.
 *
 * One immutable snapshot rather than a bag of separate flows: the HUD, the list and the
 * settings all render the same moment, and a single value means they cannot disagree about
 * what the car is doing.
 */
data class DetectionUiState(
    val running: Boolean = false,
    val hasFix: Boolean = false,
    val speedKmh: Double = 0.0,
    /** Nearest camera in range, or null when there is none. */
    val nearest: HazardView? = null,
    /** The two nearest, for the two-slot layout the original uses. */
    val second: HazardView? = null,
    /** Active or just-exited average-speed corridor, or null when outside any SPECS zone. */
    val activeSection: SectionUiState? = null,
    val gpsStatus: GpsStatus = GpsStatus.HEALTHY,
    val cameraCount: Int = 0,
    val profileName: String = "",
    val headsetConnected: Boolean = false,
    val muted: Boolean = false,
) {
    /**
     * True when the driver is over the limit of the nearest camera or the active SPECS zone.
     *
     * The original turns its bubble red on `speed > limit + legalExcess`; there is no
     * tolerance setting here, so this is the plain comparison.
     */
    val overLimit: Boolean
        get() {
            if (activeSection?.overLimit == true) return true
            val hazard = nearest ?: return false
            val limit = hazard.limitKmh?.takeIf { it > 0 } ?: return false
            return speedKmh > limit
        }
}

/**
 * The bridge between the detection service and the interface.
 *
 * A process-wide holder rather than a bound service. Binding would mean connection state,
 * a reconnection path and a lifecycle, none of which buy anything here: the service and the
 * UI are in the same process, the state is a single immutable snapshot, and the alternative
 * makes the HUD fail silently whenever the binding has not completed — which is precisely
 * when a driver needs to see something.
 */
object DetectionState {
    private val _state = MutableStateFlow(DetectionUiState())
    val state: StateFlow<DetectionUiState> = _state

    fun update(transform: (DetectionUiState) -> DetectionUiState) {
        _state.value = transform(_state.value)
    }

    fun set(next: DetectionUiState) {
        _state.value = next
    }

    fun reset() {
        _state.value = DetectionUiState()
    }
}

/** Builds the two-slot view the original lays out, nearest first. */
fun List<Hazard>.toHazardViews(limit: Int = 2): List<HazardView> =
    take(limit).map { hazard ->
        HazardView(
            cameraId = hazard.camera.id,
            latitude = hazard.camera.position.lat,
            longitude = hazard.camera.position.lon,
            distanceM = hazard.distanceM,
            limitKmh = hazard.camera.limitKmh,
            kind = hazard.camera.kind,
            bearingDeg = hazard.bearingDeg,
            behind = hazard.isBehind,
            excessKmh = hazard.excessKmh,
        )
    }

/** Maps a [SectionEvent] into the UI state shown by the HUD and status screen. */
fun SectionEvent.toSectionUiState(): SectionUiState? =
    when (this) {
        is SectionEvent.Entered ->
            SectionUiState(
                limitKmh = section.limitKmh,
                averageKmh = 0.0,
                remainingM = section.lengthM,
                completed = false,
            )

        is SectionEvent.Progress ->
            SectionUiState(
                limitKmh = section.limitKmh,
                averageKmh = averageKmh,
                remainingM = remainingM,
                completed = false,
            )

        is SectionEvent.Exited ->
            SectionUiState(
                limitKmh = section.limitKmh,
                averageKmh = averageKmh,
                remainingM = 0.0,
                completed = true,
            )

        is SectionEvent.Aborted -> null
    }
