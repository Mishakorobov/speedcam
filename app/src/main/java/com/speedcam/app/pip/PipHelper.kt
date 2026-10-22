package com.speedcam.app.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.speedcam.app.R
import com.speedcam.service.DetectionUiState
import com.speedcam.service.ProfileStore

/**
 * Helper utilities for Android Picture-in-Picture (PiP) speedometer mode.
 */
object PipHelper {
    const val ACTION_PIP_MUTE = "com.speedcam.app.pip.ACTION_PIP_MUTE"
    const val ACTION_PIP_TOGGLE = "com.speedcam.app.pip.ACTION_PIP_TOGGLE"

    const val ASPECT_RATIO_NUMERATOR = 16
    const val ASPECT_RATIO_DENOMINATOR = 9

    private const val REQUEST_CODE_MUTE = 201
    private const val REQUEST_CODE_TOGGLE = 202

    /**
     * Builds [PictureInPictureParams] for current detection state and profiles.
     */
    fun buildPipParams(
        context: Context,
        state: DetectionUiState,
        profiles: ProfileStore,
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        builder.setAspectRatio(Rational(ASPECT_RATIO_NUMERATOR, ASPECT_RATIO_DENOMINATOR))

        val actions =
            listOf(
                buildMuteAction(context, state.muted),
                buildToggleAction(context, state.running),
            )
        builder.setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = profiles.pipEnabled && profiles.pipAutoEnter && state.running
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    private fun buildMuteAction(
        context: Context,
        isMuted: Boolean,
    ): RemoteAction {
        val iconRes = if (isMuted) R.drawable.ic_pip_volume_up else R.drawable.ic_pip_volume_off
        val title =
            if (isMuted) {
                context.getString(R.string.pip_action_unmute)
            } else {
                context.getString(R.string.pip_action_mute)
            }
        val intent = Intent(ACTION_PIP_MUTE).setPackage(context.packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MUTE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent,
        )
    }

    private fun buildToggleAction(
        context: Context,
        isRunning: Boolean,
    ): RemoteAction {
        val iconRes = if (isRunning) R.drawable.ic_pip_stop else R.drawable.ic_pip_play
        val title =
            if (isRunning) {
                context.getString(R.string.pip_action_stop)
            } else {
                context.getString(R.string.pip_action_start)
            }
        val intent = Intent(ACTION_PIP_TOGGLE).setPackage(context.packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_TOGGLE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent,
        )
    }

    /**
     * Requests the activity to enter Picture-in-Picture mode if enabled.
     */
    fun enterPipMode(
        activity: Activity,
        state: DetectionUiState,
        profiles: ProfileStore,
    ): Boolean {
        if (!profiles.pipEnabled) return false
        val params = buildPipParams(activity, state, profiles)
        return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    /**
     * Updates activity's Picture-in-Picture parameters with current state.
     */
    fun updatePipParams(
        activity: Activity,
        state: DetectionUiState,
        profiles: ProfileStore,
    ) {
        val params = buildPipParams(activity, state, profiles)
        runCatching { activity.setPictureInPictureParams(params) }
    }
}
