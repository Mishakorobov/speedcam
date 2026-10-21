package com.speedcam.app.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto session for SpeedCam Radar.
 */
class SpeedCamCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SpeedCamDashboardCarScreen(carContext)
}
