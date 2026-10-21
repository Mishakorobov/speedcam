package com.speedcam.service.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.speedcam.service.DetectionState
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController

/**
 * Background listener service on the phone receiving control commands from Wear OS companion.
 */
class PhoneWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val command = String(messageEvent.data, Charsets.UTF_8)
        Log.i(TAG, "Wear OS command received: path=$path, command=$command")

        if (path == WearCompanionBridge.PATH_CMD) {
            handleCommand(command)
        }
    }

    private fun handleCommand(command: String) {
        when (command) {
            WearCompanionBridge.CMD_START -> {
                ServiceController.start(this)
                val profiles = ProfileStore(this)
                WearCompanionBridge.broadcast(this, DetectionState.state.value, profiles, force = true)
            }
            WearCompanionBridge.CMD_STOP -> {
                ServiceController.stop(this)
            }
            WearCompanionBridge.CMD_PING -> {
                val profiles = ProfileStore(this)
                WearCompanionBridge.broadcast(this, DetectionState.state.value, profiles, force = true)
            }
            WearCompanionBridge.CMD_MUTE -> {
                DetectionState.update { it.copy(muted = !it.muted) }
            }
            WearCompanionBridge.CMD_TEST_VIBRATION -> {
                WearCompanionBridge.sendCommand(this, WearCompanionBridge.PATH_ALERT, "TEST_VIBRATION")
            }
        }
    }

    companion object {
        private const val TAG = "PhoneWearableListener"
    }
}
