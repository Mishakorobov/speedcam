package com.speedcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.speedcam.app.ui.components.SettingsSectionCard
import com.speedcam.app.ui.components.SettingsSliderItem
import com.speedcam.app.ui.components.SettingsToggleItem
import com.speedcam.app.ui.components.SliderItemState
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.media.OutputMode

/**
 * Everything the settings screen reads.
 */
data class SettingsState(
    val enabled: Boolean,
    val quickSetting: QuickSetting,
    val captureDistanceKm: Double,
    val transport: TransportProfile,
    val hudEnabled: Boolean,
    val hudAutoHide: Boolean = false,
    val hudPermissionGranted: Boolean,
    val outputMode: OutputMode = OutputMode.STANDARD,
    val silentDuringCall: Boolean,
    val cameraCount: Int,
    val sectionCount: Int = 0,
    val databaseBuiltAt: String? = null,
    val dataSourceNote: String,
    val autoUpdateEnabled: Boolean = true,
    val lastUpdateCheckAt: String? = null,
    val lastUpdateStatus: String? = null,
    val isUpdating: Boolean = false,
    val pendingReportCount: Int = 0,
    val lastHazardSyncAt: String? = null,
    val lastHazardSyncStatus: String? = null,
    val isSyncingReports: Boolean = false,
    val useContraCamDb: Boolean = false,
    val lastContraCamSyncAt: String? = null,
    val lastContraCamStatus: String? = null,
    val contraCamCameraCount: Int = 0,
    val isContraCamSyncing: Boolean = false,
)

/** The settings screen's writes, bundled for reviewability. */
data class SettingsActions(
    val onEnabledChange: (Boolean) -> Unit = {},
    val onQuickSettingChange: (QuickSetting) -> Unit = {},
    val onCaptureDistanceChange: (Double) -> Unit = {},
    val onTransportChange: (TransportProfile) -> Unit = {},
    val onHudEnabledChange: (Boolean) -> Unit = {},
    val onHudAutoHideChange: (Boolean) -> Unit = {},
    val onRequestHudPermission: () -> Unit = {},
    val onResetHudPosition: () -> Unit = {},
    val onOutputModeChange: (OutputMode) -> Unit = {},
    val onSilentDuringCallChange: (Boolean) -> Unit = {},
    val onAutoUpdateChange: (Boolean) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onSyncHazardOutbox: () -> Unit = {},
    val onClearSyncedReports: () -> Unit = {},
    val onContraCamDbChange: (Boolean) -> Unit = {},
    val onSyncContraCam: () -> Unit = {},
)

/**
 * Modernized Settings Screen with grouped design cards, high visual hierarchy,
 * WCAG AA accessible touch targets, and clear descriptions.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetectionSectionCard(state = state, actions = actions)
        AudioSectionCard(state = state, actions = actions)
        HudSectionCard(state = state, actions = actions)
        DatabaseSyncSectionCard(state = state, actions = actions)
        ContraCamSectionCard(state = state, actions = actions)
        HazardOutboxSectionCard(state = state, actions = actions)
        AttributionSectionCard(dataSourceNote = state.dataSourceNote)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DetectionSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Radar Detection & Vehicle",
        icon = Icons.Default.Speed,
    ) {
        SettingsToggleItem(
            title = "Radar Detection",
            subtitle = "Continuously scan for speed cameras and hazards via GPS",
            checked = state.enabled,
            onCheckedChange = actions.onEnabledChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Detection Profile", style = MaterialTheme.typography.titleSmall)
        PresetSelector(state.quickSetting, actions.onQuickSettingChange)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Vehicle Type", style = MaterialTheme.typography.titleSmall)
        TransportSelector(state.transport, actions.onTransportChange)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val distanceLabel =
            if (state.captureDistanceKm <= 0.0) "Auto" else "%.2f km".format(state.captureDistanceKm)
        SettingsSliderItem(
            item =
                SliderItemState(
                    title = "Warning Distance",
                    valueLabel = distanceLabel,
                    value = state.captureDistanceKm.toFloat(),
                    valueRange = 0f..3f,
                ),
            onValueChange = { actions.onCaptureDistanceChange(it.toDouble()) },
        )
    }
}

@Composable
private fun AudioSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Audio & Alert Behavior",
        icon = Icons.AutoMirrored.Filled.VolumeUp,
    ) {
        SettingsToggleItem(
            title = "Silent During Calls",
            subtitle = "Suppress voice and beeping alerts when phone call is in progress",
            checked = state.silentDuringCall,
            onCheckedChange = actions.onSilentDuringCallChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Audio Output Channel", style = MaterialTheme.typography.titleSmall)
        Text(
            text =
                "Choose where alerts are played. Bluetooth HFP interrupts car radio " +
                    "like an incoming phone call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AudioOutputSelector(
            selected = state.outputMode,
            onChange = actions.onOutputModeChange,
        )
    }
}

@Composable
private fun AudioOutputSelector(
    selected: OutputMode,
    onChange: (OutputMode) -> Unit,
) {
    val modes =
        listOf(
            OutputMode.STANDARD to "Auto / Media",
            OutputMode.ALWAYS_SPEAKER to "Speaker",
            OutputMode.BLUETOOTH_MULTIMEDIA to "BT Media",
            OutputMode.BLUETOOTH_HFP to "BT Hands-Free (HFP)",
        )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(modes) { (mode, label) ->
            FilterChip(
                selected = mode == selected,
                onClick = { onChange(mode) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HudSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Floating HUD (Overlay)",
        icon = Icons.Default.Layers,
    ) {
        SettingsToggleItem(
            title = "Show Over Other Apps",
            subtitle = "Displays floating speedometer and radar warnings over navigation apps",
            checked = state.hudEnabled,
            onCheckedChange = actions.onHudEnabledChange,
        )

        SettingsToggleItem(
            title = "Dim on Clear Road",
            subtitle = "Reduces HUD opacity when no hazards or cameras are nearby",
            checked = state.hudAutoHide,
            onCheckedChange = actions.onHudAutoHideChange,
        )

        if (!state.hudPermissionGranted) {
            Spacer(modifier = Modifier.height(4.dp))
            HudPermissionWarningCard(onRequestPermission = actions.onRequestHudPermission)
        }

        OutlinedButton(
            onClick = actions.onResetHudPosition,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reset HUD Window Position")
        }
    }
}

@Composable
private fun DatabaseSyncSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Camera Database & Sync",
        icon = Icons.Default.Storage,
    ) {
        DatabaseStatusSurface(state = state)

        SettingsToggleItem(
            title = "Weekly Auto-Update",
            subtitle = "Automatically download fresh camera databases over Wi-Fi",
            checked = state.autoUpdateEnabled,
            onCheckedChange = actions.onAutoUpdateChange,
        )

        Button(
            onClick = actions.onCheckForUpdates,
            enabled = !state.isUpdating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Checking for updates...")
            } else {
                Text("Check for Database Updates Now")
            }
        }
    }
}

@Composable
private fun DatabaseStatusSurface(state: SettingsState) {
    val countsText =
        if (state.sectionCount > 0) {
            "${state.cameraCount} speed cameras, ${state.sectionCount} SPECS corridors loaded"
        } else {
            "${state.cameraCount} speed cameras loaded"
        }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = countsText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            state.databaseBuiltAt?.takeIf { it.isNotBlank() }?.let { builtAt ->
                Text(
                    text = "Database build: ${builtAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastUpdateCheckAt?.takeIf { it.isNotBlank() }?.let { checkedAt ->
                val checkLabel = checkedAt.take(16).replace("T", " ")
                Text(
                    text = "Last checked: $checkLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastUpdateStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = "Status: $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ContraCamSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "ContraCam Database (Binary Archive)",
        icon = Icons.Default.CloudDownload,
    ) {
        SettingsToggleItem(
            title = "Use ContraCam Binary Archive",
            subtitle = "Download and use proprietary ContraCam map.dat archives instead of OSM/SpeedCam",
            checked = state.useContraCamDb,
            onCheckedChange = actions.onContraCamDbChange,
        )

        ContraCamStatusSurface(state = state)

        Button(
            onClick = actions.onSyncContraCam,
            enabled = !state.isContraCamSyncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isContraCamSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Downloading & Parsing Archive...")
            } else {
                Text(
                    if (state.contraCamCameraCount > 0) {
                        "Update ContraCam Archive (HTTP)"
                    } else {
                        "Download ContraCam Archive (HTTP)"
                    },
                )
            }
        }
    }
}

@Composable
private fun ContraCamStatusSurface(state: SettingsState) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val statusText =
                if (state.contraCamCameraCount > 0) {
                    "${state.contraCamCameraCount} ContraCam cameras installed"
                } else {
                    "Archive not installed yet"
                }
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val modeText =
                if (state.useContraCamDb) {
                    "Active detection source: ContraCam"
                } else {
                    "Active detection source: Standard (OSM + SpeedCam)"
                }
            Text(
                text = modeText,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.useContraCamDb) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            state.lastContraCamSyncAt?.takeIf { it.isNotBlank() }?.let { syncAt ->
                val label = syncAt.take(16).replace("T", " ")
                Text(
                    text = "Last updated: $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastContraCamStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = "Status: $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HazardOutboxSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Crowdsourced Hazard Outbox",
        icon = Icons.Default.CloudSync,
    ) {
        Text(
            text =
                "Offline-first hazard queue. Reports submitted while driving are stored locally " +
                    "and synced when an internet connection becomes available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HazardStatusSurface(state = state)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = actions.onSyncHazardOutbox,
                enabled = !state.isSyncingReports,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isSyncingReports) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Syncing...")
                } else {
                    Text("Sync Outbox")
                }
            }

            OutlinedButton(
                onClick = actions.onClearSyncedReports,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Synced")
            }
        }
    }
}

@Composable
private fun HazardStatusSurface(state: SettingsState) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Pending in outbox:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color =
                        if (state.pendingReportCount > 0) {
                            SpeedWarning
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ) {
                    Text(
                        text = "${state.pendingReportCount} report(s)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (state.pendingReportCount > 0) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            state.lastHazardSyncAt?.takeIf { it.isNotBlank() }?.let { syncAt ->
                Text(
                    text = "Last synced: ${syncAt.take(16).replace("T", " ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttributionSectionCard(dataSourceNote: String) {
    SettingsSectionCard(
        title = "Attribution & Legal",
        icon = Icons.Default.Info,
    ) {
        Text(
            text = dataSourceNote,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                "ContraCam clean-room engine. Data distributed under " +
                    "the Open Database License (ODbL 1.0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetSelector(
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
private fun TransportSelector(
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
private fun HudPermissionWarningCard(onRequestPermission: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF261217)),
        border = androidx.compose.foundation.BorderStroke(1.dp, SpeedDanger.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = SpeedDanger,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Overlay Permission Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SpeedDanger,
                )
            }
            Text(
                text =
                    "Android requires explicit permission to draw the floating HUD widget " +
                        "over Google Maps or Yandex Navigator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Button(
                onClick = onRequestPermission,
                colors =
                    androidx.compose.material3.ButtonDefaults
                        .buttonColors(containerColor = SpeedDanger),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant Overlay Permission")
            }
        }
    }
}
