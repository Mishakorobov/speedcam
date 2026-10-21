package com.speedcam.service

import android.content.Context
import android.content.Intent

/**
 * Broadcasts real-time vehicle telemetry and speed-camera hazard warnings to
 * Android Head Unit launchers (FCC Car Launcher, Agama Car Launcher, CarWebGuru, Car Launcher).
 */
object CarLauncherBroadcaster {
    const val ACTION_SPEEDCAM_TELEMETRY = "com.speedcam.app.CAR_SPEED_UPDATE"
    const val ACTION_FCC_SPEED = "com.fcc.launcher.action.SPEED_UPDATE"
    const val ACTION_AGAMA_SPEED = "com.agama.launcher.action.SPEED_UPDATE"
    const val ACTION_CAR_LAUNCHER_SPEED = "com.carlauncher.SPEED_UPDATE"

    const val EXTRA_SPEED_KMH = "speed_kmh"
    const val EXTRA_SPEED_LIMIT = "speed_limit"
    const val EXTRA_IS_OVER_LIMIT = "is_over_limit"
    const val EXTRA_HAZARD_NAME = "hazard_name"
    const val EXTRA_HAZARD_KIND = "hazard_kind"
    const val EXTRA_HAZARD_DISTANCE = "hazard_distance"
    const val EXTRA_HAZARD_BEHIND = "hazard_behind"
    const val EXTRA_IN_SPECS_ZONE = "in_specs_zone"
    const val EXTRA_SPECS_AVG_SPEED = "specs_avg_speed"
    const val EXTRA_SPECS_REMAINING_M = "specs_remaining_m"
    const val EXTRA_RADAR_RUNNING = "radar_running"
    const val EXTRA_MUTED = "muted"
    const val EXTRA_GPS_FIX = "gps_fix"

    /**
     * Builds standard SpeedCam telemetry payload for car launcher widgets and tests.
     */
    fun buildTelemetryPayload(state: DetectionUiState): Map<String, Any> {
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh ?: 0
        val map =
            mutableMapOf<String, Any>(
                EXTRA_SPEED_KMH to state.speedKmh,
                EXTRA_SPEED_LIMIT to limit,
                EXTRA_IS_OVER_LIMIT to state.overLimit,
                EXTRA_RADAR_RUNNING to state.running,
                EXTRA_MUTED to state.muted,
                EXTRA_GPS_FIX to state.hasFix,
            )

        state.nearest?.let { nearest ->
            map[EXTRA_HAZARD_NAME] = nearest.kind.name
            map[EXTRA_HAZARD_KIND] = nearest.kind.ordinal
            map[EXTRA_HAZARD_DISTANCE] = nearest.distanceM
            map[EXTRA_HAZARD_BEHIND] = nearest.behind
        }

        state.activeSection?.let { section ->
            map[EXTRA_IN_SPECS_ZONE] = true
            map[EXTRA_SPECS_AVG_SPEED] = section.averageKmh
            map[EXTRA_SPECS_REMAINING_M] = section.remainingM
        }
        return map
    }

    /**
     * Builds standard SpeedCam telemetry intent for car launcher widgets.
     */
    fun buildTelemetryIntent(state: DetectionUiState): Intent {
        val payload = buildTelemetryPayload(state)
        val intent = Intent(ACTION_SPEEDCAM_TELEMETRY)
        payload.forEach { (key, value) ->
            when (value) {
                is Double -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        return intent
    }

    /**
     * Broadcasts vehicle telemetry to all supported Android Head Unit launchers.
     */
    fun broadcast(
        context: Context,
        state: DetectionUiState,
        profiles: ProfileStore,
    ) {
        if (!profiles.launcherBroadcastEnabled) return

        val speed = state.speedKmh.toFloat()
        val limit = state.activeSection?.limitKmh ?: state.nearest?.limitKmh ?: 0

        context.sendBroadcast(buildTelemetryIntent(state))

        // FCC Car Launcher standard speed update
        context.sendBroadcast(
            Intent(ACTION_FCC_SPEED).apply {
                putExtra("speed", speed)
                putExtra("speed_limit", limit)
            },
        )

        // Agama Car Launcher standard speed update
        context.sendBroadcast(
            Intent(ACTION_AGAMA_SPEED).apply {
                putExtra("speed", speed)
                putExtra("limit", limit)
            },
        )

        // Car Launcher Pro standard speed update
        context.sendBroadcast(
            Intent(ACTION_CAR_LAUNCHER_SPEED).apply {
                putExtra("speed", speed)
            },
        )
    }
}
