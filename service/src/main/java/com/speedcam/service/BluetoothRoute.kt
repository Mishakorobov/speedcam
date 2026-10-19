package com.speedcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Whether a Bluetooth audio device is connected: hands-free or media.
 *
 * Two things depend on this and neither is available from the media layer: the warning
 * *profile* switches from Common to Car ([com.speedcam.core.detect.TransportProfile.select])
 * and the *output route* moves to Bluetooth multimedia. Keeping the query here is what stops
 * the two from disagreeing.
 *
 * The original maintains this with `BluetoothProfile.ServiceListener` on HEADSET and A2DP plus
 * two broadcast receivers, and additionally pokes `startVoiceRecognition` every second for ten
 * seconds after a connection to force the SCO route open. None of that is reproduced: the SCO
 * handling belongs to the output layer, and forcing a voice-recognition session on a driver's
 * headset as a side effect of an alert would be unpleasant and is not this app's business.
 */
object BluetoothRoute {
    private const val TAG = "BluetoothRoute"

    /**
     * Whether a hands-free device is connected, or false when that cannot be established.
     *
     * `getConnectedDevices` needs `BLUETOOTH_CONNECT` from Android 12, and it is a runtime
     * permission there. Returning false when it is absent is the right failure: the driver
     * gets the phone's own speaker instead of silence, which is a better outcome than
     * refusing to run.
     */
    @SuppressLint("MissingPermission") // Guarded by canQueryConnections below.
    fun hasConnectedAudioDevice(context: Context): Boolean {
        val manager =
            context
                .getSystemService(BluetoothManager::class.java)
                ?.takeIf { canQueryConnections(context) }
                ?: return false

        val adapter: BluetoothAdapter = runCatching { manager.adapter }.getOrNull() ?: return false
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return false

        val devices =
            runCatching {
                // Both profiles, as the original's BluetoothHeadsetUtils does. Checking only
                // HEADSET would miss a car stereo, which negotiates A2DP alone and never
                // presents a hands-free profile.
                manager.getConnectedDevices(BluetoothProfile.HEADSET) +
                    manager.getConnectedDevices(BluetoothProfile.A2DP)
            }.onFailure { Log.d(TAG, "Could not query connected headsets: ${it.message}") }
                .getOrDefault(emptyList())

        return devices.isNotEmpty()
    }

    private fun canQueryConnections(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

fun hasConnectedAudioDevice(context: Context): Boolean = BluetoothRoute.hasConnectedAudioDevice(context)
