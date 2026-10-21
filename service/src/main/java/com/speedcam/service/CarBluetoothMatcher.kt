package com.speedcam.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice

/**
 * Pure and device-level matching rules to identify car head units and audio devices.
 */
object CarBluetoothMatcher {
    /** Common automotive manufacturer and aftermarket stereo keywords. */
    private val CAR_KEYWORDS =
        listOf(
            "car",
            "auto",
            "bt-car",
            "carkit",
            "my car",
            "media",
            "sync",
            "mmi",
            "r-link",
            "audi",
            "bmw",
            "mercedes",
            "toyota",
            "lexus",
            "vw",
            "volkswagen",
            "skoda",
            "honda",
            "hyundai",
            "kia",
            "nissan",
            "ford",
            "chevrolet",
            "subaru",
            "mazda",
            "pioneer",
            "kenwood",
            "alpine",
            "jvc",
            "sony",
            "teyes",
            "ugreen",
            "baseus",
        )

    /** Evaluates whether device attributes describe a car stereo or handsfree unit. */
    fun isCarDevice(
        deviceClass: Int?,
        name: String?,
    ): Boolean {
        if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        ) {
            return true
        }
        return isCarName(name)
    }

    /** Evaluates whether device attributes describe an audio device (A2DP, car audio, headset). */
    fun isAudioDevice(
        deviceClass: Int?,
        majorClass: Int?,
        name: String?,
    ): Boolean {
        if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        return isCarDevice(deviceClass, name)
    }

    /**
     * Matches device attributes against the user's filter.
     *
     * If [filter] is null or blank, matches any audio or car device.
     * If [filter] is non-empty, checks against MAC [address] or [name].
     */
    fun matchesFilter(
        deviceClass: Int?,
        majorClass: Int?,
        name: String?,
        address: String?,
        filter: String?,
    ): Boolean {
        if (filter.isNullOrBlank()) {
            return isAudioDevice(deviceClass, majorClass, name)
        }
        if (address?.equals(filter, ignoreCase = true) == true) return true
        if (name?.equals(filter, ignoreCase = true) == true) return true
        return false
    }

    /** Inspects a real [BluetoothDevice] against the configured filter. */
    @SuppressLint("MissingPermission")
    fun matchesFilter(
        device: BluetoothDevice?,
        filter: String?,
    ): Boolean {
        if (device == null) return false
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        val devClass = btClass?.deviceClass
        val majorClass = btClass?.majorDeviceClass
        val name = runCatching { device.name }.getOrNull()
        val address = runCatching { device.address }.getOrNull()
        return matchesFilter(devClass, majorClass, name, address, filter)
    }

    private fun isCarName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return CAR_KEYWORDS.any { lower.contains(it) }
    }
}
