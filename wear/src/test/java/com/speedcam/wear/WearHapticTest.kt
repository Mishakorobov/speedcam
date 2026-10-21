package com.speedcam.wear

import com.speedcam.wear.haptics.MotorcycleHapticProfile
import com.speedcam.wear.haptics.WearHapticController
import com.speedcam.wear.model.WearTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearHapticTest {
    @Test
    fun `wear telemetry parses json correctly`() {
        val original =
            WearTelemetry(
                speedKmh = 92,
                speedLimitKmh = 80,
                overLimit = true,
                nearestDistanceM = 450,
                nearestKind = "AVERAGE_ENTRY",
                warningLevel = 2,
                running = true,
                hasFix = true,
            )

        val json = original.toJson()
        val parsed = WearTelemetry.fromJson(json)

        assertEquals(92, parsed.speedKmh)
        assertEquals(80, parsed.speedLimitKmh)
        assertTrue(parsed.overLimit)
        assertEquals(450, parsed.nearestDistanceM)
        assertEquals("AVERAGE_ENTRY", parsed.nearestKind)
        assertEquals(2, parsed.warningLevel)
        assertTrue(parsed.running)
        assertTrue(parsed.hasFix)
    }

    @Test
    fun `wear telemetry defaults empty json safely`() {
        val empty = WearTelemetry.fromJson("{}")
        assertEquals(0, empty.speedKmh)
        assertNull(empty.speedLimitKmh)
        assertFalse(empty.overLimit)
        assertNull(empty.nearestDistanceM)
        assertEquals(0, empty.warningLevel)
        assertFalse(empty.running)
    }

    @Test
    fun `distance milestone calculation aligns with motorcycle alerts`() {
        assertEquals(100, WearHapticController.getMilestone(50))
        assertEquals(100, WearHapticController.getMilestone(120))
        assertEquals(250, WearHapticController.getMilestone(180))
        assertEquals(250, WearHapticController.getMilestone(300))
        assertEquals(500, WearHapticController.getMilestone(450))
        assertEquals(500, WearHapticController.getMilestone(600))
        assertNull(WearHapticController.getMilestone(800))
        assertNull(WearHapticController.getMilestone(1500))
    }

    @Test
    fun `motorcycle haptic profiles define distinct options`() {
        val profiles = MotorcycleHapticProfile.values()
        assertEquals(4, profiles.size)
        assertTrue(profiles.contains(MotorcycleHapticProfile.MOTORCYCLE_HIGH))
        assertTrue(profiles.contains(MotorcycleHapticProfile.STANDARD))
        assertTrue(profiles.contains(MotorcycleHapticProfile.OVERLIMIT_ONLY))
        assertTrue(profiles.contains(MotorcycleHapticProfile.SILENT))
    }
}
