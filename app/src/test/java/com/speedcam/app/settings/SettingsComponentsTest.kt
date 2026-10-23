package com.speedcam.app.settings

import com.speedcam.app.SettingsActions
import com.speedcam.app.SettingsState
import com.speedcam.app.ui.components.SliderItemState
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.media.OutputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsComponentsTest {
    @Test
    fun sliderItemState_dynamicLabelFormatting() {
        val slider =
            SliderItemState(
                title = "Warning Distance",
                valueLabel = "Auto",
                value = 0.0f,
                valueRange = 0f..3f,
                formatValueLabel = { v ->
                    if (v <= 0.05f) "Auto" else "%.2f km".format(v)
                },
            )

        assertEquals("Auto", slider.formatValueLabel?.invoke(0.0f))
        assertEquals("Auto", slider.formatValueLabel?.invoke(0.03f))
        assertEquals("1.25 km", slider.formatValueLabel?.invoke(1.25f))
        assertEquals("2.50 km", slider.formatValueLabel?.invoke(2.50f))
    }

    @Test
    fun sliderItemState_fallbackToStaticLabelWhenFormatterNull() {
        val slider =
            SliderItemState(
                title = "Volume",
                valueLabel = "80%",
                value = 0.8f,
            )

        val resolvedLabel = slider.formatValueLabel?.invoke(slider.value) ?: slider.valueLabel
        assertEquals("80%", resolvedLabel)
    }

    @Test
    fun settingsActions_dispatchCallbacksCorrectly() {
        var distanceCommitted: Double? = null
        var quickSettingCommitted: QuickSetting? = null
        var transportCommitted: TransportProfile? = null
        var enabledCommitted: Boolean? = null
        var outputModeCommitted: OutputMode? = null

        val actions =
            SettingsActions(
                onEnabledChange = { enabledCommitted = it },
                onQuickSettingChange = { quickSettingCommitted = it },
                onCaptureDistanceChange = { distanceCommitted = it },
                onTransportChange = { transportCommitted = it },
                onOutputModeChange = { outputModeCommitted = it },
            )

        actions.onEnabledChange(false)
        assertEquals(false, enabledCommitted)

        actions.onQuickSettingChange(QuickSetting.QUIET)
        assertEquals(QuickSetting.QUIET, quickSettingCommitted)

        actions.onCaptureDistanceChange(1.8)
        assertEquals(1.8, distanceCommitted!!, 0.001)

        actions.onTransportChange(TransportProfile.TRUCK)
        assertEquals(TransportProfile.TRUCK, transportCommitted)

        actions.onOutputModeChange(OutputMode.BLUETOOTH_HFP)
        assertEquals(OutputMode.BLUETOOTH_HFP, outputModeCommitted)
    }

    @Test
    fun settingsState_instantiationCompleteness() {
        val state =
            SettingsState(
                enabled = true,
                quickSetting = QuickSetting.MAXIMUM_ALERTS,
                captureDistanceKm = 1.5,
                transport = TransportProfile.MOTO,
                outputMode = OutputMode.ALWAYS_SPEAKER,
                silentDuringCall = true,
                cameraCount = 12000,
                dataSourceNote = "OpenStreetMap",
            )

        assertTrue(state.enabled)
        assertEquals(QuickSetting.MAXIMUM_ALERTS, state.quickSetting)
        assertEquals(1.5, state.captureDistanceKm, 0.001)
        assertEquals(TransportProfile.MOTO, state.transport)
        assertEquals(OutputMode.ALWAYS_SPEAKER, state.outputMode)
        assertTrue(state.silentDuringCall)
        assertEquals(12000, state.cameraCount)
    }
}
