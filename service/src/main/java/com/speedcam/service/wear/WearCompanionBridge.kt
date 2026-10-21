package com.speedcam.service.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.speedcam.service.DetectionUiState
import com.speedcam.service.ProfileStore

/**
 * Bridges detection state from the phone to connected Wear OS companion watches.
 */
object WearCompanionBridge {
    const val PATH_TELEMETRY = "/speedcam/telemetry"
    const val PATH_ALERT = "/speedcam/alert"
    const val PATH_CMD = "/speedcam/cmd"

    const val CMD_START = "START"
    const val CMD_STOP = "STOP"
    const val CMD_MUTE = "MUTE"
    const val CMD_PING = "PING"
    const val CMD_TEST_VIBRATION = "TEST_VIBRATION"

    private const val TAG = "WearCompanionBridge"
    private const val THROTTLE_INTERVAL_MS = 400L

    private var lastBroadcastMs = 0L
    private var lastWarningLevel = 0

    /**
     * Broadcasts current telemetry to all paired Wear OS nodes.
     */
    fun broadcast(
        context: Context,
        state: DetectionUiState,
        profiles: ProfileStore,
        force: Boolean = false,
    ) {
        if (!profiles.wearCompanionEnabled) return

        val now = System.currentTimeMillis()
        val telemetry = WearTelemetry.fromDetectionUiState(state)
        val stateChanged = telemetry.warningLevel != lastWarningLevel || telemetry.running != state.running

        if (!force && !stateChanged && now - lastBroadcastMs < THROTTLE_INTERVAL_MS) {
            return
        }
        lastBroadcastMs = now
        lastWarningLevel = telemetry.warningLevel

        val jsonBytes = telemetry.toJson().toByteArray(Charsets.UTF_8)
        sendToConnectedNodes(context, PATH_TELEMETRY, jsonBytes)
    }

    /**
     * Sends an alert command or test vibration signal to Wear OS companion devices.
     */
    fun sendCommand(
        context: Context,
        path: String,
        payload: String = "",
    ) {
        sendToConnectedNodes(context, path, payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Sends binary payload to all active connected Wear OS nodes via Google Play Services.
     */
    fun sendToConnectedNodes(
        context: Context,
        path: String,
        data: ByteArray,
    ) {
        runCatching {
            val nodeClient = Wearable.getNodeClient(context)
            val messageClient = Wearable.getMessageClient(context)
            nodeClient.connectedNodes
                .addOnSuccessListener { nodes ->
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, path, data)
                    }
                }.addOnFailureListener { error ->
                    Log.w(TAG, "Failed to query connected Wear OS nodes: ${error.message}")
                }
        }.onFailure { error ->
            Log.d(TAG, "Play Services Wearable not available: ${error.message}")
        }
    }

    /**
     * Queries connected Wear OS devices count and display name of the primary companion watch.
     */
    fun queryConnectedWatches(
        context: Context,
        onResult: (count: Int, deviceName: String) -> Unit,
    ) {
        runCatching {
            val nodeClient = Wearable.getNodeClient(context)
            nodeClient.connectedNodes
                .addOnSuccessListener { nodes ->
                    val primaryName = nodes.firstOrNull()?.displayName ?: ""
                    onResult(nodes.size, primaryName)
                }.addOnFailureListener {
                    onResult(0, "")
                }
        }.onFailure {
            onResult(0, "")
        }
    }
}
