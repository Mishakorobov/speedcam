package com.speedcam.service

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Listens for system Bluetooth connection events to auto-start and auto-stop detection.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val device = extractDevice(intent)
        Log.d(TAG, "Received Bluetooth broadcast: $action, device: ${device?.address}")

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                CarBluetoothManager.handleConnected(context, device)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                CarBluetoothManager.handleDisconnected(context, device)
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            -> {
                handleProfileStateChange(context, intent, device)
            }
            else -> Log.d(TAG, "Unhandled Bluetooth action: $action")
        }
    }

    private fun handleProfileStateChange(
        context: Context,
        intent: Intent,
        device: BluetoothDevice?,
    ) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                CarBluetoothManager.handleConnected(context, device)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                CarBluetoothManager.handleDisconnected(context, device)
            }
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "CarBluetoothReceiver"
    }
}
