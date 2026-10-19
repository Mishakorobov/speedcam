package com.speedcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.hud.HudOverlay
import com.speedcam.app.hud.HudPositionStore
import com.speedcam.core.data.CameraStore
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(
                        state =
                            ShellState(
                                ui = DetectionState.state.collectAsStateWithLifecycle().value,
                                cameras = cameras.collectAsStateWithLifecycle().value,
                                blocked = blocked.collectAsStateWithLifecycle().value,
                                profiles = profiles,
                                hudPermissionGranted = HudOverlay.canDrawOverlays(this),
                            ),
                        actions =
                            ShellActions(
                                onEnableChange = ::setDetectionEnabled,
                                onUnblock = ::unblock,
                                onRequestHudPermission = ::requestOverlayPermission,
                                onResetHudPosition = { hudPositions.clear() },
                            ),
                    )
                }
            }
        }
    }

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
        val cameraStore = store ?: CameraStore.open(applicationContext).also { store = it }
        lifecycleScope.launch {
            runCatching {
                cameraStore.refreshOverrides()
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
    STATUS("Status"),
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
)

/** What the shell does, one callback per screen-level action. */
private data class ShellActions(
    val onEnableChange: (Boolean) -> Unit,
    val onUnblock: (CameraRow) -> Unit,
    val onRequestHudPermission: () -> Unit,
    val onResetHudPosition: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    state: ShellState,
    actions: ShellActions,
) {
    var tab by remember { mutableStateOf(Tab.STATUS) }
    val profiles = state.profiles

    Scaffold(
        topBar = { TopAppBar(title = { Text("SpeedCam") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.label.take(1)) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.STATUS -> StatusTab(state.ui, Modifier.padding(padding))
            Tab.CAMERAS ->
                CameraListScreen(
                    cameras = state.cameras,
                    blocked = state.blocked.map { it.lat to it.lon },
                    onUnblock = actions.onUnblock,
                    modifier = Modifier.padding(padding),
                )

            Tab.SETTINGS ->
                SettingsScreen(
                    state =
                        SettingsState(
                            enabled = profiles.isEnabled,
                            quickSetting = profiles.quickSetting,
                            captureDistanceKm = profiles.captureDistanceKm,
                            transport = profiles.transport,
                            hudEnabled = profiles.hudEnabled,
                            hudPermissionGranted = state.hudPermissionGranted,
                            silentDuringCall = profiles.silentDuringCall,
                            cameraCount = state.ui.cameraCount,
                            dataSourceNote = DATA_SOURCE_NOTE,
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
                            onSilentDuringCallChange = { profiles.silentDuringCall = it },
                        ),
                    modifier = Modifier.padding(padding),
                )
        }
    }
}

/** Attribution is not optional: ODbL 1.0 requires crediting OpenStreetMap. */
private const val DATA_SOURCE_NOTE = "OpenStreetMap, ODbL 1.0. Rebuilt by tools/import."

@Composable
private fun StatusTab(
    state: com.speedcam.service.DetectionUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("SpeedCam", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = if (state.running) "Detecting" else "Stopped",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "${state.speedKmh.toInt()} km/h · ${if (state.hasFix) "GPS ok" else "no fix"}",
            style = MaterialTheme.typography.bodyLarge,
        )

        val nearest = state.nearest
        if (nearest == null) {
            Text("No cameras in range", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text =
                    "Nearest: ${nearest.distanceM.toInt()} m" +
                        (nearest.limitKmh?.let { ", limit $it km/h" } ?: ", limit unknown"),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (state.overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                text = if (nearest.behind) "Oncoming" else "Ahead",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text =
                "Profile: ${state.profileName}" +
                    if (state.headsetConnected) {
                        " · Bluetooth audio"
                    } else {
                        "" +
                            if (state.muted) " · muted" else ""
                    },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )
    }
}
