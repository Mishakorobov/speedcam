package com.speedcam.wear.state

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.speedcam.wear.haptics.MotorcycleHapticProfile
import com.speedcam.wear.model.WearTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global reactive observable state store for the Wear OS companion application.
 */
object WearStateStore {
    private const val TAG = "WearStateStore"
    private const val PREFS_NAME = "wear_speedcam_prefs"
    private const val KEY_HAPTIC_PROFILE = "haptic_profile"

    private val _telemetry = MutableStateFlow(WearTelemetry())
    val telemetry: StateFlow<WearTelemetry> = _telemetry.asStateFlow()

    private val _hapticProfile = MutableStateFlow(MotorcycleHapticProfile.MOTORCYCLE_HIGH)
    val hapticProfile: StateFlow<MotorcycleHapticProfile> = _hapticProfile.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        val shared = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = shared
        val savedName = shared.getString(KEY_HAPTIC_PROFILE, MotorcycleHapticProfile.MOTORCYCLE_HIGH.name)
        val profile =
            runCatching {
                MotorcycleHapticProfile.valueOf(savedName ?: MotorcycleHapticProfile.MOTORCYCLE_HIGH.name)
            }.getOrDefault(MotorcycleHapticProfile.MOTORCYCLE_HIGH)
        _hapticProfile.value = profile
    }

    fun updateTelemetry(newTelemetry: WearTelemetry) {
        _telemetry.value = newTelemetry
    }

    fun setHapticProfile(profile: MotorcycleHapticProfile) {
        _hapticProfile.value = profile
        prefs?.edit()?.putString(KEY_HAPTIC_PROFILE, profile.name)?.apply()
    }

    fun sendCommandToPhone(
        context: Context,
        command: String,
    ) {
        runCatching {
            val nodeClient = Wearable.getNodeClient(context)
            val messageClient = Wearable.getMessageClient(context)
            nodeClient.connectedNodes
                .addOnSuccessListener { nodes ->
                    val data = command.toByteArray(Charsets.UTF_8)
                    for (node in nodes) {
                        messageClient.sendMessage(node.id, "/speedcam/cmd", data)
                    }
                }.addOnFailureListener { error ->
                    Log.w(TAG, "Failed to send command to phone: ${error.message}")
                }
        }.onFailure { error ->
            Log.d(TAG, "Wearable API failed: ${error.message}")
        }
    }
}
