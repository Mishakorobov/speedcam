package com.speedcam.wear.service

import android.content.Context
import android.os.PowerManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.speedcam.wear.haptics.WearHapticController
import com.speedcam.wear.model.WearTelemetry
import com.speedcam.wear.state.WearStateStore

/**
 * Wearable listener service receiving real-time vehicle telemetry and hazard alerts from phone.
 */
class WearCompanionListenerService : WearableListenerService() {
    private lateinit var hapticController: WearHapticController

    override fun onCreate() {
        super.onCreate()
        WearStateStore.initialize(applicationContext)
        hapticController = WearHapticController(applicationContext)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/speedcam/telemetry" -> {
                val json = String(messageEvent.data, Charsets.UTF_8)
                val telemetry = WearTelemetry.fromJson(json)
                WearStateStore.updateTelemetry(telemetry)

                val profile = WearStateStore.hapticProfile.value
                hapticController.evaluateAndVibrate(telemetry, profile)

                if (telemetry.warningLevel == WearHapticController.LEVEL_DANGER) {
                    wakeScreenBriefly()
                }
            }

            "/speedcam/alert" -> {
                val payload = String(messageEvent.data, Charsets.UTF_8)
                if (payload == "TEST_VIBRATION") {
                    hapticController.vibrateTest(WearStateStore.hapticProfile.value)
                }
            }
        }
    }

    private fun wakeScreenBriefly() {
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager

            @Suppress("DEPRECATION")
            val wakeLock =
                powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    WAKE_LOCK_TAG,
                )
            wakeLock?.acquire(WAKE_LOCK_DURATION_MS)
        }
    }

    companion object {
        private const val WAKE_LOCK_TAG = "speedcam:wear_wake"
        private const val WAKE_LOCK_DURATION_MS = 3000L
    }
}
