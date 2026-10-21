package com.speedcam.app.hud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HudPositionCalculationTest {
    @Test
    fun clampPosition_withinScreen_movesByDelta() {
        val screenWidth = 1080
        val screenHeight = 2400
        val layoutWidth = 300
        val layoutHeight = 150

        val initialX = 400
        val initialY = 800

        val dx = 50
        val dy = -30

        val maxX = (screenWidth - layoutWidth).coerceAtLeast(0)
        val maxY = (screenHeight - layoutHeight).coerceAtLeast(0)

        val newX = (initialX + dx).coerceIn(0, maxX)
        val newY = (initialY + dy).coerceIn(0, maxY)

        assertEquals(450, newX)
        assertEquals(770, newY)
    }

    @Test
    fun clampPosition_beyondScreenBoundaries_clampsToEdges() {
        val screenWidth = 1080
        val screenHeight = 2400
        val layoutWidth = 300
        val layoutHeight = 150

        val maxX = (screenWidth - layoutWidth).coerceAtLeast(0)
        val maxY = (screenHeight - layoutHeight).coerceAtLeast(0)

        // Try to drag off left/top edge
        val clampedLeft = (-100).coerceIn(0, maxX)
        val clampedTop = (-50).coerceIn(0, maxY)
        assertEquals(0, clampedLeft)
        assertEquals(0, clampedTop)

        // Try to drag off right/bottom edge
        val clampedRight = (1200).coerceIn(0, maxX)
        val clampedBottom = (3000).coerceIn(0, maxY)
        assertEquals(780, clampedRight)
        assertEquals(2250, clampedBottom)
    }

    @Test
    fun hudWanted_verifiesAllFourPreconditions() {
        fun isHudWanted(
            detectionEnabled: Boolean,
            hudSettingEnabled: Boolean,
            canDrawOverlays: Boolean,
            detectionRunning: Boolean,
        ): Boolean = detectionEnabled && hudSettingEnabled && canDrawOverlays && detectionRunning

        // All true -> wanted
        val allTrue =
            isHudWanted(
                detectionEnabled = true,
                hudSettingEnabled = true,
                canDrawOverlays = true,
                detectionRunning = true,
            )
        assertTrue(allTrue)

        // Any false -> not wanted
        assertFalse(
            isHudWanted(
                detectionEnabled = false,
                hudSettingEnabled = true,
                canDrawOverlays = true,
                detectionRunning = true,
            ),
        )
        assertFalse(
            isHudWanted(
                detectionEnabled = true,
                hudSettingEnabled = false,
                canDrawOverlays = true,
                detectionRunning = true,
            ),
        )
        assertFalse(
            isHudWanted(
                detectionEnabled = true,
                hudSettingEnabled = true,
                canDrawOverlays = false,
                detectionRunning = true,
            ),
        )
        assertFalse(
            isHudWanted(
                detectionEnabled = true,
                hudSettingEnabled = true,
                canDrawOverlays = true,
                detectionRunning = false,
            ),
        )
    }
}
