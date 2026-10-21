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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.hud.HudOverlay
import com.speedcam.app.hud.HudPositionStore
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
import com.speedcam.core.detect.Camera
import com.speedcam.core.geo.LatLon
import com.speedcam.service.DetectionState
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
    private val hudPositions by lazy { HudPositionStore(this) }

    private var hudOverlay: HudOverlay? = null
    private var store: CameraStore? = null

    private val cameras = MutableStateFlow<List<Camera>>(emptyList())
    private val blocked = MutableStateFlow<List<LatLon>>(emptyList())

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
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { syncHud() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
            onResetHudPosition = { hudPositions.clear() },
            onAutoUpdateChange = ::toggleAutoUpdate,
            onCheckForUpdates = { DatabaseSyncWorker.triggerImmediate(this) },
            onReportCamera = ::reportCamera,
            onSyncHazardOutbox = ::syncHazardOutbox,
            onClearSyncedReports = ::clearSyncedHazards,
            onToggleMute = ::muteNearest,
            onBlockNearest = ::blockNearest,
            onReportHazard = ::reportHazardFromHud,
            onToggleHud = {
                profiles.hudEnabled = !profiles.hudEnabled
                syncHud()
            },
        )

    override fun onResume() {
        super.onResume()
        refreshCameraList()
        // The overlay permission is granted in system settings, so this is the only place the
        // grant can become visible.
        syncHud()
        if (profiles.isEnabled && fineLocationGranted) ServiceController.start(this)
    }

    override fun onDestroy() {
        hudOverlay?.hide()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loading

    private fun refreshCameraList() {
        val cameraStore = SpeedCamApp.cameraStore(applicationContext)
        store = cameraStore
        lifecycleScope.launch {
            runCatching {
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

    // ---------------------------------------------------------------------- HUD

    private fun syncHud() {
        val wanted = profiles.hudEnabled && HudOverlay.canDrawOverlays(this)
        if (wanted && hudOverlay?.isShowing != true) {
            hudOverlay =
                HudOverlay(
                    context = applicationContext,
                    positionStore = hudPositions,
                    onBlockNearest = ::blockNearest,
                    onMuteNearest = ::muteNearest,
                    onReportHazard = ::reportHazardFromHud,
                ).also { it.show() }
        } else if (!wanted) {
            hudOverlay?.hide()
            hudOverlay = null
        }
    }

    /** Long-press on the HUD: silence this camera for good. */
    private fun blockNearest() {
        val cameraStore = store ?: return
        val nearest = DetectionState.state.value.nearest ?: return
        lifecycleScope.launch {
            runCatching { cameraStore.block(LatLon(nearest.latitude, nearest.longitude)) }
                .onFailure { Log.w(TAG, "Could not block: ${it.message}") }
            blocked.value = cameraStore.overrides.blocked
        }
    }

    /** Tap on the HUD: silence this camera for the rest of the session. */
    private fun muteNearest() {
        DetectionState.update { it.copy(muted = !it.muted) }
        Log.i(TAG, "HUD mute toggled.")
    }

    /** Double-tap on the HUD: reports a hazard at the active camera or current position. */
    private fun reportHazardFromHud() {
        val state = DetectionState.state.value
        val nearest = state.nearest
        val lat = nearest?.latitude ?: state.currentLat ?: return
        val lon = nearest?.longitude ?: state.currentLon ?: return

        val report =
            HazardReportEntity(
                lat = lat,
                lon = lon,
                headingDeg = state.currentBearingDeg,
                cameraId = nearest?.cameraId,
                reportType =
                    if (nearest != null) {
                        HazardReportType.FALSE_POSITIVE.ordinal
                    } else {
                        HazardReportType.NEW_CAMERA.ordinal
                    },
                speedLimitKmh = state.activeSection?.limitKmh ?: nearest?.limitKmh,
                comment = if (nearest != null) "Reported via HUD" else "New camera via HUD",
            )

        lifecycleScope.launch {
            val cameraStore = store ?: SpeedCamApp.cameraStore(applicationContext)
            cameraStore.submitReport(report)
            HazardReportSyncWorker.triggerImmediate(applicationContext)
            Toast.makeText(this@MainActivity, "Hazard report queued in outbox", Toast.LENGTH_SHORT).show()
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
    val profiles = state.profiles
    SettingsScreen(
        state =
            SettingsState(
                enabled = profiles.isEnabled,
                quickSetting = profiles.quickSetting,
                captureDistanceKm = profiles.captureDistanceKm,
                transport = profiles.transport,
                hudEnabled = profiles.hudEnabled,
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
            ),
        actions =
            SettingsActions(
                onEnabledChange = actions.onEnableChange,
                onQuickSettingChange = { profiles.quickSetting = it },
                onCaptureDistanceChange = { profiles.captureDistanceKm = it },
                onTransportChange = { profiles.transport = it },
                onHudEnabledChange = { profiles.hudEnabled = it },
                onRequestHudPermission = actions.onRequestHudPermission,
                onResetHudPosition = actions.onResetHudPosition,
                onOutputModeChange = { profiles.outputMode = it },
                onSilentDuringCallChange = { profiles.silentDuringCall = it },
                onAutoUpdateChange = actions.onAutoUpdateChange,
                onCheckForUpdates = actions.onCheckForUpdates,
                onSyncHazardOutbox = actions.onSyncHazardOutbox,
                onClearSyncedReports = actions.onClearSyncedReports,
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
