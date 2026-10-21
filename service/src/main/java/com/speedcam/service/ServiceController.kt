package com.speedcam.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts and stops [DetectionService] from inside the app.
 *
 * Exists because a `location`-typed foreground service cannot be started from outside the app
 * on Android 14 and later: the shell gets `Requires permission not exported from uid 10055`,
 * and any other external caller is blocked the same way. Only the app's own uid may start it,
 * so this has to live in the app and be called from the UI or from a receiver the app owns.
 */
object ServiceController {
    private const val TAG = "ServiceController"

    /**
     * Starts detection, promoting the service to the foreground.
     *
     * Uses `startForegroundService` where the service may be started from the background and
     * the plain `startService` below where it may not: on Android 8+ a background start that
     * never calls `startForeground()` within five seconds throws, whereas the foreground start
     * is simply refused outright.
     */
    fun start(context: Context) {
        val intent = Intent(context, DetectionService::class.java).setAction(DetectionService.ACTION_START)

        val started =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
            } else {
                runCatching { context.startService(intent) }.isSuccess
            }

        if (started) {
            Resurrection.schedule(context)
            Log.i(TAG, "Detection requested.")
        } else {
            // Reaching here means the foreground-start restriction refused us, which happens
            // when the app is not in the foreground and has no exemption yet.
            Log.w(TAG, "Could not start detection.")
        }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(
                Intent(context, DetectionService::class.java).setAction(DetectionService.ACTION_STOP),
            )
        }
        Resurrection.cancel(context)
        ProfileStore(context).isEnabled = false
        Log.i(TAG, "Detection stopped.")
    }

    /** Requests the running detection service to refresh its queries after a database update. */
    fun reloadDatabase(context: Context) {
        val intent =
            Intent(context, DetectionService::class.java).setAction(DetectionService.ACTION_RELOAD_DATABASE)
        runCatching { context.startService(intent) }
    }
}
