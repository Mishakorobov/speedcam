package com.speedcam.app.pip

import androidx.compose.ui.graphics.Color
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.core.detect.CameraKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class PipHelperTest {
    @Test
    fun `aspect ratio constants are 16 by 9`() {
        assertEquals(16, PipHelper.ASPECT_RATIO_NUMERATOR)
        assertEquals(9, PipHelper.ASPECT_RATIO_DENOMINATOR)
    }

    @Test
    fun `action constants match expected action strings`() {
        assertEquals("com.speedcam.app.pip.ACTION_PIP_MUTE", PipHelper.ACTION_PIP_MUTE)
        assertEquals("com.speedcam.app.pip.ACTION_PIP_TOGGLE", PipHelper.ACTION_PIP_TOGGLE)
    }

    @Test
    fun `formatCameraKindLabel maps camera kinds correctly`() {
        assertEquals("СВЕТОФОР", formatCameraKindLabel(CameraKind.RED_LIGHT))
        assertEquals("НАЧАЛО КСС", formatCameraKindLabel(CameraKind.AVERAGE_ENTRY))
        assertEquals("КОНЕЦ КСС", formatCameraKindLabel(CameraKind.AVERAGE_EXIT))
        assertEquals("ЗАСАДА", formatCameraKindLabel(CameraKind.MOBILE))
        assertEquals("КОМПЛЕКС", formatCameraKindLabel(CameraKind.COMPLEX))
        assertEquals("МУЛЯЖ", formatCameraKindLabel(CameraKind.DUMMY))
        assertEquals("КАМЕРА", formatCameraKindLabel(CameraKind.FIXED))
        assertEquals("ОПАСНОСТЬ", formatCameraKindLabel(CameraKind.UNKNOWN))
    }

    @Test
    fun `formatPipDistance formats distances under 1km in meters`() {
        assertEquals("250 m", formatPipDistance(250.0, Locale.US))
        assertEquals("999 m", formatPipDistance(999.4, Locale.US))
    }

    @Test
    fun `formatPipDistance formats distances 1km and above in kilometers`() {
        assertEquals("1.0 km", formatPipDistance(1000.0, Locale.US))
        assertEquals("1.4 km", formatPipDistance(1380.0, Locale.US))
        assertEquals("2.5 km", formatPipDistance(2510.0, Locale.US))
    }

    @Test
    fun `resolvePipSpeedColor resolves danger when speeding`() {
        val color =
            resolvePipSpeedColor(
                overLimit = true,
                hasHazard = true,
                running = true,
            )
        assertEquals(SpeedDanger, color)
    }

    @Test
    fun `resolvePipSpeedColor resolves warning when approaching hazard within limit`() {
        val color =
            resolvePipSpeedColor(
                overLimit = false,
                hasHazard = true,
                running = true,
            )
        assertEquals(SpeedWarning, color)
    }

    @Test
    fun `resolvePipSpeedColor resolves white when road is clear and running`() {
        val color =
            resolvePipSpeedColor(
                overLimit = false,
                hasHazard = false,
                running = true,
            )
        assertEquals(Color.White, color)
    }

    @Test
    fun `resolvePipSpeedColor resolves muted slate when stopped`() {
        val color =
            resolvePipSpeedColor(
                overLimit = false,
                hasHazard = false,
                running = false,
            )
        assertEquals(Color(0xFF94A3B8), color)
    }
}
