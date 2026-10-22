package com.speedcam.app.car

import com.speedcam.app.pip.formatCameraKindLabel
import com.speedcam.app.pip.formatPipDistance
import com.speedcam.app.util.toRussianTitle
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.LatLon
import com.speedcam.core.geo.MapViewport
import com.speedcam.core.geo.MercatorProjection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedCamCarNavigationTest {
    @Test
    fun `zoom levels clamp within safe automotive limits`() {
        val state = CarMapViewportState()

        assertEquals(CarMapViewportState.DEFAULT_ZOOM, state.zoom)

        // Zoom out until min
        repeat(20) { state.zoomOut() }
        assertEquals(CarMapViewportState.MIN_ZOOM, state.zoom)

        // Zoom in until max
        repeat(30) { state.zoomIn() }
        assertEquals(CarMapViewportState.MAX_ZOOM, state.zoom)

        // Reset restores default
        state.resetCenter()
        assertEquals(CarMapViewportState.DEFAULT_ZOOM, state.zoom)
        assertFalse(state.isPanMode)
    }

    @Test
    fun `pan mode tracks manual offset and resets accurately`() {
        val state = CarMapViewportState()

        assertFalse(state.isPanMode)
        assertEquals(0f, state.panOffsetX)
        assertEquals(0f, state.panOffsetY)

        state.onScroll(45f, -30f)
        assertTrue(state.isPanMode)
        assertEquals(-45f, state.panOffsetX)
        assertEquals(30f, state.panOffsetY)

        state.resetCenter()
        assertFalse(state.isPanMode)
        assertEquals(0f, state.panOffsetX)
        assertEquals(0f, state.panOffsetY)
    }

    @Test
    fun `head unit viewport projects coordinates correctly on automotive display`() {
        val headUnitWidth = 1024.0
        val headUnitHeight = 600.0
        val center = LatLon(55.7558, 37.6173) // Moscow

        val viewport =
            MapViewport(
                center = center,
                zoom = 15.0,
                bearingDeg = 0.0,
                width = headUnitWidth,
                height = headUnitHeight,
            )

        val (projX, projY) = MercatorProjection.project(center, viewport)
        assertEquals(headUnitWidth / 2.0, projX, 1.0)
        assertEquals(headUnitHeight / 2.0, projY, 1.0)
    }

    @Test
    fun `navigation warning messages format properly for car driver`() {
        assertEquals("КАМЕРА", formatCameraKindLabel(CameraKind.FIXED))
        assertEquals("ЗАСАДА", formatCameraKindLabel(CameraKind.MOBILE))
        assertEquals("ПОЛОСА ОТ", formatCameraKindLabel(CameraKind.BUS_LANE))

        assertEquals("Камера скорости", CameraKind.FIXED.toRussianTitle())
        assertEquals("Мобильная засада", CameraKind.MOBILE.toRussianTitle())

        assertEquals("450 m", formatPipDistance(450.0, java.util.Locale.US))
        assertEquals("1.2 km", formatPipDistance(1200.0, java.util.Locale.US))
        assertEquals("1,2 км", formatPipDistance(1200.0, java.util.Locale.forLanguageTag("ru-RU")))
    }
}
