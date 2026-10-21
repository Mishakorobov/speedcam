package com.speedcam.service

import com.speedcam.core.detect.CameraKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CarLauncherBroadcasterTest {
    @Test
    fun `action constants match head unit launcher specifications`() {
        assertEquals("com.speedcam.app.CAR_SPEED_UPDATE", CarLauncherBroadcaster.ACTION_SPEEDCAM_TELEMETRY)
        assertEquals("com.fcc.launcher.action.SPEED_UPDATE", CarLauncherBroadcaster.ACTION_FCC_SPEED)
        assertEquals("com.agama.launcher.action.SPEED_UPDATE", CarLauncherBroadcaster.ACTION_AGAMA_SPEED)
        assertEquals("com.carlauncher.SPEED_UPDATE", CarLauncherBroadcaster.ACTION_CAR_LAUNCHER_SPEED)
    }

    @Test
    fun `buildTelemetryPayload builds basic telemetry on clear road`() {
        val state =
            DetectionUiState(
                running = true,
                hasFix = true,
                speedKmh = 72.5,
                muted = false,
            )
        val payload = CarLauncherBroadcaster.buildTelemetryPayload(state)

        assertEquals(72.5, payload[CarLauncherBroadcaster.EXTRA_SPEED_KMH])
        assertEquals(0, payload[CarLauncherBroadcaster.EXTRA_SPEED_LIMIT])
        assertEquals(false, payload[CarLauncherBroadcaster.EXTRA_IS_OVER_LIMIT])
        assertEquals(true, payload[CarLauncherBroadcaster.EXTRA_RADAR_RUNNING])
        assertEquals(false, payload[CarLauncherBroadcaster.EXTRA_MUTED])
        assertEquals(true, payload[CarLauncherBroadcaster.EXTRA_GPS_FIX])
        assertFalse(payload.containsKey(CarLauncherBroadcaster.EXTRA_HAZARD_NAME))
        assertFalse(payload.containsKey(CarLauncherBroadcaster.EXTRA_IN_SPECS_ZONE))
    }

    @Test
    fun `buildTelemetryPayload includes camera hazard details`() {
        val hazard =
            HazardView(
                cameraId = 101,
                latitude = 55.75,
                longitude = 37.61,
                distanceM = 320.0,
                limitKmh = 60,
                kind = CameraKind.RED_LIGHT,
                bearingDeg = 45.0,
                behind = false,
                excessKmh = 10.0,
            )
        val state =
            DetectionUiState(
                running = true,
                hasFix = true,
                speedKmh = 70.0,
                nearest = hazard,
            )
        val payload = CarLauncherBroadcaster.buildTelemetryPayload(state)

        assertEquals(70.0, payload[CarLauncherBroadcaster.EXTRA_SPEED_KMH])
        assertEquals(60, payload[CarLauncherBroadcaster.EXTRA_SPEED_LIMIT])
        assertEquals(true, payload[CarLauncherBroadcaster.EXTRA_IS_OVER_LIMIT])
        assertEquals("RED_LIGHT", payload[CarLauncherBroadcaster.EXTRA_HAZARD_NAME])
        assertEquals(CameraKind.RED_LIGHT.ordinal, payload[CarLauncherBroadcaster.EXTRA_HAZARD_KIND])
        assertEquals(320.0, payload[CarLauncherBroadcaster.EXTRA_HAZARD_DISTANCE])
        assertEquals(false, payload[CarLauncherBroadcaster.EXTRA_HAZARD_BEHIND])
    }

    @Test
    fun `buildTelemetryPayload includes SPECS corridor details`() {
        val section =
            SectionUiState(
                limitKmh = 90,
                averageKmh = 88.0,
                remainingM = 1500.0,
            )
        val state =
            DetectionUiState(
                running = true,
                hasFix = true,
                speedKmh = 85.0,
                activeSection = section,
            )
        val payload = CarLauncherBroadcaster.buildTelemetryPayload(state)

        assertEquals(90, payload[CarLauncherBroadcaster.EXTRA_SPEED_LIMIT])
        assertEquals(true, payload[CarLauncherBroadcaster.EXTRA_IN_SPECS_ZONE])
        assertEquals(88.0, payload[CarLauncherBroadcaster.EXTRA_SPECS_AVG_SPEED])
        assertEquals(1500.0, payload[CarLauncherBroadcaster.EXTRA_SPECS_REMAINING_M])
    }

    @Test
    fun `head unit receiver constants match automotive hardware standards`() {
        assertEquals("android.intent.action.QUICKBOOT_POWERON", CarHeadUnitReceiver.ACTION_QUICKBOOT_POWERON)
        assertEquals("com.htc.intent.action.QUICKBOOT_POWERON", CarHeadUnitReceiver.ACTION_HTC_QUICKBOOT)
        assertEquals("com.syu.ms.ACC_ON", CarHeadUnitReceiver.ACTION_SYU_ACC_ON)
        assertEquals("com.syu.ms.ACC_OFF", CarHeadUnitReceiver.ACTION_SYU_ACC_OFF)
    }
}
