package com.speedcam.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.hud.HudOverlay
import com.speedcam.app.hud.HudOverlayManager
import com.speedcam.app.map.MapCenteringHelper
import com.speedcam.app.map.MapFollowMode
import com.speedcam.app.map.MapThemeMode
import com.speedcam.app.map.RadarMapCard
import com.speedcam.app.map.VectorMapActions
import com.speedcam.app.map.VectorMapData
import com.speedcam.app.map.VectorMapScreen
import com.speedcam.app.map.VectorMapState
import com.speedcam.app.pip.PipHelper
import com.speedcam.app.pip.PipSpeedometerContent
import com.speedcam.app.sync.DatabaseImportHelper
import com.speedcam.app.sync.DatabaseSyncEngine
import com.speedcam.app.sync.DatabaseSyncWorker
import com.speedcam.app.sync.HazardReportSyncWorker
import com.speedcam.app.sync.SyncResult
import com.speedcam.app.ui.components.EmptyHazardCard
import com.speedcam.app.ui.components.PrimaryHazardAlertCard
import com.speedcam.app.ui.components.SecondaryHazardCard
import com.speedcam.app.ui.components.SpecsSectionAlertCard
import com.speedcam.app.ui.components.SpeedometerActions
import com.speedcam.app.ui.components.SpeedometerCluster
import com.speedcam.app.ui.theme.BrandPrimaryDark
import com.speedcam.app.ui.theme.SpeedCamTheme
import com.speedcam.app.ui.theme.SpeedSafe
import com.speedcam.core.data.CameraStore
import com.speedcam.core.data.HazardReportEntity
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.data.contracam.ContraCamArchiveSync
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraSource
import com.speedcam.core.detect.QuickSetting
import com.speedcam.core.detect.SectionRecord
import com.speedcam.core.detect.TransportProfile
import com.speedcam.core.geo.LatLon
import com.speedcam.core.media.OutputMode
import com.speedcam.core.media.VoiceAlertCoordinator
import com.speedcam.core.media.VoiceEngine
import com.speedcam.core.media.VoicePackManager
import com.speedcam.core.media.VoicePhraseStyle
import com.speedcam.service.CarBluetoothManager
import com.speedcam.service.DetectionState
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import com.speedcam.service.wear.WearCompanionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole interface: status, the camera list, and settings.
 *
 * Stage 4 needed a service start button here because a `location`-typed foreground service
 * cannot be started from outside the app on Android 14+. What Stage 6 adds is the
 * driver-facing part — and the HUD is deliberately *not* one of these tabs, because it is a
 * separate `TYPE_APPLICATION_OVERLAY` window that outlives this activity.
 */
class MainActivity : ComponentActivity() {
    private val profiles by lazy { ProfileStore(this) }
    private var store: CameraStore? = null

    private val cameras = MutableStateFlow<List<Camera>>(emptyList())
    private val blocked = MutableStateFlow<List<LatLon>>(emptyList())
    private val sections = MutableStateFlow<List<SectionRecord>>(emptyList())

    private var isContraCamSyncing by mutableStateOf(false)
    private var useContraCamDb by mutableStateOf(false)
    private var autoStartOnBluetooth by mutableStateOf(false)
    private var autoStopOnBluetooth by mutableStateOf(true)
    private var bluetoothDeviceFilter by mutableStateOf("")
    private var bluetoothDeviceFilterName by mutableStateOf("")
    private var bluetoothStopDelaySeconds by mutableStateOf(20)
    private var isInPipMode by mutableStateOf(false)
    private var pipEnabled by mutableStateOf(true)
    private var pipAutoEnter by mutableStateOf(true)
    private var launcherBroadcastEnabled by mutableStateOf(true)
    private var headUnitAutoStart by mutableStateOf(true)
    private var connectedWatchCount by mutableStateOf(0)
    private var connectedWatchName by mutableStateOf("")
    private var detectionRunning by mutableStateOf(true)
    private var quickSetting by mutableStateOf(QuickSetting.MAXIMUM_ALERTS)
    private var captureDistanceKm by mutableDoubleStateOf(0.0)
    private var transport by mutableStateOf(TransportProfile.COMMON)
    private var hudEnabled by mutableStateOf(true)
    private var hudAutoHide by mutableStateOf(false)
    private var outputMode by mutableStateOf(OutputMode.STANDARD)
    private var silentDuringCall by mutableStateOf(false)
    private var autoUpdateEnabled by mutableStateOf(true)
    private var updateUrl by mutableStateOf("")
    private var isManualUpdating by mutableStateOf(false)
    private var wearCompanionEnabled by mutableStateOf(true)
    private var wearHapticProfile by mutableStateOf("MOTORCYCLE_HIGH")
    private var offlineTilesEnabled by mutableStateOf(true)
    private var activeMbTilesName by mutableStateOf<String?>(null)
    private var tileCacheStats by mutableStateOf("")
    private var voiceEngine by mutableStateOf(VoiceEngine.TTS)
    private var voicePhraseStyle by mutableStateOf(VoicePhraseStyle.FULL)
    private var ttsSpeechRate by mutableFloatStateOf(1.0f)
    private var activeVoicePackId by mutableStateOf<String?>(null)
    private var activeVoicePackName by mutableStateOf<String?>(null)
    private var installedVoicePacks by mutableStateOf<List<Pair<String, String>>>(emptyList())

    private val voicePackManager by lazy { VoicePackManager(this) }
    private val voiceAlertCoordinator by lazy { VoiceAlertCoordinator(this) }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importLocalDatabaseFile(uri)
            }
        }

    private val mbTilesPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importMbTilesFile(uri)
            }
        }

    private val voicePackPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                importVoicePackZip(uri)
            }
        }

    private val pipActionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    PipHelper.ACTION_PIP_MUTE -> HudOverlayManager.muteNearest(this@MainActivity)
                    PipHelper.ACTION_PIP_TOGGLE -> setDetectionEnabled(!DetectionState.state.value.running)
                }
            }
        }

    /** Tracks the fine-location grant, which background location cannot be requested without. */
    private var fineLocationGranted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            fineLocationGranted =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            continueStartup()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            HudOverlayManager.sync(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isInPipMode = isInPictureInPictureMode
        pipEnabled = profiles.pipEnabled
        pipAutoEnter = profiles.pipAutoEnter
        launcherBroadcastEnabled = profiles.launcherBroadcastEnabled
        headUnitAutoStart = profiles.headUnitAutoStart
        useContraCamDb = profiles.useContraCamDb
        autoStartOnBluetooth = profiles.autoStartOnBluetooth
        autoStopOnBluetooth = profiles.autoStopOnBluetooth
        bluetoothDeviceFilter = profiles.bluetoothDeviceFilter
        bluetoothDeviceFilterName = profiles.bluetoothDeviceFilterName
        bluetoothStopDelaySeconds = profiles.bluetoothStopDelaySeconds
        detectionRunning = profiles.isEnabled
        quickSetting = profiles.quickSetting
        captureDistanceKm = profiles.captureDistanceKm
        transport = profiles.transport
        hudEnabled = profiles.hudEnabled
        hudAutoHide = profiles.hudAutoHide
        outputMode = profiles.outputMode
        silentDuringCall = profiles.silentDuringCall
        autoUpdateEnabled = profiles.autoUpdateEnabled
        updateUrl = profiles.updateUrl
        wearCompanionEnabled = profiles.wearCompanionEnabled
        wearHapticProfile = profiles.wearHapticProfile
        voiceEngine = profiles.voiceEngine
        voicePhraseStyle = profiles.voicePhraseStyle
        ttsSpeechRate = profiles.ttsSpeechRate
        activeVoicePackId = profiles.activeVoicePackId
        refreshVoicePacks()
        refreshOfflineMapStats()
        refreshCameraList()
        requestLocationThenStart()

        setContent {
            MainContent()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter =
            IntentFilter().apply {
                addAction(PipHelper.ACTION_PIP_MUTE)
                addAction(PipHelper.ACTION_PIP_TOGGLE)
            }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun checkConnectedWatches() {
        WearCompanionBridge.queryConnectedWatches(this) { count, name ->
            connectedWatchCount = count
            connectedWatchName = name
        }
    }

    private fun testWearVibration() {
        WearCompanionBridge.sendCommand(this, WearCompanionBridge.PATH_ALERT, "TEST_VIBRATION")
        Toast.makeText(this, "Тестовая вибрация отправлена на часы", Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(pipActionReceiver) }
    }

    override fun onDestroy() {
        voiceAlertCoordinator.close()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (profiles.pipEnabled && profiles.pipAutoEnter && DetectionState.state.value.running) {
            PipHelper.enterPipMode(this, DetectionState.state.value, profiles)
        }
    }

    @Composable
    private fun MainContent() {
        val detectionState by DetectionState.state.collectAsStateWithLifecycle()

        LaunchedEffect(
            detectionState.running,
            detectionState.muted,
            pipEnabled,
            pipAutoEnter,
        ) {
            PipHelper.updatePipParams(this@MainActivity, detectionState, profiles)
        }

        SpeedCamTheme {
            if (isInPipMode) {
                PipSpeedometerContent(state = detectionState)
            } else {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FullscreenAppContent(detectionState)
                }
            }
        }
    }

    @Composable
    private fun FullscreenAppContent(detectionState: com.speedcam.service.DetectionUiState) {
        val syncInfos by WorkManager
            .getInstance(this)
            .getWorkInfosForUniqueWorkFlow(DatabaseSyncWorker.WORK_NAME_IMMEDIATE)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val hazardInfos by WorkManager
            .getInstance(this)
            .getWorkInfosForUniqueWorkFlow(HazardReportSyncWorker.WORK_NAME_IMMEDIATE)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val isUpdating = syncInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val isSyncing = hazardInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val pendingCount by (store ?: SpeedCamApp.cameraStore(this))
            .observePendingReportCount()
            .collectAsStateWithLifecycle(initialValue = 0)

        LaunchedEffect(syncInfos) {
            if (syncInfos.any { it.state.isFinished }) refreshCameraList()
        }

        AppShell(
            state =
                ShellState(
                    ui = detectionState,
                    cameras = cameras.collectAsStateWithLifecycle().value,
                    blocked = blocked.collectAsStateWithLifecycle().value,
                    sections = sections.collectAsStateWithLifecycle().value,
                    profiles = profiles,
                    hudPermissionGranted = HudOverlay.canDrawOverlays(this),
                    isUpdating = isUpdating || isManualUpdating,
                    pendingReportCount = pendingCount,
                    isSyncingReports = isSyncing,
                    isContraCamSyncing = isContraCamSyncing,
                    useContraCamDb = useContraCamDb,
                    autoStartOnBluetooth = autoStartOnBluetooth,
                    autoStopOnBluetooth = autoStopOnBluetooth,
                    bluetoothDeviceFilter = bluetoothDeviceFilter,
                    bluetoothDeviceFilterName = bluetoothDeviceFilterName,
                    bluetoothStopDelaySeconds = bluetoothStopDelaySeconds,
                    pairedBluetoothDevices = getBondedDevices(),
                    pipEnabled = pipEnabled,
                    pipAutoEnter = pipAutoEnter,
                    launcherBroadcastEnabled = launcherBroadcastEnabled,
                    headUnitAutoStart = headUnitAutoStart,
                    wearCompanionEnabled = wearCompanionEnabled,
                    wearHapticProfile = wearHapticProfile,
                    connectedWatchCount = connectedWatchCount,
                    connectedWatchName = connectedWatchName,
                    isDetectionEnabled = detectionRunning,
                    quickSetting = quickSetting,
                    captureDistanceKm = captureDistanceKm,
                    transport = transport,
                    hudEnabled = hudEnabled,
                    hudAutoHide = hudAutoHide,
                    outputMode = outputMode,
                    silentDuringCall = silentDuringCall,
                    autoUpdateEnabled = autoUpdateEnabled,
                    updateUrl = updateUrl,
                    offlineTilesEnabled = offlineTilesEnabled,
                    activeMbTilesName = activeMbTilesName,
                    tileCacheStats = tileCacheStats,
                    voiceEngine = voiceEngine,
                    voicePhraseStyle = voicePhraseStyle,
                    ttsSpeechRate = ttsSpeechRate,
                    activeVoicePackId = activeVoicePackId,
                    activeVoicePackName = activeVoicePackName,
                    installedVoicePacks = installedVoicePacks,
                ),
            actions = buildShellActions(),
        )
    }

    private fun getBondedDevices(): List<Pair<String, String>> =
        runCatching {
            CarBluetoothManager.getBondedDevices(this).map {
                it.address to (runCatching { it.name }.getOrNull() ?: it.address)
            }
        }.getOrDefault(emptyList())

    private fun toggleAutoUpdate(enabled: Boolean) {
        autoUpdateEnabled = enabled
        lifecycleScope.launch(Dispatchers.IO) { profiles.autoUpdateEnabled = enabled }
        if (enabled) {
            DatabaseSyncWorker.schedulePeriodic(this)
        } else {
            DatabaseSyncWorker.cancelPeriodic(this)
        }
    }

    private fun onBluetoothFilterChange(
        address: String,
        name: String,
    ) {
        bluetoothDeviceFilter = address
        bluetoothDeviceFilterName = name
        lifecycleScope.launch(Dispatchers.IO) {
            profiles.bluetoothDeviceFilter = address
            profiles.bluetoothDeviceFilterName = name
        }
    }

    private fun onPipChange(enabled: Boolean) {
        pipEnabled = enabled
        profiles.pipEnabled = enabled
        PipHelper.updatePipParams(this, DetectionState.state.value, profiles)
    }

    private fun onPipAutoEnterChange(autoEnter: Boolean) {
        pipAutoEnter = autoEnter
        profiles.pipAutoEnter = autoEnter
        PipHelper.updatePipParams(this, DetectionState.state.value, profiles)
    }

    private fun buildShellActions(): ShellActions =
        ShellActions(
            onEnableChange = ::setDetectionEnabled,
            onUnblock = ::unblock,
            onRequestHudPermission = ::requestOverlayPermission,
            onResetHudPosition = { HudOverlayManager.resetPosition(this) },
            onAutoUpdateChange = ::toggleAutoUpdate,
            onCheckForUpdates = { DatabaseSyncWorker.triggerImmediate(this) },
            onReportCamera = ::reportCamera,
            onSyncHazardOutbox = ::syncHazardOutbox,
            onClearSyncedReports = ::clearSyncedHazards,
            onToggleMute = { HudOverlayManager.muteNearest(this) },
            onBlockNearest = { HudOverlayManager.blockNearest(this) },
            onReportHazard = { HudOverlayManager.reportHazard(this) },
            onReportHazardType = { type ->
                HudOverlayManager.reportSpecificHazard(this, type)
                refreshCameraList()
            },
            onToggleHud = { HudOverlayManager.toggle(this) },
            onContraCamDbChange = ::toggleContraCamDb,
            onSyncContraCam = ::syncContraCam,
            onAutoStartOnBluetoothChange = {
                autoStartOnBluetooth = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.autoStartOnBluetooth = it }
            },
            onAutoStopOnBluetoothChange = {
                autoStopOnBluetooth = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.autoStopOnBluetooth = it }
            },
            onBluetoothDeviceFilterChange = ::onBluetoothFilterChange,
            onBluetoothStopDelaySecondsChange = {
                bluetoothStopDelaySeconds = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.bluetoothStopDelaySeconds = it }
            },
            onEnterPip = { PipHelper.enterPipMode(this, DetectionState.state.value, profiles) },
            onPipEnabledChange = ::onPipChange,
            onPipAutoEnterChange = ::onPipAutoEnterChange,
            onLauncherBroadcastEnabledChange = {
                launcherBroadcastEnabled = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.launcherBroadcastEnabled = it }
            },
            onHeadUnitAutoStartChange = {
                headUnitAutoStart = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.headUnitAutoStart = it }
            },
            onWearCompanionEnabledChange = {
                wearCompanionEnabled = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.wearCompanionEnabled = it }
            },
            onWearHapticProfileChange = {
                wearHapticProfile = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.wearHapticProfile = it }
            },
            onTestWearVibration = ::testWearVibration,
            onQuickSettingChange = {
                quickSetting = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.quickSetting = it }
            },
            onCaptureDistanceChange = {
                captureDistanceKm = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.captureDistanceKm = it }
            },
            onTransportChange = {
                transport = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.transport = it }
            },
            onHudEnabledChange = {
                hudEnabled = it
                HudOverlayManager.setHudEnabled(this, it)
            },
            onHudAutoHideChange = {
                hudAutoHide = it
                lifecycleScope.launch(Dispatchers.IO) {
                    profiles.hudAutoHide = it
                    HudOverlayManager.sync(this@MainActivity)
                }
            },
            onOutputModeChange = {
                outputMode = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.outputMode = it }
            },
            onSilentDuringCallChange = {
                silentDuringCall = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.silentDuringCall = it }
            },
            onVoiceEngineChange = { engine ->
                voiceEngine = engine
                profiles.voiceEngine = engine
            },
            onVoicePhraseStyleChange = { style ->
                voicePhraseStyle = style
                profiles.voicePhraseStyle = style
            },
            onTtsSpeechRateChange = { rate ->
                ttsSpeechRate = rate
                profiles.ttsSpeechRate = rate
            },
            onSelectVoicePack = { packId ->
                activeVoicePackId = packId
                profiles.activeVoicePackId = packId
                refreshVoicePacks()
            },
            onImportVoicePack = { voicePackPickerLauncher.launch(arrayOf("*/*")) },
            onTestVoice = ::testVoice,
            onImportDatabaseFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onRestoreBundledDatabase = ::restoreBundledDatabase,
            onUpdateUrlChange = ::onUpdateUrlChange,
            onOfflineTilesEnabledChange = {
                offlineTilesEnabled = it
                lifecycleScope.launch(Dispatchers.IO) { profiles.offlineTilesEnabled = it }
            },
            onImportMbTilesFile = { mbTilesPickerLauncher.launch(arrayOf("*/*")) },
            onClearMapCache = ::clearMapCache,
            onDisconnectMbTiles = ::disconnectMbTiles,
        )

    private fun importLocalDatabaseFile(uri: android.net.Uri) {
        if (isManualUpdating) return
        isManualUpdating = true
        lifecycleScope.launch {
            val helper = DatabaseImportHelper(applicationContext)
            val result = helper.importFromUri(uri)
            handleSyncResult(result)
            isManualUpdating = false
            refreshCameraList()
        }
    }

    private fun restoreBundledDatabase() {
        if (isManualUpdating) return
        isManualUpdating = true
        lifecycleScope.launch {
            val syncEngine = DatabaseSyncEngine(applicationContext)
            val result = syncEngine.restoreBundledDatabase()
            handleSyncResult(result)
            isManualUpdating = false
            refreshCameraList()
        }
    }

    private fun refreshOfflineMapStats() {
        val mgr = SpeedCamApp.offlineTileManager(this)
        activeMbTilesName = mgr.activeMbTiles?.name
        tileCacheStats = mgr.getTileCacheStats()
        offlineTilesEnabled = profiles.offlineTilesEnabled
    }

    private fun importMbTilesFile(uri: Uri) {
        lifecycleScope.launch {
            val result = SpeedCamApp.offlineTileManager(this@MainActivity).importMbTilesFromUri(uri)
            Toast.makeText(this@MainActivity, result.second, Toast.LENGTH_LONG).show()
            refreshOfflineMapStats()
        }
    }

    private fun clearMapCache() {
        SpeedCamApp.offlineTileManager(this).clearCache()
        refreshOfflineMapStats()
        Toast.makeText(this, "Кэш карты очищен", Toast.LENGTH_SHORT).show()
    }

    private fun disconnectMbTiles() {
        SpeedCamApp.offlineTileManager(this).clearActiveMbTiles()
        refreshOfflineMapStats()
        Toast.makeText(this, "Архив MBTiles отключен", Toast.LENGTH_SHORT).show()
    }

    private fun refreshVoicePacks() {
        val packs = voicePackManager.listInstalledPacks()
        installedVoicePacks = packs.map { it.id to "${it.name} (${it.clipCount})" }
        val active = packs.firstOrNull { it.id == activeVoicePackId }
        activeVoicePackName = active?.let { "${it.name} (${it.clipCount} фраз)" }
    }

    private fun importVoicePackZip(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val (success, message) = voicePackManager.importPackFromUri(uri)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                if (success) {
                    val packs = voicePackManager.listInstalledPacks()
                    if (packs.isNotEmpty()) {
                        val lastPack = packs.last()
                        activeVoicePackId = lastPack.id
                        profiles.activeVoicePackId = lastPack.id
                        profiles.voiceEngine = VoiceEngine.AUDIO_PACK
                        voiceEngine = VoiceEngine.AUDIO_PACK
                    }
                    refreshVoicePacks()
                }
            }
        }
    }

    private fun testVoice() {
        voiceAlertCoordinator.setOutputMode(outputMode)
        voiceAlertCoordinator.engine = voiceEngine
        voiceAlertCoordinator.phraseStyle = voicePhraseStyle
        voiceAlertCoordinator.speechRate = ttsSpeechRate
        voiceAlertCoordinator.activePackId = activeVoicePackId
        voiceAlertCoordinator.speakTest()
    }

    private fun handleSyncResult(result: SyncResult) {
        profiles.lastUpdateStatus = result.message
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        profiles.lastUpdateCheckAt = nowStr
        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
    }

    private fun onUpdateUrlChange(newUrl: String) {
        updateUrl = newUrl
        lifecycleScope.launch(Dispatchers.IO) {
            profiles.updateUrl = newUrl
        }
    }

    private fun toggleContraCamDb(enabled: Boolean) {
        profiles.useContraCamDb = enabled
        useContraCamDb = enabled
        val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
        cameraStore.useContraCamDb = enabled

        if (enabled) {
            lifecycleScope.launch(Dispatchers.IO) {
                val contraCount = cameraStore.cameraDao.countBySource(CameraSource.CONTRACAM.ordinal)
                if (contraCount == 0) {
                    withContext(Dispatchers.Main) {
                        val message = "База ContraCam не найдена. Начинаем автоматическую загрузку..."
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        syncContraCam()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val message = "Активирована база ContraCam ($contraCount камер)"
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
                ServiceController.reloadDatabase(this@MainActivity)
                refreshCameraList()
            }
        } else {
            ServiceController.reloadDatabase(this)
            refreshCameraList()
            Toast.makeText(this, "Активирована стандартная база OSM", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncContraCam() {
        if (isContraCamSyncing) return
        isContraCamSyncing = true
        lifecycleScope.launch {
            val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
            val sync = ContraCamArchiveSync(cameraStore.cameraDao, cacheDir)
            val result = sync.downloadAndImport()
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            profiles.lastContraCamSyncAt = nowStr
            result.fold(
                onSuccess = { count ->
                    profiles.contraCamCameraCount = count
                    profiles.lastContraCamStatus = "Успешно ($count камер)"
                    if (profiles.useContraCamDb) {
                        cameraStore.useContraCamDb = true
                        ServiceController.reloadDatabase(this@MainActivity)
                        refreshCameraList()
                    }
                    Toast
                        .makeText(
                            this@MainActivity,
                            "Архив ContraCam загружен: $count камер",
                            Toast.LENGTH_SHORT,
                        ).show()
                },
                onFailure = { error ->
                    profiles.lastContraCamStatus = "Ошибка: ${error.message}"
                    Toast
                        .makeText(
                            this@MainActivity,
                            "Ошибка синхронизации ContraCam: ${error.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                },
            )
            isContraCamSyncing = false
        }
    }

    override fun onResume() {
        super.onResume()
        checkConnectedWatches()
        autoStartOnBluetooth = profiles.autoStartOnBluetooth
        autoStopOnBluetooth = profiles.autoStopOnBluetooth
        bluetoothDeviceFilter = profiles.bluetoothDeviceFilter
        bluetoothDeviceFilterName = profiles.bluetoothDeviceFilterName
        bluetoothStopDelaySeconds = profiles.bluetoothStopDelaySeconds
        pipEnabled = profiles.pipEnabled
        pipAutoEnter = profiles.pipAutoEnter
        launcherBroadcastEnabled = profiles.launcherBroadcastEnabled
        headUnitAutoStart = profiles.headUnitAutoStart
        detectionRunning = profiles.isEnabled
        quickSetting = profiles.quickSetting
        captureDistanceKm = profiles.captureDistanceKm
        transport = profiles.transport
        hudEnabled = profiles.hudEnabled
        hudAutoHide = profiles.hudAutoHide
        outputMode = profiles.outputMode
        silentDuringCall = profiles.silentDuringCall
        autoUpdateEnabled = profiles.autoUpdateEnabled
        updateUrl = profiles.updateUrl
        wearCompanionEnabled = profiles.wearCompanionEnabled
        wearHapticProfile = profiles.wearHapticProfile
        voiceEngine = profiles.voiceEngine
        voicePhraseStyle = profiles.voicePhraseStyle
        ttsSpeechRate = profiles.ttsSpeechRate
        activeVoicePackId = profiles.activeVoicePackId
        refreshVoicePacks()
        refreshOfflineMapStats()
        refreshCameraList()
        HudOverlayManager.sync(this)
        if (profiles.isEnabled && fineLocationGranted) ServiceController.start(this)
    }

    // ------------------------------------------------------------------ loading

    private fun refreshCameraList() {
        val cameraStore = SpeedCamApp.cameraStore(applicationContext)
        store = cameraStore
        lifecycleScope.launch {
            runCatching {
                cameraStore.useContraCamDb = profiles.useContraCamDb
                cameraStore.refreshOverrides()
                cameraStore.refreshSections()
                cameras.value = cameraStore.allCameras()
                blocked.value = cameraStore.overrides.blocked
                sections.value = cameraStore.sections
            }.onFailure { Log.w(TAG, "Could not load the camera list: ${it.message}") }
        }
    }

    private fun setDetectionEnabled(enabled: Boolean) {
        detectionRunning = enabled
        lifecycleScope.launch(Dispatchers.IO) { profiles.isEnabled = enabled }
        if (enabled) {
            ServiceController.start(this)
        } else {
            ServiceController.stop(this)
        }
        DetectionState.update { it.copy(running = enabled) }
    }

    private fun unblock(row: CameraRow) {
        val cameraStore = store ?: return
        lifecycleScope.launch {
            runCatching { cameraStore.unblock(LatLon(row.latitude, row.longitude)) }
                .onFailure { Log.w(TAG, "Could not unblock: ${it.message}") }
            blocked.value = cameraStore.overrides.blocked
        }
    }

    private fun reportCamera(
        row: CameraRow,
        type: HazardReportType,
        speedLimitKmh: Int?,
        comment: String?,
    ) {
        val report =
            HazardReportEntity(
                lat = row.latitude,
                lon = row.longitude,
                cameraId = row.id,
                reportType = type.ordinal,
                speedLimitKmh = speedLimitKmh,
                comment = comment,
            )
        lifecycleScope.launch {
            val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
            cameraStore.submitReport(report)
            HazardReportSyncWorker.triggerImmediate(applicationContext)
            Toast.makeText(this@MainActivity, "Отметка поставлена в очередь для камеры #${row.id}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncHazardOutbox() {
        HazardReportSyncWorker.triggerImmediate(this)
    }

    private fun clearSyncedHazards() {
        lifecycleScope.launch {
            val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
            cameraStore.hazardReportDao.clearSynced()
            Toast.makeText(this@MainActivity, "Синхронизированные отчеты очищены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    // ------------------------------------------------------------- permissions

    private fun requestLocationThenStart() {
        val wanted =
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) {
            fineLocationGranted = true
            continueStartup()
        } else {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    /**
     * Background location is a separate grant from Android 10, and Android 11+ refuses to ask
     * for it in the same call as foreground location.
     */
    private fun continueStartup() {
        if (!fineLocationGranted) {
            Log.i(TAG, "Fine location not granted; detection not started.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }

        if (profiles.isEnabled) {
            ServiceController.start(this)
        } else {
            Log.i(TAG, "Detection is switched off; not starting.")
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

// --------------------------------------------------------------------- UI shell

private enum class Tab(
    val label: String,
) {
    STATUS("Спидометр"),
    MAP("Карта"),
    SETTINGS("Настройки"),
}

/** What the shell reads, grouped so the composable's signature stays reviewable. */
private data class ShellState(
    val ui: com.speedcam.service.DetectionUiState,
    val cameras: List<Camera>,
    val blocked: List<LatLon>,
    val sections: List<SectionRecord> = emptyList(),
    val profiles: ProfileStore,
    val hudPermissionGranted: Boolean,
    val isUpdating: Boolean = false,
    val pendingReportCount: Int = 0,
    val isSyncingReports: Boolean = false,
    val isContraCamSyncing: Boolean = false,
    val useContraCamDb: Boolean = false,
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
    val isDetectionEnabled: Boolean = true,
    val quickSetting: QuickSetting = QuickSetting.MAXIMUM_ALERTS,
    val captureDistanceKm: Double = 0.0,
    val transport: TransportProfile = TransportProfile.COMMON,
    val hudEnabled: Boolean = true,
    val hudAutoHide: Boolean = false,
    val outputMode: OutputMode = OutputMode.STANDARD,
    val silentDuringCall: Boolean = false,
    val autoUpdateEnabled: Boolean = true,
    val updateUrl: String = "",
    val offlineTilesEnabled: Boolean = true,
    val activeMbTilesName: String? = null,
    val tileCacheStats: String = "",
    val voiceEngine: VoiceEngine = VoiceEngine.TTS,
    val voicePhraseStyle: VoicePhraseStyle = VoicePhraseStyle.FULL,
    val ttsSpeechRate: Float = 1.0f,
    val activeVoicePackId: String? = null,
    val activeVoicePackName: String? = null,
    val installedVoicePacks: List<Pair<String, String>> = emptyList(),
)

/** What the shell does, one callback per screen-level action. */
private data class ShellActions(
    val onEnableChange: (Boolean) -> Unit,
    val onUnblock: (CameraRow) -> Unit,
    val onRequestHudPermission: () -> Unit,
    val onResetHudPosition: () -> Unit,
    val onAutoUpdateChange: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onImportDatabaseFile: () -> Unit,
    val onRestoreBundledDatabase: () -> Unit,
    val onUpdateUrlChange: (String) -> Unit,
    val onReportCamera: (CameraRow, HazardReportType, Int?, String?) -> Unit,
    val onSyncHazardOutbox: () -> Unit,
    val onClearSyncedReports: () -> Unit,
    val onToggleMute: () -> Unit,
    val onBlockNearest: () -> Unit,
    val onReportHazard: () -> Unit,
    val onReportHazardType: (HazardReportType) -> Unit,
    val onToggleHud: () -> Unit,
    val onContraCamDbChange: (Boolean) -> Unit,
    val onSyncContraCam: () -> Unit,
    val onAutoStartOnBluetoothChange: (Boolean) -> Unit,
    val onAutoStopOnBluetoothChange: (Boolean) -> Unit,
    val onBluetoothDeviceFilterChange: (address: String, name: String) -> Unit,
    val onBluetoothStopDelaySecondsChange: (Int) -> Unit,
    val onEnterPip: () -> Unit,
    val onPipEnabledChange: (Boolean) -> Unit,
    val onPipAutoEnterChange: (Boolean) -> Unit,
    val onLauncherBroadcastEnabledChange: (Boolean) -> Unit,
    val onHeadUnitAutoStartChange: (Boolean) -> Unit,
    val onWearCompanionEnabledChange: (Boolean) -> Unit,
    val onWearHapticProfileChange: (String) -> Unit,
    val onTestWearVibration: () -> Unit,
    val onQuickSettingChange: (QuickSetting) -> Unit,
    val onCaptureDistanceChange: (Double) -> Unit,
    val onTransportChange: (TransportProfile) -> Unit,
    val onHudEnabledChange: (Boolean) -> Unit,
    val onHudAutoHideChange: (Boolean) -> Unit,
    val onOutputModeChange: (OutputMode) -> Unit,
    val onSilentDuringCallChange: (Boolean) -> Unit,
    val onVoiceEngineChange: (VoiceEngine) -> Unit,
    val onVoicePhraseStyleChange: (VoicePhraseStyle) -> Unit,
    val onTtsSpeechRateChange: (Float) -> Unit,
    val onSelectVoicePack: (String) -> Unit,
    val onImportVoicePack: () -> Unit,
    val onTestVoice: () -> Unit,
    val onOfflineTilesEnabledChange: (Boolean) -> Unit,
    val onImportMbTilesFile: () -> Unit,
    val onClearMapCache: () -> Unit,
    val onDisconnectMbTiles: () -> Unit,
    val onNavigateToTab: (Tab) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    state: ShellState,
    actions: ShellActions,
) {
    var tab by remember { mutableStateOf(Tab.STATUS) }
    val context = LocalContext.current
    val initialCenter =
        remember(state.cameras) {
            val (center, _) =
                MapCenteringHelper.findInitialCenter(
                    context = context,
                    uiLat = state.ui.currentLat,
                    uiLon = state.ui.currentLon,
                    cameras = state.cameras,
                )
            center
        }
    val mapState =
        remember {
            VectorMapState(
                initialCenter = initialCenter,
                initialZoom = 15.5f,
                initialBearing = (state.ui.currentBearingDeg ?: 0.0).toFloat(),
                initialFollowMode = MapFollowMode.FOLLOW_HEADING,
                initialTheme = MapThemeMode.AUTOMOTIVE_DARK,
            )
        }

    LaunchedEffect(state.offlineTilesEnabled) {
        mapState.showTiles = state.offlineTilesEnabled
    }

    Scaffold(
        topBar = {
            AppTopBar(
                running = state.ui.running,
                hudEnabled = state.profiles.hudEnabled,
                pipEnabled = state.pipEnabled,
                onToggleHud = actions.onToggleHud,
                onEnterPip = actions.onEnterPip,
            )
        },
        bottomBar = {
            AppBottomBar(
                selectedTab = tab,
                onSelectTab = { tab = it },
            )
        },
    ) { padding ->
        val shellActions =
            remember(actions) {
                actions.copy(onNavigateToTab = { tab = it })
            }
        ShellTabContent(
            tab = tab,
            state = state,
            mapState = mapState,
            actions = shellActions,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun ShellTabContent(
    tab: Tab,
    state: ShellState,
    mapState: VectorMapState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        Tab.STATUS ->
            StatusTab(
                state = state,
                actions = actions,
                onOpenMap = { actions.onNavigateToTab(Tab.MAP) },
                modifier = modifier,
            )

        Tab.MAP ->
            VectorMapScreen(
                mapState = mapState,
                detectionState = state.ui,
                mapData =
                    VectorMapData(
                        cameras = state.cameras,
                        sections = state.sections,
                        roads = emptyList(),
                        blocked = state.blocked,
                    ),
                actions =
                    VectorMapActions(
                        onBlockCamera = { cam -> actions.onUnblock(cam.toCameraRow()) },
                        onUnblockCamera = { cam -> actions.onUnblock(cam.toCameraRow()) },
                        onReportCamera = { cam ->
                            actions.onReportCamera(
                                cam.toCameraRow(),
                                HazardReportType.FALSE_POSITIVE,
                                cam.limitKmh,
                                "Reported from Vector Map",
                            )
                        },
                        onReportHazardType = { type ->
                            actions.onReportHazardType(type)
                        },
                    ),
                modifier = modifier,
            )

        Tab.SETTINGS -> SettingsTab(state, actions, modifier)
    }
}

private fun Camera.toCameraRow(): CameraRow =
    CameraRow(
        id = id,
        latitude = position.lat,
        longitude = position.lon,
        limitKmh = limitKmh,
        kind = kind,
        source = source,
        operator = operator,
        blocked = false,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    running: Boolean,
    hudEnabled: Boolean,
    pipEnabled: Boolean,
    onToggleHud: () -> Unit,
    onEnterPip: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_speedcam_logo),
                    contentDescription = "SpeedCam Logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SpeedCam",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (running) SpeedSafe else Color(0xFF64748B)),
                )
            }
        },
        actions = {
            if (pipEnabled) {
                IconButton(onClick = onEnterPip) {
                    Icon(
                        painter = painterResource(R.drawable.ic_picture_in_picture),
                        contentDescription = "Picture in Picture",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggleHud) {
                val tint =
                    if (hudEnabled) {
                        BrandPrimaryDark
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Плавающий HUD",
                    tint = tint,
                )
            }
        },
    )
}

@Composable
private fun AppBottomBar(
    selectedTab: Tab,
    onSelectTab: (Tab) -> Unit,
) {
    NavigationBar {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = selectedTab == entry,
                onClick = { onSelectTab(entry) },
                icon = {
                    Icon(
                        imageVector =
                            when (entry) {
                                Tab.STATUS -> Icons.Default.Speed
                                Tab.MAP -> Icons.Default.Map
                                Tab.SETTINGS -> Icons.Default.Settings
                            },
                        contentDescription = entry.label,
                    )
                },
                label = { Text(entry.label) },
            )
        }
    }
}

private fun buildSettingsState(state: ShellState): SettingsState {
    val profiles = state.profiles
    return SettingsState(
        enabled = state.isDetectionEnabled,
        quickSetting = state.quickSetting,
        captureDistanceKm = state.captureDistanceKm,
        transport = state.transport,
        hudEnabled = state.hudEnabled,
        hudAutoHide = state.hudAutoHide,
        hudPermissionGranted = state.hudPermissionGranted,
        outputMode = state.outputMode,
        silentDuringCall = state.silentDuringCall,
        cameraCount = state.ui.cameraCount,
        sectionCount = state.ui.sectionCount,
        databaseBuiltAt = state.ui.databaseBuiltAt,
        dataSourceNote = DATA_SOURCE_NOTE,
        autoUpdateEnabled = state.autoUpdateEnabled,
        lastUpdateCheckAt = profiles.lastUpdateCheckAt,
        lastUpdateStatus = profiles.lastUpdateStatus,
        isUpdating = state.isUpdating,
        pendingReportCount = state.pendingReportCount,
        lastHazardSyncAt = profiles.lastHazardSyncAt,
        lastHazardSyncStatus = profiles.lastHazardSyncStatus,
        isSyncingReports = state.isSyncingReports,
        useContraCamDb = state.useContraCamDb,
        lastContraCamSyncAt = profiles.lastContraCamSyncAt,
        lastContraCamStatus = profiles.lastContraCamStatus,
        contraCamCameraCount = profiles.contraCamCameraCount,
        isContraCamSyncing = state.isContraCamSyncing,
        autoStartOnBluetooth = state.autoStartOnBluetooth,
        autoStopOnBluetooth = state.autoStopOnBluetooth,
        bluetoothDeviceFilter = state.bluetoothDeviceFilter,
        bluetoothDeviceFilterName = state.bluetoothDeviceFilterName,
        bluetoothStopDelaySeconds = state.bluetoothStopDelaySeconds,
        pairedBluetoothDevices = state.pairedBluetoothDevices,
        pipEnabled = state.pipEnabled,
        pipAutoEnter = state.pipAutoEnter,
        launcherBroadcastEnabled = state.launcherBroadcastEnabled,
        headUnitAutoStart = state.headUnitAutoStart,
        wearCompanionEnabled = state.wearCompanionEnabled,
        wearHapticProfile = state.wearHapticProfile,
        connectedWatchCount = state.connectedWatchCount,
        connectedWatchName = state.connectedWatchName,
        updateUrl = state.updateUrl,
        offlineTilesEnabled = state.offlineTilesEnabled,
        activeMbTilesName = state.activeMbTilesName,
        tileCacheStats = state.tileCacheStats,
        voiceEngine = state.voiceEngine,
        voicePhraseStyle = state.voicePhraseStyle,
        ttsSpeechRate = state.ttsSpeechRate,
        activeVoicePackId = state.activeVoicePackId,
        activeVoicePackName = state.activeVoicePackName,
        installedVoicePacks = state.installedVoicePacks,
    )
}

private fun buildSettingsActions(actions: ShellActions): SettingsActions =
    SettingsActions(
        onEnabledChange = actions.onEnableChange,
        onQuickSettingChange = actions.onQuickSettingChange,
        onCaptureDistanceChange = actions.onCaptureDistanceChange,
        onTransportChange = actions.onTransportChange,
        onHudEnabledChange = actions.onHudEnabledChange,
        onHudAutoHideChange = actions.onHudAutoHideChange,
        onRequestHudPermission = actions.onRequestHudPermission,
        onResetHudPosition = actions.onResetHudPosition,
        onOutputModeChange = actions.onOutputModeChange,
        onSilentDuringCallChange = actions.onSilentDuringCallChange,
        onVoiceEngineChange = actions.onVoiceEngineChange,
        onVoicePhraseStyleChange = actions.onVoicePhraseStyleChange,
        onTtsSpeechRateChange = actions.onTtsSpeechRateChange,
        onSelectVoicePack = actions.onSelectVoicePack,
        onImportVoicePack = actions.onImportVoicePack,
        onTestVoice = actions.onTestVoice,
        onAutoUpdateChange = actions.onAutoUpdateChange,
        onCheckForUpdates = actions.onCheckForUpdates,
        onImportDatabaseFile = actions.onImportDatabaseFile,
        onRestoreBundledDatabase = actions.onRestoreBundledDatabase,
        onUpdateUrlChange = actions.onUpdateUrlChange,
        onSyncHazardOutbox = actions.onSyncHazardOutbox,
        onClearSyncedReports = actions.onClearSyncedReports,
        onContraCamDbChange = actions.onContraCamDbChange,
        onSyncContraCam = actions.onSyncContraCam,
        onAutoStartOnBluetoothChange = actions.onAutoStartOnBluetoothChange,
        onAutoStopOnBluetoothChange = actions.onAutoStopOnBluetoothChange,
        onBluetoothDeviceFilterChange = actions.onBluetoothDeviceFilterChange,
        onBluetoothStopDelaySecondsChange = actions.onBluetoothStopDelaySecondsChange,
        onPipEnabledChange = actions.onPipEnabledChange,
        onPipAutoEnterChange = actions.onPipAutoEnterChange,
        onLauncherBroadcastEnabledChange = actions.onLauncherBroadcastEnabledChange,
        onHeadUnitAutoStartChange = actions.onHeadUnitAutoStartChange,
        onWearCompanionEnabledChange = actions.onWearCompanionEnabledChange,
        onWearHapticProfileChange = actions.onWearHapticProfileChange,
        onTestWearVibration = actions.onTestWearVibration,
        onOfflineTilesEnabledChange = actions.onOfflineTilesEnabledChange,
        onImportMbTilesFile = actions.onImportMbTilesFile,
        onClearMapCache = actions.onClearMapCache,
        onDisconnectMbTiles = actions.onDisconnectMbTiles,
    )

@Composable
private fun SettingsTab(
    state: ShellState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(
        state = buildSettingsState(state),
        actions = buildSettingsActions(actions),
        modifier = modifier,
    )
}

/** Attribution is not optional: ODbL 1.0 requires crediting OpenStreetMap. */
private const val DATA_SOURCE_NOTE = "OpenStreetMap, ODbL 1.0. Rebuilt by tools/import."

@Composable
private fun StatusTab(
    state: ShellState,
    actions: ShellActions,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hero Speedometer & Radar Cluster
        item {
            SpeedometerCluster(
                state = state.ui,
                actions =
                    SpeedometerActions(
                        onEnableChange = actions.onEnableChange,
                        onToggleMute = actions.onToggleMute,
                        onReportHazard = actions.onReportHazard,
                        onToggleHud = actions.onToggleHud,
                    ),
            )
        }

        // Lightweight Vector Radar Map Preview Card
        item {
            RadarMapCard(
                detectionState = state.ui,
                cameras = state.cameras,
                sections = state.sections,
                onExpandMap = onOpenMap,
            )
        }

        // Active Average Speed Zone (SPECS Corridor)
        state.ui.activeSection?.let { section ->
            item {
                SpecsSectionAlertCard(section = section)
            }
        }

        // Primary Upcoming Hazard
        val nearest = state.ui.nearest
        if (nearest != null) {
            item {
                PrimaryHazardAlertCard(
                    hazard = nearest,
                    overLimit = state.ui.overLimit,
                    onMute = actions.onToggleMute,
                    onBlock = actions.onBlockNearest,
                )
            }
        }

        // Secondary Upcoming Hazard
        state.ui.second?.let { second ->
            item {
                SecondaryHazardCard(hazard = second)
            }
        }

        // Clear Road State
        if (nearest == null && state.ui.activeSection == null) {
            item {
                EmptyHazardCard(radarActive = state.ui.running)
            }
        }
    }
}
