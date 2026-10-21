package com.speedcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.hud.HudOverlay
import com.speedcam.app.hud.HudOverlayManager
import com.speedcam.app.sync.DatabaseSyncWorker
import com.speedcam.app.sync.HazardReportSyncWorker
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
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionState
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    private var isContraCamSyncing by mutableStateOf(false)
    private var useContraCamDb by mutableStateOf(false)

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

        useContraCamDb = profiles.useContraCamDb
        refreshCameraList()
        requestLocationThenStart()

        setContent {
            MainContent()
        }
    }

    @Composable
    private fun MainContent() {
        val syncWorkInfos by WorkManager
            .getInstance(this)
            .getWorkInfosForUniqueWorkFlow(DatabaseSyncWorker.WORK_NAME_IMMEDIATE)
            .collectAsStateWithLifecycle(initialValue = emptyList())

        val hazardWorkInfos by WorkManager
            .getInstance(this)
            .getWorkInfosForUniqueWorkFlow(HazardReportSyncWorker.WORK_NAME_IMMEDIATE)
            .collectAsStateWithLifecycle(initialValue = emptyList())

        val isUpdating =
            syncWorkInfos.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }

        val isSyncingReports =
            hazardWorkInfos.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }

        val pendingReportCount by (store ?: SpeedCamApp.cameraStore(this))
            .observePendingReportCount()
            .collectAsStateWithLifecycle(initialValue = 0)

        LaunchedEffect(syncWorkInfos) {
            if (syncWorkInfos.any { it.state.isFinished }) {
                refreshCameraList()
            }
        }

        SpeedCamTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppShell(
                    state =
                        ShellState(
                            ui = DetectionState.state.collectAsStateWithLifecycle().value,
                            cameras = cameras.collectAsStateWithLifecycle().value,
                            blocked = blocked.collectAsStateWithLifecycle().value,
                            profiles = profiles,
                            hudPermissionGranted = HudOverlay.canDrawOverlays(this),
                            isUpdating = isUpdating,
                            pendingReportCount = pendingReportCount,
                            isSyncingReports = isSyncingReports,
                            isContraCamSyncing = isContraCamSyncing,
                            useContraCamDb = useContraCamDb,
                        ),
                    actions = buildShellActions(),
                )
            }
        }
    }

    private fun toggleAutoUpdate(enabled: Boolean) {
        profiles.autoUpdateEnabled = enabled
        if (enabled) {
            DatabaseSyncWorker.schedulePeriodic(this)
        } else {
            DatabaseSyncWorker.cancelPeriodic(this)
        }
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
            onToggleHud = { HudOverlayManager.toggle(this) },
            onContraCamDbChange = ::toggleContraCamDb,
            onSyncContraCam = ::syncContraCam,
        )

    private fun toggleContraCamDb(enabled: Boolean) {
        profiles.useContraCamDb = enabled
        useContraCamDb = enabled
        val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
        cameraStore.useContraCamDb = enabled
        ServiceController.reloadDatabase(this)
        refreshCameraList()
        val mode = if (enabled) "ContraCam proprietary DB" else "Standard OSM DB"
        Toast.makeText(this, "Active database: $mode", Toast.LENGTH_SHORT).show()
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
                    profiles.lastContraCamStatus = "Success ($count cameras)"
                    if (profiles.useContraCamDb) {
                        cameraStore.useContraCamDb = true
                        ServiceController.reloadDatabase(this@MainActivity)
                        refreshCameraList()
                    }
                    Toast
                        .makeText(
                            this@MainActivity,
                            "ContraCam archive downloaded: $count cameras",
                            Toast.LENGTH_SHORT,
                        ).show()
                },
                onFailure = { error ->
                    profiles.lastContraCamStatus = "Error: ${error.message}"
                    Toast
                        .makeText(
                            this@MainActivity,
                            "ContraCam sync failed: ${error.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                },
            )
            isContraCamSyncing = false
        }
    }

    override fun onResume() {
        super.onResume()
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
            }.onFailure { Log.w(TAG, "Could not load the camera list: ${it.message}") }
        }
    }

    private fun setDetectionEnabled(enabled: Boolean) {
        profiles.isEnabled = enabled
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
            Toast.makeText(this@MainActivity, "Report queued for camera #${row.id}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncHazardOutbox() {
        HazardReportSyncWorker.triggerImmediate(this)
    }

    private fun clearSyncedHazards() {
        lifecycleScope.launch {
            val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
            cameraStore.hazardReportDao.clearSynced()
            Toast.makeText(this@MainActivity, "Cleared synced hazard reports", Toast.LENGTH_SHORT).show()
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
    STATUS("Dashboard"),
    CAMERAS("Cameras"),
    SETTINGS("Settings"),
}

/** What the shell reads, grouped so the composable's signature stays reviewable. */
private data class ShellState(
    val ui: com.speedcam.service.DetectionUiState,
    val cameras: List<Camera>,
    val blocked: List<LatLon>,
    val profiles: ProfileStore,
    val hudPermissionGranted: Boolean,
    val isUpdating: Boolean = false,
    val pendingReportCount: Int = 0,
    val isSyncingReports: Boolean = false,
    val isContraCamSyncing: Boolean = false,
    val useContraCamDb: Boolean = false,
)

/** What the shell does, one callback per screen-level action. */
private data class ShellActions(
    val onEnableChange: (Boolean) -> Unit,
    val onUnblock: (CameraRow) -> Unit,
    val onRequestHudPermission: () -> Unit,
    val onResetHudPosition: () -> Unit,
    val onAutoUpdateChange: (Boolean) -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onReportCamera: (CameraRow, HazardReportType, Int?, String?) -> Unit,
    val onSyncHazardOutbox: () -> Unit,
    val onClearSyncedReports: () -> Unit,
    val onToggleMute: () -> Unit,
    val onBlockNearest: () -> Unit,
    val onReportHazard: () -> Unit,
    val onToggleHud: () -> Unit,
    val onContraCamDbChange: (Boolean) -> Unit,
    val onSyncContraCam: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    state: ShellState,
    actions: ShellActions,
) {
    var tab by remember { mutableStateOf(Tab.STATUS) }

    Scaffold(
        topBar = {
            AppTopBar(
                running = state.ui.running,
                hudEnabled = state.profiles.hudEnabled,
                onToggleHud = actions.onToggleHud,
            )
        },
        bottomBar = {
            AppBottomBar(
                selectedTab = tab,
                onSelectTab = { tab = it },
            )
        },
    ) { padding ->
        when (tab) {
            Tab.STATUS -> StatusTab(state.ui, actions, Modifier.padding(padding))
            Tab.CAMERAS ->
                CameraListScreen(
                    cameras = state.cameras,
                    blocked = state.blocked.map { it.lat to it.lon },
                    onUnblock = actions.onUnblock,
                    modifier = Modifier.padding(padding),
                    onReportCamera = actions.onReportCamera,
                )

            Tab.SETTINGS -> SettingsTab(state, actions, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    running: Boolean,
    hudEnabled: Boolean,
    onToggleHud: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (running) SpeedSafe else Color(0xFF64748B)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SpeedCam Radar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleHud) {
                val tint =
                    if (hudEnabled) {
                        BrandPrimaryDark
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Floating HUD",
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
                                Tab.CAMERAS -> Icons.Default.PhotoCamera
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

@Composable
private fun SettingsTab(
    state: ShellState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profiles = state.profiles
    SettingsScreen(
        state =
            SettingsState(
                enabled = profiles.isEnabled,
                quickSetting = profiles.quickSetting,
                captureDistanceKm = profiles.captureDistanceKm,
                transport = profiles.transport,
                hudEnabled = profiles.hudEnabled,
                hudAutoHide = profiles.hudAutoHide,
                hudPermissionGranted = state.hudPermissionGranted,
                outputMode = profiles.outputMode,
                silentDuringCall = profiles.silentDuringCall,
                cameraCount = state.ui.cameraCount,
                sectionCount = state.ui.sectionCount,
                databaseBuiltAt = state.ui.databaseBuiltAt,
                dataSourceNote = DATA_SOURCE_NOTE,
                autoUpdateEnabled = profiles.autoUpdateEnabled,
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
            ),
        actions =
            SettingsActions(
                onEnabledChange = actions.onEnableChange,
                onQuickSettingChange = { profiles.quickSetting = it },
                onCaptureDistanceChange = { profiles.captureDistanceKm = it },
                onTransportChange = { profiles.transport = it },
                onHudEnabledChange = { HudOverlayManager.setHudEnabled(context, it) },
                onHudAutoHideChange = {
                    profiles.hudAutoHide = it
                    HudOverlayManager.sync(context)
                },
                onRequestHudPermission = actions.onRequestHudPermission,
                onResetHudPosition = actions.onResetHudPosition,
                onOutputModeChange = { profiles.outputMode = it },
                onSilentDuringCallChange = { profiles.silentDuringCall = it },
                onAutoUpdateChange = actions.onAutoUpdateChange,
                onCheckForUpdates = actions.onCheckForUpdates,
                onSyncHazardOutbox = actions.onSyncHazardOutbox,
                onClearSyncedReports = actions.onClearSyncedReports,
                onContraCamDbChange = actions.onContraCamDbChange,
                onSyncContraCam = actions.onSyncContraCam,
            ),
        modifier = modifier,
    )
}

/** Attribution is not optional: ODbL 1.0 requires crediting OpenStreetMap. */
private const val DATA_SOURCE_NOTE = "OpenStreetMap, ODbL 1.0. Rebuilt by tools/import."

@Composable
private fun StatusTab(
    state: com.speedcam.service.DetectionUiState,
    actions: ShellActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hero Speedometer & Radar Cluster
        item {
            SpeedometerCluster(
                state = state,
                actions =
                    SpeedometerActions(
                        onEnableChange = actions.onEnableChange,
                        onToggleMute = actions.onToggleMute,
                        onReportHazard = actions.onReportHazard,
                        onToggleHud = actions.onToggleHud,
                    ),
            )
        }

        // Active Average Speed Zone (SPECS Corridor)
        state.activeSection?.let { section ->
            item {
                SpecsSectionAlertCard(section = section)
            }
        }

        // Primary Upcoming Hazard
        val nearest = state.nearest
        if (nearest != null) {
            item {
                PrimaryHazardAlertCard(
                    hazard = nearest,
                    overLimit = state.overLimit,
                    onMute = actions.onToggleMute,
                    onBlock = actions.onBlockNearest,
                )
            }
        }

        // Secondary Upcoming Hazard
        state.second?.let { second ->
            item {
                SecondaryHazardCard(hazard = second)
            }
        }

        // Clear Road State
        if (nearest == null && state.activeSection == null) {
            item {
                EmptyHazardCard(radarActive = state.running)
            }
        }
    }
}
