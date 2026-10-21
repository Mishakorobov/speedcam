package com.speedcam.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.speedcam.wear.haptics.WearHapticController
import com.speedcam.wear.state.WearStateStore
import com.speedcam.wear.ui.WearSettingsScreen
import com.speedcam.wear.ui.WearSpeedometerScreen

/**
 * Main activity for the Wear OS motorcycle companion application.
 */
class MainActivity : ComponentActivity() {
    private lateinit var hapticController: WearHapticController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WearStateStore.initialize(this)
        hapticController = WearHapticController(this)

        setContent {
            WearAppRoot(
                hapticController = hapticController,
                onSendCommand = { cmd -> WearStateStore.sendCommandToPhone(this, cmd) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ping phone for immediate state update upon opening watch app
        WearStateStore.sendCommandToPhone(this, "PING")
    }
}

private enum class WearScreen {
    SPEEDOMETER,
    SETTINGS,
}

@Composable
private fun WearAppRoot(
    hapticController: WearHapticController,
    onSendCommand: (String) -> Unit,
) {
    val telemetry by WearStateStore.telemetry.collectAsStateWithLifecycle()
    val hapticProfile by WearStateStore.hapticProfile.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf(WearScreen.SPEEDOMETER) }

    when (currentScreen) {
        WearScreen.SPEEDOMETER ->
            WearSpeedometerScreen(
                telemetry = telemetry,
                onOpenSettings = { currentScreen = WearScreen.SETTINGS },
                onToggleService = {
                    val cmd = if (telemetry.running) "STOP" else "START"
                    onSendCommand(cmd)
                },
                onToggleMute = { onSendCommand("MUTE") },
            )

        WearScreen.SETTINGS ->
            WearSettingsScreen(
                currentProfile = hapticProfile,
                onSelectProfile = { WearStateStore.setHapticProfile(it) },
                onTestVibration = { hapticController.vibrateTest(hapticProfile) },
                onBack = { currentScreen = WearScreen.SPEEDOMETER },
            )
    }
}
