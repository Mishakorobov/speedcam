package com.speedcam.app.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class SpeedCamWidgetFormatterTest {
    @Test
    fun `formatQuickSpeed returns ON text when speed is 2 or less`() {
        val stationary =
            SpeedCamWidgetFormatter.formatQuickSpeed(
                speedKmh = 0.0,
                defaultOnText = "ON",
                unitText = "km/h",
            )
        assertEquals("ON", stationary)

        val creeping =
            SpeedCamWidgetFormatter.formatQuickSpeed(
                speedKmh = 1.9,
                defaultOnText = "ON",
                unitText = "km/h",
            )
        assertEquals("ON", creeping)
    }

    @Test
    fun `formatQuickSpeed formats speed rounded when speed exceeds 2`() {
        val moving =
            SpeedCamWidgetFormatter.formatQuickSpeed(
                speedKmh = 59.6,
                defaultOnText = "ON",
                unitText = "km/h",
            )
        assertEquals("60 km/h", moving)

        val highway =
            SpeedCamWidgetFormatter.formatQuickSpeed(
                speedKmh = 110.2,
                defaultOnText = "ON",
                unitText = "km/h",
            )
        assertEquals("110 km/h", highway)
    }

    @Test
    fun `formatDistance returns meters for distances below 1000m`() {
        assertEquals("350 m", SpeedCamWidgetFormatter.formatDistance(350.2, Locale.US))
        assertEquals("50 m", SpeedCamWidgetFormatter.formatDistance(49.8, Locale.US))
        assertEquals("999 m", SpeedCamWidgetFormatter.formatDistance(999.0, Locale.US))
    }

    @Test
    fun `formatDistance returns kilometers for distances 1000m and above`() {
        assertEquals("1.0 km", SpeedCamWidgetFormatter.formatDistance(1000.0, Locale.US))
        assertEquals("1.5 km", SpeedCamWidgetFormatter.formatDistance(1480.0, Locale.US))
        assertEquals("2.3 km", SpeedCamWidgetFormatter.formatDistance(2340.0, Locale.US))
    }

    @Test
    fun `resolveHazardColor returns danger color when overLimit is true`() {
        val color = SpeedCamWidgetFormatter.resolveHazardColor(overLimit = true)
        assertEquals(SpeedCamWidgetFormatter.COLOR_DANGER, color)
    }

    @Test
    fun `resolveHazardColor returns primary text color when within speed limit`() {
        val color = SpeedCamWidgetFormatter.resolveHazardColor(overLimit = false)
        assertEquals(SpeedCamWidgetFormatter.COLOR_TEXT_PRIMARY, color)
    }

    @Test
    fun `toggle action constant matches expected format`() {
        assertEquals(
            "com.speedcam.app.widget.ACTION_TOGGLE_DETECTION",
            SpeedCamWidgetManager.ACTION_TOGGLE_DETECTION,
        )
    }
}
