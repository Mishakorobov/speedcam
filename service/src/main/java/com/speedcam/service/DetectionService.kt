package com.speedcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sticky foreground service that owns the detection pipeline.
 *
 * Stage 0 delivers the shell and the notification channel only. The location pipeline,
 * fix arbitration and alert dispatch are Stage 4; see Plan.md §8.
 *
 * Uses an explicit [CoroutineScope] rather than `LifecycleService`: this service outlives any
 * activity and is torn down by the system rather than by a lifecycle transition, so the
 * lifecycle-owner semantics of `LifecycleService` buy nothing here and would add a
 * dependency. The scope is cancelled in [onDestroy], which is the only teardown that matters.
 */
class DetectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        promoteToForeground(buildNotification())

        scope.launch {
            // Stage 4: attach the location provider and start the detection loop here.
        }

        // START_STICKY: the service must survive a low-memory kill. A detection aid that
        // silently stops is a safety regression, so the system is asked to restore it.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(notification: Notification) {
        // On API 34+ the type must be passed explicitly; on older versions the manifest
        // declaration alone is honoured and this overload does not exist.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_detection_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_detection_description)
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        const val CHANNEL_ID = "detection"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.speedcam.service.action.START"
        const val ACTION_STOP = "com.speedcam.service.action.STOP"
    }
}
