package com.speedcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController

/**
 * The only screen, for now.
 *
 * Stage 4 needs a way to turn detection on from inside the app, and it cannot live anywhere
 * else: on Android 14+ a `location`-typed foreground service may only be started by the app's
 * own uid. `adb shell am start-foreground-service` fails with "Requires permission not exported
 * from uid 10055", so without this the service is unreachable and cannot be verified at all.
 *
 * The full settings UI is Stage 6; this is the minimum that makes the service operable and
 * testable on a real device.
 */
class MainActivity : ComponentActivity() {
    private val profiles by lazy { ProfileStore(this) }

    /** Tracks the background-location grant, which cannot be requested together with fine. */
    private var fineLocationGranted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            fineLocationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            continueStartup()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestLocationThenStart()

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    StatusScreen(Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The driver may have switched detection off through the notification while we were
        // away, in which case this deliberately does nothing.
        if (profiles.isEnabled && fineLocationGranted) {
            ServiceController.start(this)
        }
    }

    /**
     * Asks for what detection needs, then starts.
     *
     * `POST_NOTIFICATIONS` is requested from Android 13. Without it the foreground notification
     * is never shown, which on a driving app means the user has no evidence anything is running,
     * so it is asked for alongside location rather than later.
     */
    private fun requestLocationThenStart() {
        val wanted =
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
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
     * Continues the startup sequence once foreground location has been dealt with.
     *
     * Background location is a separate grant from Android 10, and Android 11+ refuses to ask
     * for it in the same call as foreground location — so it is requested only after the fine
     * grant has actually landed, and startup proceeds whether or not it succeeds. Detection
     * works with foreground location alone while the app is open, which is enough to verify a
     * drive; the background grant is what keeps it running once the screen is off.
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

@Suppress("FunctionName") // Compose requires @Composable functions to start uppercase.
@Composable
private fun StatusScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SpeedCam",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text =
                "Detection runs as a foreground service. Its notification shows the nearest " +
                    "hazard, GPS loss and recovery. Stage 6 brings the real UI.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
