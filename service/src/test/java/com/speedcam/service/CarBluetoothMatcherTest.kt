package com.speedcam.service

import android.bluetooth.BluetoothClass
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CarBluetoothMatcherTest {
    @Test
    fun `identifies car stereo from device class`() {
        val isCar =
            CarBluetoothMatcher.isCarDevice(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                name = "Stereo",
            )
        assertTrue(isCar)
    }

    @Test
    fun `identifies car handsfree from device class`() {
        val isCar =
            CarBluetoothMatcher.isCarDevice(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                name = "Car Unit",
            )
        assertTrue(isCar)
    }

    @Test
    fun `identifies car stereo by vehicle brand keywords`() {
        val carNames =
            listOf(
                "Toyota Touch 2",
                "BMW_BT_1234",
                "Audi MMI Navigation",
                "VW Golf BT",
                "SYNC MyFord",
                "Mercedes-Benz BT",
                "Hyundai Audio",
                "Kia Motors BT",
                "Skoda Infotainment",
                "Pioneer MVH-S320BT",
                "Teyes CC3",
                "Baseus BA01 Car",
                "Ugreen Car Bluetooth",
                "My Car Kit",
            )

        for (name in carNames) {
            val isCar =
                CarBluetoothMatcher.isCarDevice(
                    deviceClass = null,
                    name = name,
                )
            assertTrue(isCar, "Expected '$name' to be recognized as car device")
        }
    }

    @Test
    fun `rejects non-car peripherals and wearables without car keywords`() {
        val nonCarNames =
            listOf(
                "Mi Smart Band 7",
                "Apple Watch Series 8",
                "Logitech MX Master 3",
                "HP DeskJet 2700",
                "Living Room TV",
                "Samsung Galaxy Buds",
            )

        for (name in nonCarNames) {
            val isCar =
                CarBluetoothMatcher.isCarDevice(
                    deviceClass = 0,
                    name = name,
                )
            assertFalse(isCar, "Expected '$name' not to be recognized as car device")
        }
    }

    @Test
    fun `identifies general audio video major class as audio device`() {
        val isAudio =
            CarBluetoothMatcher.isAudioDevice(
                deviceClass = null,
                majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                name = "Generic Receiver",
            )
        assertTrue(isAudio)
    }

    @Test
    fun `blank filter matches any car or audio device`() {
        val matchesCar =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                name = "Toyota BT",
                address = "00:11:22:33:44:55",
                filter = "",
            )
        assertTrue(matchesCar)

        val matchesNullFilter =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                name = "Toyota BT",
                address = "00:11:22:33:44:55",
                filter = null,
            )
        assertTrue(matchesNullFilter)

        val rejectsNonAudio =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = 0,
                majorClass = BluetoothClass.Device.Major.WEARABLE,
                name = "Smart Fitness Band",
                address = "00:11:22:33:44:55",
                filter = "",
            )
        assertFalse(rejectsNonAudio)
    }

    @Test
    fun `mac address filter matches target device case insensitively`() {
        val targetMac = "AA:BB:CC:DD:EE:FF"

        val matchesExact =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = 0,
                majorClass = 0,
                name = "Any Name",
                address = "AA:BB:CC:DD:EE:FF",
                filter = targetMac,
            )
        assertTrue(matchesExact)

        val matchesLowerCase =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = 0,
                majorClass = 0,
                name = "Any Name",
                address = "aa:bb:cc:dd:ee:ff",
                filter = targetMac,
            )
        assertTrue(matchesLowerCase)

        val rejectsOtherDevice =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                name = "Other Car Stereo",
                address = "11:22:33:44:55:66",
                filter = targetMac,
            )
        assertFalse(rejectsOtherDevice)
    }

    @Test
    fun `name filter matches target device by name`() {
        val targetName = "Toyota Touch 2"

        val matchesName =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = 0,
                majorClass = 0,
                name = "Toyota Touch 2",
                address = "00:11:22:33:44:55",
                filter = targetName,
            )
        assertTrue(matchesName)

        val rejectsDifferentName =
            CarBluetoothMatcher.matchesFilter(
                deviceClass = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO,
                name = "VW Golf BT",
                address = "00:11:22:33:44:55",
                filter = targetName,
            )
        assertFalse(rejectsDifferentName)
    }
}
