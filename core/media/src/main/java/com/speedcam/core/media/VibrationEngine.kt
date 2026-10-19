package com.speedcam.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * The single haptic the original uses for hazards.
 *
 * There are no waveform patterns anywhere in its decompile — just
 * `VibrationEffect.createOneShot(500L, -1)`, one 500 ms pulse at the device default
 * amplitude. That is reproduced exactly.
 *
 * [VibrationPolicy.shouldVibrate] restricts it to the nearest hazard of the current tick,
 * matching the original, which vibrates only for the primary hazard and for the speedometer.
 * Vibrating on every hazard would be a continuous buzz through a corridor of them.
 */
class VibrationEngine(
    private val context: Context,
) {
    private val vibrator: Vibrator? =
        runCatching {
            context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
        }.getOrNull()

    @SuppressLint("MissingPermission") // Enforced by the class-level annotation.
    fun vibrate() {
        val device =
            vibrator ?: run {
                Log.w(TAG, "No vibrator on this device; skipping.")
                return
            }
        runCatching {
            device.vibrate(
                VibrationEffect.createOneShot(VibrationPolicy.DURATION_MS, VibrationPolicy.DEFAULT_AMPLITUDE),
            )
            Log.i(TAG, "Vibrated for ${VibrationPolicy.DURATION_MS} ms.")
        }.onFailure { Log.w(TAG, "Vibration refused: ${it.message}") }
    }

    @SuppressLint("MissingPermission") // Enforced by the class-level annotation.
    fun release() {
        vibrator?.runCatching { cancel() }
    }

    private companion object {
        const val TAG = "VibrationEngine"

        /** Kept for parity with the original's predefined-effect UI clicks; not used for hazards. */
        @Suppress("unused")
        fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }
}
