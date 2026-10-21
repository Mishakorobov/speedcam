package com.speedcam.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Coordinates automatic detection start and stop based on Car Bluetooth events.
 */
object CarBluetoothManager {
    private const val TAG = "CarBluetoothManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

    val isStopPending: Boolean
        get() = pendingStopRunnable != null

    fun handleConnected(
        context: Context,
        device: BluetoothDevice?,
    ) {
        val profiles = ProfileStore(context)
        if (!profiles.autoStartOnBluetooth) {
            Log.d(TAG, "Car BT connected but auto-start is disabled.")
            return
        }

        if (!CarBluetoothMatcher.matchesFilter(device, profiles.bluetoothDeviceFilter)) {
            Log.d(TAG, "Connected device does not match Bluetooth filter.")
            return
        }

        cancelPendingStop()

        if (!DetectionState.state.value.running) {
            val deviceDesc = runCatching { device?.name ?: device?.address }.getOrNull() ?: "unknown"
            Log.i(TAG, "Auto-starting detection on Car Bluetooth connection ($deviceDesc).")
            profiles.isEnabled = true
            ServiceController.start(context)
            DetectionState.update { it.copy(running = true) }
        } else {
            Log.d(TAG, "Detection is already active; cancel pending stop.")
        }
    }

    fun handleDisconnected(
        context: Context,
        device: BluetoothDevice?,
    ) {
        val profiles = ProfileStore(context)
        if (!profiles.autoStopOnBluetooth) {
            Log.d(TAG, "Car BT disconnected but auto-stop is disabled.")
            return
        }

        if (!CarBluetoothMatcher.matchesFilter(device, profiles.bluetoothDeviceFilter)) {
            Log.d(TAG, "Disconnected device does not match Bluetooth filter.")
            return
        }

        if (!DetectionState.state.value.running) {
            Log.d(TAG, "Detection is not running; ignoring BT disconnect.")
            return
        }

        scheduleStop(context, profiles.bluetoothStopDelaySeconds)
    }

    fun scheduleStop(
        context: Context,
        delaySeconds: Int,
    ) {
        cancelPendingStop()
        val appContext = context.applicationContext

        if (delaySeconds <= 0) {
            executeStop(appContext)
            return
        }

        Log.i(TAG, "Scheduling auto-stop in $delaySeconds seconds.")
        val runnable =
            Runnable {
                pendingStopRunnable = null
                if (!hasActiveMatchingConnection(appContext)) {
                    Log.i(TAG, "Stop delay elapsed with no connected car BT; stopping detection.")
                    executeStop(appContext)
                } else {
                    Log.i(TAG, "Device reconnected during stop delay; detection remains active.")
                }
            }
        pendingStopRunnable = runnable
        mainHandler.postDelayed(runnable, delaySeconds * 1000L)
    }

    fun cancelPendingStop() {
        pendingStopRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingStopRunnable = null
            Log.d(TAG, "Cancelled pending auto-stop timer.")
        }
    }

    private fun executeStop(context: Context) {
        ServiceController.stop(context)
        DetectionState.update { it.copy(running = false) }
    }

    fun hasActiveMatchingConnection(context: Context): Boolean {
        val filter = ProfileStore(context).bluetoothDeviceFilter
        if (filter.isBlank()) {
            return BluetoothRoute.hasConnectedAudioDevice(context)
        }
        return getConnectedAudioDevices(context).any {
            CarBluetoothMatcher.matchesFilter(it, filter)
        }
    }

    @SuppressLint("MissingPermission")
    fun getConnectedAudioDevices(context: Context): List<BluetoothDevice> {
        if (!BluetoothRoute.canQueryConnections(context)) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = runCatching { manager?.adapter }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) return emptyList()
        return runCatching {
            manager.getConnectedDevices(BluetoothProfile.HEADSET) +
                manager.getConnectedDevices(BluetoothProfile.A2DP)
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(context: Context): List<BluetoothDevice> {
        if (!BluetoothRoute.canQueryConnections(context)) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = runCatching { manager?.adapter }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) return emptyList()
        return runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())
    }
}
