package com.speedcam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles Android Head Unit (ГУ) ignition and power state transitions (ACC ON / ACC OFF),
 * quick boot, and system startup.
 */
class CarHeadUnitReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val profiles = ProfileStore(context)

        Log.d(TAG, "Received Head Unit power event: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT,
            ACTION_SYU_ACC_ON,
            -> {
                if (profiles.headUnitAutoStart && profiles.isEnabled) {
                    Log.i(TAG, "Auto-starting detection on Head Unit power ON ($action)")
                    ServiceController.start(context)
                }
            }

            ACTION_SYU_ACC_OFF,
            Intent.ACTION_SHUTDOWN,
            -> {
                if (profiles.headUnitAutoStart) {
                    Log.i(TAG, "Stopping detection on Head Unit power OFF ($action)")
                    ServiceController.stop(context)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CarHeadUnitReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
        const val ACTION_SYU_ACC_ON = "com.syu.ms.ACC_ON"
        const val ACTION_SYU_ACC_OFF = "com.syu.ms.ACC_OFF"
    }
}
