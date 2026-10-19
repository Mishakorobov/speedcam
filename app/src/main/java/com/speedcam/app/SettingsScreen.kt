package com.speedcam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile

/**
 * Everything the settings screen reads.
 *
 * A single object rather than seventeen parameters. Compose screens that take a value and a
 * setter per field end up with a signature nobody can hold in their head, and every new
 * setting makes it worse; grouping the reads separately from the writes keeps each side
 * legible.
 */
data class SettingsState(
    val enabled: Boolean,
    val quickSetting: QuickSetting,
    val captureDistanceKm: Double,
    val transport: TransportProfile,
    val hudEnabled: Boolean,
    val hudPermissionGranted: Boolean,
    val silentDuringCall: Boolean,
    val cameraCount: Int,
    val dataSourceNote: String,
)

/** The settings screen's writes, so a caller passes one callback rather than eight. */
data class SettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onQuickSettingChange: (QuickSetting) -> Unit = {},
    val onCaptureDistanceChange: (Double) -> Unit = {},
    val onTransportChange: (TransportProfile) -> Unit = {},
    val onHudEnabledChange: (Boolean) -> Unit = {},
    val onRequestHudPermission: () -> Unit = {},
    val onResetHudPosition: () -> Unit = {},
    val onSilentDuringCallChange: (Boolean) -> Unit = {},
)

/**
 * Settings.
 *
 * Covers what Plan.md §8.1 asks for — presets, capture distance, transport, data source —
 * plus the two things a driver needs before the HUD is usable: detection on, and the overlay
 * permission.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToggleRow("Detection", state.enabled, actions.onEnabledChange)

        HorizontalDivider()

        Text("Preset", style = MaterialTheme.typography.titleSmall)
        PresetRow(state.quickSetting, actions.onQuickSettingChange)

        HorizontalDivider()

        CaptureDistance(state.captureDistanceKm, actions.onCaptureDistanceChange)

        HorizontalDivider()

        Text("Vehicle", style = MaterialTheme.typography.titleSmall)
        TransportRow(state.transport, actions.onTransportChange)

        HorizontalDivider()

        ToggleRow("Floating HUD", state.hudEnabled, actions.onHudEnabledChange)
        OverlayPermission(state.hudPermissionGranted, actions)

        HorizontalDivider()

        ToggleRow("Silent during a call", state.silentDuringCall, actions.onSilentDuringCallChange)

        HorizontalDivider()

        Text("Data source", style = MaterialTheme.typography.titleSmall)
        Text(text = state.dataSourceNote, style = MaterialTheme.typography.bodySmall)
        Text(text = "${state.cameraCount} cameras loaded", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PresetRow(
    selected: QuickSetting,
    onChange: (QuickSetting) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(QuickSetting.entries.toList()) { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onChange(preset) },
                label = { Text(preset.displayName) },
            )
        }
    }
}

@Composable
private fun TransportRow(
    selected: TransportProfile,
    onChange: (TransportProfile) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(TransportProfile.entries.toList()) { profile ->
            FilterChip(
                selected = profile == selected,
                onClick = { onChange(profile) },
                label = { Text(profile.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Composable
private fun CaptureDistance(
    value: Double,
    onChange: (Double) -> Unit,
) {
    val label = if (value <= 0.0) "Capture distance: Auto" else "Capture distance: %.2f km".format(value)
    Text(text = label, style = MaterialTheme.typography.titleSmall)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toDouble()) },
        valueRange = 0f..3f,
    )
}

@Composable
private fun OverlayPermission(
    granted: Boolean,
    actions: SettingsActions,
) {
    if (granted) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "The overlay needs permission to draw over other apps.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onRequestHudPermission) { Text("Grant") }
            OutlinedButton(onClick = actions.onResetHudPosition) { Text("Reset position") }
        }
    }
}
