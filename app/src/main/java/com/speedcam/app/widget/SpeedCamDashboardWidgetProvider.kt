package com.speedcam.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * 2x1 Automotive Dashboard Widget Provider displaying real-time speed, camera alerts,
 * and a dedicated one-tap start/stop toggle.
 */
class SpeedCamDashboardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SpeedCamWidgetManager.updateDashboardWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        if (intent.action == SpeedCamWidgetManager.ACTION_TOGGLE_DETECTION) {
            SpeedCamWidgetManager.handleToggle(context)
        }
    }
}
