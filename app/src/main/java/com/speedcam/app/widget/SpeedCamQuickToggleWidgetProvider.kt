package com.speedcam.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * 1x1 Compact Quick-Toggle Widget Provider for one-tap radar activation/deactivation.
 */
class SpeedCamQuickToggleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        SpeedCamWidgetManager.updateQuickToggleWidgets(context, appWidgetManager, appWidgetIds)
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
