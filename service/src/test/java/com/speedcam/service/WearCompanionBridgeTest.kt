package com.speedcam.service

import com.speedcam.core.detect.CameraKind
import com.speedcam.service.wear.WearTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearCompanionBridgeTest {
    @Test
    fun `telemetry serializes and deserializes accurately`() {
        val original =
            WearTelemetry(
                speedKmh = 78,
                speedLimitKmh = 60,
                overLimit = true,
                nearestDistanceM = 350,
                nearestKind = "FIXED",
                nearestBehind = false,
                inSpecsZone = true,
                specsAverageKmh = 64.5,
                specsLimitKmh = 60,
                specsRemainingM = 1200.0,
                warningLevel = 2,
                running = true,
                hasFix = true,
                timestampMs = 1700000000000L,
            )

        val json = original.toJson()
        val parsed = WearTelemetry.fromJson(json)

        assertEquals(original.speedKmh, parsed.speedKmh)
        assertEquals(original.speedLimitKmh, parsed.speedLimitKmh)
        assertEquals(original.overLimit, parsed.overLimit)
        assertEquals(original.nearestDistanceM, parsed.nearestDistanceM)
        assertEquals(original.nearestKind, parsed.nearestKind)
        assertEquals(original.nearestBehind, parsed.nearestBehind)
        assertEquals(original.inSpecsZone, parsed.inSpecsZone)
        assertEquals(original.specsAverageKmh, parsed.specsAverageKmh)
        assertEquals(original.specsLimitKmh, parsed.specsLimitKmh)
        assertEquals(original.specsRemainingM, parsed.specsRemainingM)
        assertEquals(original.warningLevel, parsed.warningLevel)
        assertEquals(original.running, parsed.running)
        assertEquals(original.hasFix, parsed.hasFix)
        assertEquals(original.timestampMs, parsed.timestampMs)
    }

    @Test
    fun `telemetry handles nullable fields when idle`() {
        val idle =
            WearTelemetry(
                speedKmh = 0,
                speedLimitKmh = null,
                overLimit = false,
                nearestDistanceM = null,
                nearestKind = null,
                warningLevel = 0,
                running = false,
                hasFix = false,
            )

        val json = idle.toJson()
        val parsed = WearTelemetry.fromJson(json)

        assertEquals(0, parsed.speedKmh)
        assertNull(parsed.speedLimitKmh)
        assertFalse(parsed.overLimit)
        assertNull(parsed.nearestDistanceM)
        assertNull(parsed.nearestKind)
        assertEquals(0, parsed.warningLevel)
        assertFalse(parsed.running)
    }

    @Test
    fun `fromDetectionUiState computes correct warning level for danger`() {
        val state =
            DetectionUiState(
                running = true,
                hasFix = true,
                speedKmh = 85.0,
                nearest =
                    HazardView(
                        cameraId = 1L,
                        latitude = 55.75,
                        longitude = 37.61,
                        distanceM = 240.0,
                        limitKmh = 60,
                        kind = CameraKind.FIXED,
                        bearingDeg = 0.0,
                        behind = false,
                        excessKmh = 25.0,
                    ),
            )

        val telemetry = WearTelemetry.fromDetectionUiState(state)

        assertEquals(85, telemetry.speedKmh)
        assertEquals(60, telemetry.speedLimitKmh)
        assertTrue(telemetry.overLimit)
        assertEquals(240, telemetry.nearestDistanceM)
        assertEquals(2, telemetry.warningLevel) // Danger overlimit
    }

    @Test
    fun `fromDetectionUiState computes correct warning level for SPECS zone`() {
        val state =
            DetectionUiState(
                running = true,
                hasFix = true,
                speedKmh = 58.0,
                activeSection =
                    SectionUiState(
                        limitKmh = 60,
                        averageKmh = 57.0,
                        remainingM = 1500.0,
                    ),
            )

        val telemetry = WearTelemetry.fromDetectionUiState(state)

        assertEquals(58, telemetry.speedKmh)
        assertEquals(60, telemetry.speedLimitKmh)
        assertFalse(telemetry.overLimit)
        assertTrue(telemetry.inSpecsZone)
        assertEquals(3, telemetry.warningLevel) // Specs zone
    }
}
