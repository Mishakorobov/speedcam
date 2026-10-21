package com.speedcam.wear.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.speedcam.wear.model.WearTelemetry

/**
 * Haptic tactile vibration controller designed for motorcycle riders on Wear OS.
 *
 * Produces strong, high-contrast vibration waveforms that can be felt through thick riding gloves
 * over the vibration of motorcycle handlebars.
 */
class WearHapticController(
    context: Context,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var lastVibratedMilestone: Int? = null
    private var lastWarningLevel: Int = 0
    private var lastVibrateTimestampMs: Long = 0L

    /**
     * Evaluates incoming telemetry and triggers appropriate motorcycle vibration patterns.
     */
    fun evaluateAndVibrate(
        telemetry: WearTelemetry,
        profile: MotorcycleHapticProfile,
    ) {
        if (profile == MotorcycleHapticProfile.SILENT || vibrator == null || !vibrator.hasVibrator()) {
            return
        }

        val now = System.currentTimeMillis()
        val warningLevel = telemetry.warningLevel
        val overLimit = telemetry.overLimit
        val distance = telemetry.nearestDistanceM

        if (isImminentDanger(warningLevel, overLimit, distance)) {
            handleDangerVibration(profile, now)
            return
        }

        if (profile == MotorcycleHapticProfile.OVERLIMIT_ONLY) {
            lastWarningLevel = warningLevel
            return
        }

        handleStandardAlerts(telemetry, profile, now)
    }

    private fun isImminentDanger(
        warningLevel: Int,
        overLimit: Boolean,
        distanceM: Int?,
    ): Boolean =
        warningLevel == LEVEL_DANGER ||
            (overLimit && distanceM != null && distanceM <= DISTANCE_DANGER_MAX_M)

    private fun handleDangerVibration(
        profile: MotorcycleHapticProfile,
        now: Long,
    ) {
        if (now - lastVibrateTimestampMs >= DANGER_REPEAT_INTERVAL_MS || lastWarningLevel != LEVEL_DANGER) {
            vibrateDangerOverlimit(profile)
            lastVibrateTimestampMs = now
            lastWarningLevel = LEVEL_DANGER
        }
    }

    private fun handleStandardAlerts(
        telemetry: WearTelemetry,
        profile: MotorcycleHapticProfile,
        now: Long,
    ) {
        val warningLevel = telemetry.warningLevel
        val distance = telemetry.nearestDistanceM

        if (warningLevel == LEVEL_SPECS && lastWarningLevel != LEVEL_SPECS) {
            vibrateSpecsEntry(profile)
            lastVibrateTimestampMs = now
        } else if (distance != null && warningLevel == LEVEL_APPROACHING) {
            checkDistanceMilestone(distance, profile, now)
        } else if (lastWarningLevel in LEVEL_APPROACHING..LEVEL_SPECS && warningLevel == LEVEL_CLEAR) {
            vibrateCleared()
            lastVibratedMilestone = null
        }
        lastWarningLevel = warningLevel
    }

    private fun checkDistanceMilestone(
        distance: Int,
        profile: MotorcycleHapticProfile,
        now: Long,
    ) {
        val milestone = getMilestone(distance)
        if (milestone != null && milestone != lastVibratedMilestone) {
            vibrateApproachingCamera(profile)
            lastVibrateTimestampMs = now
            lastVibratedMilestone = milestone
        }
    }

    fun vibrateTest(profile: MotorcycleHapticProfile) {
        vibrateDangerOverlimit(profile)
    }

    private fun vibrateDangerOverlimit(profile: MotorcycleHapticProfile) {
        val isHigh = profile == MotorcycleHapticProfile.MOTORCYCLE_HIGH
        val timings =
            if (isHigh) {
                longArrayOf(0, 250, 70, 250, 70, 250, 70, 350)
            } else {
                longArrayOf(0, 180, 80, 180, 80, 220)
            }
        val amplitudes =
            if (isHigh) {
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
            } else {
                intArrayOf(0, 220, 0, 220, 0, 240)
            }
        executeWaveform(timings, amplitudes)
    }

    private fun vibrateApproachingCamera(profile: MotorcycleHapticProfile) {
        val isHigh = profile == MotorcycleHapticProfile.MOTORCYCLE_HIGH
        val timings = if (isHigh) longArrayOf(0, 300, 100, 300) else longArrayOf(0, 200, 100, 200)
        val amplitudes = if (isHigh) intArrayOf(0, 255, 0, 255) else intArrayOf(0, 190, 0, 190)
        executeWaveform(timings, amplitudes)
    }

    private fun vibrateSpecsEntry(profile: MotorcycleHapticProfile) {
        val isHigh = profile == MotorcycleHapticProfile.MOTORCYCLE_HIGH
        val timings = if (isHigh) longArrayOf(0, 450, 120, 450) else longArrayOf(0, 300, 100, 300)
        val amplitudes = if (isHigh) intArrayOf(0, 255, 0, 255) else intArrayOf(0, 200, 0, 200)
        executeWaveform(timings, amplitudes)
    }

    private fun vibrateCleared() {
        executeWaveform(longArrayOf(0, 120), intArrayOf(0, 180))
    }

    private fun executeWaveform(
        timings: LongArray,
        amplitudes: IntArray,
    ) {
        if (vibrator == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }

    companion object {
        const val LEVEL_CLEAR = 0
        const val LEVEL_APPROACHING = 1
        const val LEVEL_DANGER = 2
        const val LEVEL_SPECS = 3

        const val DISTANCE_DANGER_MAX_M = 700
        const val DANGER_REPEAT_INTERVAL_MS = 2500L

        fun getMilestone(distanceM: Int): Int? =
            when (distanceM) {
                in 0..120 -> 100
                in 121..300 -> 250
                in 301..600 -> 500
                else -> null
            }
    }
}
