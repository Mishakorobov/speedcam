package com.speedcam.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Brings the detection service back after the device or the process kills it.
 *
 * Plan.md §8.1 calls for `AlarmManager` plus a boot receiver. A detection aid that silently
 * stops is a safety regression, and on a long drive the two things that reliably kill a
 * foreground service are the device rebooting and the process being reclaimed, so both paths
 * have to be covered.
 *
 * `START_STICKY` on the service handles the ordinary case; this covers the cases the system
 * will not restart for us.
 */
class ResurrectionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!ProfileStore(context).isEnabled) {
            Log.i(TAG, "Detection is switched off; not resurrecting.")
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> startService(context)
            ALARM_ACTION -> {
                startService(context)
                Resurrection.schedule(context)
            }

            else -> Log.w(TAG, "Ignoring unexpected broadcast ${intent.action}")
        }
    }

    private fun startService(context: Context) {
        val launched =
            runCatching { context.startForegroundService(Intent(context, DetectionService::class.java)) }
        if (launched.isFailure) {
            // Expected on a fresh boot before the user has unlocked once: background activity
            // starts are blocked until then. The next alarm will try again.
            Log.w(TAG, "Could not start the detection service yet: ${launched.exceptionOrNull()?.message}")
        }
    }

    companion object {
        private const val TAG = "Resurrection"

        /** Private action, so the alarm is not confused with anyone else's. */
        const val ALARM_ACTION = "com.speedcam.service.action.RESCHEDULE"

        /**
         * How often to re-arm. AlarmManager cannot be trusted to fire while the device is
         * asleep, so the interval is short enough that a missed alarm is corrected on the next
         * one, and the service re-arms on every start.
         */
        const val RESCHEDULE_INTERVAL_MS = 15 * 60 * 1000L
    }
}

/** Schedules the repeating alarm that re-arms the service. */
object Resurrection {
    private const val TAG = "Resurrection"

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Exact alarms are a privileged capability from Android 12. The window is what the
            // app is allowed, and a few minutes of drift does not matter for a 15-minute alarm.
            runCatching {
                alarms.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    nextTrigger(),
                    ResurrectionReceiver.RESCHEDULE_INTERVAL_MS,
                    pending,
                )
            }
        } else {
            alarms.set(AlarmManager.RTC_WAKEUP, nextTrigger(), pending)
        }
        Log.d(TAG, "Resurrection alarm scheduled.")
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(context))
    }

    private fun nextTrigger(): Long = System.currentTimeMillis() + ResurrectionReceiver.RESCHEDULE_INTERVAL_MS

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ResurrectionReceiver::class.java).setAction(ResurrectionReceiver.ALARM_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
