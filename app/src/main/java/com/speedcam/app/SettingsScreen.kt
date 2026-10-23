package com.speedcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.speedcam.app.sync.DatabaseSyncEngine
import com.speedcam.app.ui.components.SettingsSectionCard
import com.speedcam.app.ui.components.SettingsSliderItem
import com.speedcam.app.ui.components.SettingsToggleItem
import com.speedcam.app.ui.components.SliderItemState
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.ui.theme.SpeedWarning
import com.speedcam.app.util.toRussianTitle
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.media.OutputMode
import com.speedcam.core.media.VoiceEngine
import com.speedcam.core.media.VoicePhraseStyle
import java.util.Locale

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
    val autoStartOnBluetooth: Boolean = false,
    val autoStopOnBluetooth: Boolean = true,
    val bluetoothDeviceFilter: String = "",
    val bluetoothDeviceFilterName: String = "",
    val bluetoothStopDelaySeconds: Int = 20,
    val pairedBluetoothDevices: List<Pair<String, String>> = emptyList(),
    val pipEnabled: Boolean = true,
    val pipAutoEnter: Boolean = true,
    val launcherBroadcastEnabled: Boolean = true,
    val headUnitAutoStart: Boolean = true,
    val wearCompanionEnabled: Boolean = true,
    val wearHapticProfile: String = "MOTORCYCLE_HIGH",
    val connectedWatchCount: Int = 0,
    val connectedWatchName: String = "",
    val updateUrl: String = "",
    val offlineTilesEnabled: Boolean = true,
    val customTileServerUrl: String = "",
    val tileApiKey: String = "",
    val activeMbTilesName: String? = null,
    val tileCacheStats: String = "",
    val voiceEngine: VoiceEngine = VoiceEngine.TTS,
    val voicePhraseStyle: VoicePhraseStyle = VoicePhraseStyle.FULL,
    val ttsSpeechRate: Float = 1.0f,
    val activeVoicePackId: String? = null,
    val activeVoicePackName: String? = null,
    val installedVoicePacks: List<Pair<String, String>> = emptyList(),
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
    val onVoiceEngineChange: (VoiceEngine) -> Unit = {},
    val onVoicePhraseStyleChange: (VoicePhraseStyle) -> Unit = {},
    val onTtsSpeechRateChange: (Float) -> Unit = {},
    val onSelectVoicePack: (String) -> Unit = {},
    val onImportVoicePack: () -> Unit = {},
    val onTestVoice: () -> Unit = {},
    val onAutoUpdateChange: (Boolean) -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onImportDatabaseFile: () -> Unit = {},
    val onRestoreBundledDatabase: () -> Unit = {},
    val onUpdateUrlChange: (String) -> Unit = {},
    val onSyncHazardOutbox: () -> Unit = {},
    val onClearSyncedReports: () -> Unit = {},
    val onContraCamDbChange: (Boolean) -> Unit = {},
    val onSyncContraCam: () -> Unit = {},
    val onAutoStartOnBluetoothChange: (Boolean) -> Unit = {},
    val onAutoStopOnBluetoothChange: (Boolean) -> Unit = {},
    val onBluetoothDeviceFilterChange: (address: String, name: String) -> Unit = { _, _ -> },
    val onBluetoothStopDelaySecondsChange: (Int) -> Unit = {},
    val onPipEnabledChange: (Boolean) -> Unit = {},
    val onPipAutoEnterChange: (Boolean) -> Unit = {},
    val onLauncherBroadcastEnabledChange: (Boolean) -> Unit = {},
    val onHeadUnitAutoStartChange: (Boolean) -> Unit = {},
    val onWearCompanionEnabledChange: (Boolean) -> Unit = {},
    val onWearHapticProfileChange: (String) -> Unit = {},
    val onTestWearVibration: () -> Unit = {},
    val onOfflineTilesEnabledChange: (Boolean) -> Unit = {},
    val onCustomTileServerUrlChange: (String) -> Unit = {},
    val onTileApiKeyChange: (String) -> Unit = {},
    val onImportMbTilesFile: () -> Unit = {},
    val onClearMapCache: () -> Unit = {},
    val onDisconnectMbTiles: () -> Unit = {},
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
        BluetoothAutomationSectionCard(state = state, actions = actions)
        AutomotiveIntegrationSectionCard(state = state, actions = actions)
        WearCompanionSectionCard(state = state, actions = actions)
        AudioSectionCard(state = state, actions = actions)
        HudSectionCard(state = state, actions = actions)
        PipSectionCard(state = state, actions = actions)
        DatabaseSyncSectionCard(state = state, actions = actions)
        OfflineMapSectionCard(state = state, actions = actions)
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
        title = "Радар-детектор и транспорт",
        icon = Icons.Default.Speed,
    ) {
        SettingsToggleItem(
            title = "Радар-детектор",
            subtitle = "Непрерывное отслеживание дорожных камер и опасностей по GPS",
            checked = state.enabled,
            onCheckedChange = actions.onEnabledChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Профиль оповещений", style = MaterialTheme.typography.titleSmall)
        PresetSelector(state.quickSetting, actions.onQuickSettingChange)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Тип транспорта", style = MaterialTheme.typography.titleSmall)
        TransportSelector(state.transport, actions.onTransportChange)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        val distanceLabel =
            if (state.captureDistanceKm <= 0.0) "Авто" else "%.2f км".format(state.captureDistanceKm)
        SettingsSliderItem(
            item =
                SliderItemState(
                    title = "Дистанция предупреждения",
                    valueLabel = distanceLabel,
                    value = state.captureDistanceKm.toFloat(),
                    valueRange = 0f..3f,
                    formatValueLabel = { v ->
                        if (v <= 0.05f) "Авто" else "%.2f км".format(v)
                    },
                ),
            onValueChange = {},
            onValueChangeFinished = { actions.onCaptureDistanceChange(it.toDouble()) },
        )
    }
}

@Composable
private fun AudioSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Звук и голосовые оповещения",
        icon = Icons.AutoMirrored.Filled.VolumeUp,
    ) {
        SettingsToggleItem(
            title = "Тишина во время звонка",
            subtitle = "Отключать голосовые и звуковые предупреждения во время телефонного разговора",
            checked = state.silentDuringCall,
            onCheckedChange = actions.onSilentDuringCallChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Движок голосовых оповещений", style = MaterialTheme.typography.titleSmall)
        Text(
            text =
                "Синтезатор речи (TTS) озвучивает любые типы фиксации («Контроль полосы ОТ», «Контроль светофора», «Разметка»). " +
                    "Аудио-пакеты воспроизводят записанные фразы с авто-переключением на TTS для отсутствующих записей.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VoiceEngineSelector(
            selected = state.voiceEngine,
            onChange = actions.onVoiceEngineChange,
        )

        if (state.voiceEngine != VoiceEngine.DISABLED) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text("Стиль предупреждений", style = MaterialTheme.typography.titleSmall)
            VoicePhraseStyleSelector(
                selected = state.voicePhraseStyle,
                onChange = actions.onVoicePhraseStyleChange,
            )
            val exampleText =
                if (state.voicePhraseStyle == VoicePhraseStyle.FULL) {
                    "«Внимание! Через 300 метров контроль светофора. Ограничение 60 километров в час»"
                } else {
                    "«Светофор 60, через 300 метров»"
                }
            Text(
                text = "Пример: $exampleText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSliderItem(
                item =
                    SliderItemState(
                        title = "Скорость речи TTS",
                        valueLabel = String.format(Locale.US, "%.2fx", state.ttsSpeechRate),
                        value = state.ttsSpeechRate,
                        valueRange = 0.8f..1.5f,
                        formatValueLabel = { String.format(Locale.US, "%.2fx", it) },
                    ),
                onValueChange = actions.onTtsSpeechRateChange,
                onValueChangeFinished = actions.onTtsSpeechRateChange,
            )

            if (state.voiceEngine == VoiceEngine.AUDIO_PACK) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                VoicePackStatusSurface(state = state, actions = actions)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            OutlinedButton(
                onClick = actions.onTestVoice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Проверить голос (Тест)")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Канал вывода звука", style = MaterialTheme.typography.titleSmall)
        Text(
            text =
                "Выберите источник воспроизведения звука. Bluetooth HFP прерывает радио магнитолы " +
                    "как входящий телефонный звонок.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AudioOutputSelector(
            selected = state.outputMode,
            onChange = actions.onOutputModeChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceEngineSelector(
    selected: VoiceEngine,
    onChange: (VoiceEngine) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VoiceEngine.entries.forEach { engine ->
            FilterChip(
                selected = engine == selected,
                onClick = { onChange(engine) },
                label = { Text(engine.toRussianTitle()) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePhraseStyleSelector(
    selected: VoicePhraseStyle,
    onChange: (VoicePhraseStyle) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VoicePhraseStyle.entries.forEach { style ->
            FilterChip(
                selected = style == selected,
                onClick = { onChange(style) },
                label = { Text(style.toRussianTitle()) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePackStatusSurface(
    state: SettingsState,
    actions: SettingsActions,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Локальные аудио-пакеты",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val currentPackText = state.activeVoicePackName ?: "Пакет не выбран (активен fallback на TTS)"
            Text(
                text = "Текущий пакет: $currentPackText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.installedVoicePacks.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.installedVoicePacks.forEach { (id, name) ->
                        FilterChip(
                            selected = id == state.activeVoicePackId,
                            onClick = { actions.onSelectVoicePack(id) },
                            label = { Text(name) },
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = actions.onImportVoicePack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импорт голосового архива (.zip)")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioOutputSelector(
    selected: OutputMode,
    onChange: (OutputMode) -> Unit,
) {
    val driverModes =
        listOf(
            OutputMode.STANDARD,
            OutputMode.ALWAYS_SPEAKER,
            OutputMode.BLUETOOTH_MULTIMEDIA,
            OutputMode.BLUETOOTH_HFP,
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        driverModes.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onChange(mode) },
                label = { Text(mode.toRussianTitle()) },
            )
        }
    }
}

@Composable
private fun BluetoothAutomationSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Автоматизация Bluetooth",
        icon = Icons.Default.DirectionsCar,
    ) {
        SettingsToggleItem(
            title = "Автостарт при подключении к авто",
            subtitle = "Автоматически запускать радар-детектор и HUD при подключении к автомобилю",
            checked = state.autoStartOnBluetooth,
            onCheckedChange = actions.onAutoStartOnBluetoothChange,
        )

        SettingsToggleItem(
            title = "Автостоп при отключении",
            subtitle = "Останавливать работу и скрывать HUD при отключении Bluetooth автомобиля",
            checked = state.autoStopOnBluetooth,
            onCheckedChange = actions.onAutoStopOnBluetoothChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        BluetoothGracePeriodSelector(
            selectedSeconds = state.bluetoothStopDelaySeconds,
            onChange = actions.onBluetoothStopDelaySecondsChange,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        BluetoothDeviceSelector(
            currentFilter = state.bluetoothDeviceFilter,
            pairedDevices = state.pairedBluetoothDevices,
            onChange = actions.onBluetoothDeviceFilterChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BluetoothGracePeriodSelector(
    selectedSeconds: Int,
    onChange: (Int) -> Unit,
) {
    Text("Задержка остановки при отключении", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Таймаут ожидания перед остановкой радара для защиты от перезапусков при пуске двигателя.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val delayOptions = listOf(0, 10, 20, 30, 60)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        delayOptions.forEach { seconds ->
            val label = if (seconds == 0) "Сразу (0с)" else "$seconds с"
            FilterChip(
                selected = selectedSeconds == seconds,
                onClick = { onChange(seconds) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BluetoothDeviceSelector(
    currentFilter: String,
    pairedDevices: List<Pair<String, String>>,
    onChange: (address: String, name: String) -> Unit,
) {
    Text("Выбор Bluetooth-устройства автомобиля", style = MaterialTheme.typography.titleSmall)
    Text(
        text = "Укажите магнитолу или гарнитуру автомобиля для триггера автозапуска.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = currentFilter.isBlank(),
            onClick = { onChange("", "") },
            label = { Text("Любая автомагнитола (по умолчанию)") },
        )
        pairedDevices.forEach { (address, name) ->
            FilterChip(
                selected = currentFilter.equals(address, ignoreCase = true),
                onClick = { onChange(address, name) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun AutomotiveIntegrationSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Автомагнитолы и Android Auto",
        icon = Icons.Default.DirectionsCar,
    ) {
        SettingsToggleItem(
            title = "Интеграция с авто-лаунчерами",
            subtitle = "Передавать текущую скорость и предупреждения в лаунчеры FCC, Agama, CarWebGuru и др.",
            checked = state.launcherBroadcastEnabled,
            onCheckedChange = actions.onLauncherBroadcastEnabledChange,
        )

        SettingsToggleItem(
            title = "Автозапуск по питанию (ACC)",
            subtitle = "Запуск при включении зажигания (ACC ON) и пауза при выключении (ACC OFF)",
            checked = state.headUnitAutoStart,
            onCheckedChange = actions.onHeadUnitAutoStartChange,
        )
    }
}

@Composable
private fun WearCompanionSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Часы Wear OS и тактильные вибросигналы",
        icon = Icons.Default.Watch,
    ) {
        SettingsToggleItem(
            title = "Синхронизация с Wear OS",
            subtitle = "Отправка скорости и тактильных вибросигналов на смарт-часы",
            checked = state.wearCompanionEnabled,
            onCheckedChange = actions.onWearCompanionEnabledChange,
        )

        WearWatchStatusRow(
            count = state.connectedWatchCount,
            name = state.connectedWatchName,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text("Тактильный профиль вибрации (Мотоцикл)", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Усиленные паттерны вибрации, ощутимые даже в плотных мотоперчатках.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WearHapticProfileSelector(
            selectedProfile = state.wearHapticProfile,
            onChange = actions.onWearHapticProfileChange,
        )

        OutlinedButton(
            onClick = actions.onTestWearVibration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Vibration,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Тест вибрации на часах")
        }
    }
}

@Composable
private fun WearWatchStatusRow(
    count: Int,
    name: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (count > 0) Color(0xFF10B981) else Color(0xFF64748B)),
            )
            val label =
                if (count > 0) {
                    "Подключено: $name (часов: $count)"
                } else {
                    "Часы Wear OS не подключены (или поиск)"
                }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WearHapticProfileSelector(
    selectedProfile: String,
    onChange: (String) -> Unit,
) {
    val profiles =
        listOf(
            "MOTORCYCLE_HIGH" to "Мото (Макс)",
            "STANDARD" to "Стандартный",
            "OVERLIMIT_ONLY" to "Только при превышении",
            "SILENT" to "Без вибрации",
        )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        profiles.forEach { (key, label) ->
            FilterChip(
                selected = selectedProfile == key,
                onClick = { onChange(key) },
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
        title = "Плавающий HUD (Виджет поверх окон)",
        icon = Icons.Default.Layers,
    ) {
        SettingsToggleItem(
            title = "Поверх других приложений",
            subtitle = "Отображать плавающий спидометр и камеры поверх навигационных приложений",
            checked = state.hudEnabled,
            onCheckedChange = actions.onHudEnabledChange,
        )

        SettingsToggleItem(
            title = "Снижать яркость на чистой дороге",
            subtitle = "Уменьшать прозрачность виджета при отсутствии опасностей и камер",
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
            Text("Сбросить позицию окна HUD")
        }
    }
}

@Composable
private fun PipSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Режим «Картинка в картинке» (PiP)",
        icon = Icons.Default.PictureInPicture,
    ) {
        SettingsToggleItem(
            title = "Спидометр «Картинка в картинке»",
            subtitle = "Компактное системное окно со спидометром во время использования других навигационных приложений",
            checked = state.pipEnabled,
            onCheckedChange = actions.onPipEnabledChange,
        )

        if (state.pipEnabled) {
            SettingsToggleItem(
                title = "Автопереход в PiP при сворачивании",
                subtitle = "Автоматически открывать компактный спидометр при возврате на главный экран",
                checked = state.pipAutoEnter,
                onCheckedChange = actions.onPipAutoEnterChange,
            )
        }
    }
}

@Composable
private fun DatabaseSyncSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "База данных камер и обновление",
        icon = Icons.Default.Storage,
    ) {
        DatabaseStatusSurface(state = state)

        SettingsToggleItem(
            title = "Еженедельное автообновление",
            subtitle = "Автоматически загружать свежую базу камер по Wi-Fi",
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
                Text("Проверка обновлений...")
            } else {
                Text("Проверить обновления онлайн")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = actions.onImportDatabaseFile,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импорт файла")
            }

            OutlinedButton(
                onClick = actions.onRestoreBundledDatabase,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сброс к APK")
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Зеркало обновлений (URL манифеста)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.updateUrl,
                onValueChange = actions.onUpdateUrlChange,
                placeholder = { Text(DatabaseSyncEngine.DEFAULT_MANIFEST_URL, maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.updateUrl.isBlank() || state.updateUrl == DatabaseSyncEngine.DEFAULT_MANIFEST_URL,
                    onClick = { actions.onUpdateUrlChange(DatabaseSyncEngine.DEFAULT_MANIFEST_URL) },
                    label = { Text("GitHub") },
                )
                FilterChip(
                    selected = state.updateUrl == DatabaseSyncEngine.KNOWN_MIRRORS.getOrNull(1),
                    onClick = {
                        DatabaseSyncEngine.KNOWN_MIRRORS.getOrNull(1)?.let(actions.onUpdateUrlChange)
                    },
                    label = { Text("jsDelivr CDN") },
                )
                if (state.updateUrl.isNotBlank() && state.updateUrl != DatabaseSyncEngine.DEFAULT_MANIFEST_URL) {
                    TextButton(onClick = { actions.onUpdateUrlChange("") }) {
                        Text("Сброс", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseStatusSurface(state: SettingsState) {
    val countsText =
        if (state.sectionCount > 0) {
            "Загружено: ${state.cameraCount} камер, ${state.sectionCount} участков КСС"
        } else {
            "Загружено: ${state.cameraCount} камер"
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
                    text = "Сборка базы: ${builtAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastUpdateCheckAt?.takeIf { it.isNotBlank() }?.let { checkedAt ->
                val checkLabel = checkedAt.take(16).replace("T", " ")
                Text(
                    text = "Последняя проверка: $checkLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastUpdateStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = "Статус: $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun OfflineMapSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "Оффлайн-картография (MBTiles и кэш)",
        icon = Icons.Default.Map,
    ) {
        SettingsToggleItem(
            title = "Оффлайн-подложка карты",
            subtitle = "Отображать векторные/растровые тайлы без задержек и без интернета",
            checked = state.offlineTilesEnabled,
            onCheckedChange = actions.onOfflineTilesEnabledChange,
        )

        OfflineMapStatusSurface(state)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = actions.onImportMbTilesFile,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импорт MBTiles")
            }

            OutlinedButton(
                onClick = actions.onClearMapCache,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Очистить кэш")
            }
        }

        if (state.activeMbTilesName != null) {
            OutlinedButton(
                onClick = actions.onDisconnectMbTiles,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Отключить MBTiles (${state.activeMbTilesName})")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "API-ключ тайл-сервера (опционально):",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.tileApiKey,
                onValueChange = actions.onTileApiKeyChange,
                placeholder = { Text("Опционально (Stadia, MapTiler, Carto)", maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Text(
                text =
                    "Встроенные зеркала OpenStreetMap работают бесплатно и не требуют API-ключа. " +
                        "Если вы используете сторонний платный тайл-сервер, укажите ключ здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Пользовательский URL тайлов:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.customTileServerUrl,
                onValueChange = actions.onCustomTileServerUrlChange,
                placeholder = { Text("По умолчанию: OpenStreetMap mirrors", maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OfflineMapStatusSurface(state: SettingsState) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val mbTilesTitle =
                if (state.activeMbTilesName != null) {
                    "MBTiles: ${state.activeMbTilesName}"
                } else {
                    "Архив MBTiles: не подключен (активен кэш)"
                }
            Text(
                text = mbTilesTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val cacheText =
                if (state.tileCacheStats.isNotBlank()) {
                    state.tileCacheStats
                } else {
                    "0.0 МБ (0 тайлов)"
                }
            Text(
                text = "Локальный кэш тайлов: $cacheText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Автономность: 100% готовность в тоннелях и на трассах без сети",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ContraCamSectionCard(
    state: SettingsState,
    actions: SettingsActions,
) {
    SettingsSectionCard(
        title = "База ContraCam (Бинарный архив)",
        icon = Icons.Default.CloudDownload,
    ) {
        SettingsToggleItem(
            title = "Использовать базу ContraCam",
            subtitle = "Фирменный бинарный архив камер вместо OSM/SpeedCam (авто-fallback на OSM при отсутствии архива)",
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
                Text("Загрузка и парсинг архива...")
            } else {
                Text(
                    if (state.contraCamCameraCount > 0) {
                        "Обновить архив ContraCam (${state.contraCamCameraCount} камер)"
                    } else {
                        "Загрузить архив ContraCam (HTTP)"
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
                    "Установлено ${state.contraCamCameraCount} камер ContraCam"
                } else {
                    "Архив еще не скачан (активен fallback на OSM)"
                }
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val modeText =
                if (state.useContraCamDb) {
                    "Активный источник камер: ContraCam"
                } else {
                    "Активный источник камер: Стандартный (OSM + SpeedCam)"
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
                    text = "Последнее обновление: $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastContraCamStatus?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = "Статус: $status",
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
        title = "Очередь сообщений о дорожных событиях",
        icon = Icons.Default.CloudSync,
    ) {
        Text(
            text =
                "Офлайн-очередь отметок. Отметки о камерах и опасностях сохраняются локально " +
                    "и отправляются в сеть при появлении подключения.",
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
                    Text("Отправка...")
                } else {
                    Text("Отправить отметки")
                }
            }

            OutlinedButton(
                onClick = actions.onClearSyncedReports,
                modifier = Modifier.weight(1f),
            ) {
                Text("Очистить отправленные")
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
                    text = "Ожидают отправки:",
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
                        text = "${state.pendingReportCount} отм.",
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
                    text = "Синхронизировано: ${syncAt.take(16).replace("T", " ")}",
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
        title = "О программе SpeedCam и правовая информация",
        icon = Icons.Default.Info,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_speedcam_lockup),
                contentDescription = "SpeedCam Logo",
                tint = Color.Unspecified,
                modifier = Modifier.height(42.dp).fillMaxWidth(0.75f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Автомобильный GPS радар-детектор и проекционный HUD • v0.1.0 (Релиз)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Text(
            text = dataSourceNote,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                "Совместимый движок ContraCam. Данные распространяются по лицензии " +
                    "Open Database License (ODbL 1.0).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSelector(
    selected: QuickSetting,
    onChange: (QuickSetting) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickSetting.entries.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onChange(preset) },
                label = { Text(preset.toRussianTitle()) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransportSelector(
    selected: TransportProfile,
    onChange: (TransportProfile) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TransportProfile.entries.forEach { profile ->
            FilterChip(
                selected = profile == selected,
                onClick = { onChange(profile) },
                label = { Text(profile.toRussianTitle()) },
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
                    text = "Требуется разрешение поверх окон",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SpeedDanger,
                )
            }
            Text(
                text =
                    "Для отображения плавающего спидометра поверх Яндекс.Навигатора или карт " +
                        "требуется предоставить системное разрешение.",
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
                Text("Предоставить разрешение")
            }
        }
    }
}
