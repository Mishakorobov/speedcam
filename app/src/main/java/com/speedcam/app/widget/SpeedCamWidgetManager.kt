package com.speedcam.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.speedcam.app.MainActivity
import com.speedcam.app.R
import com.speedcam.service.DetectionState
import com.speedcam.service.DetectionUiState
import com.speedcam.service.HazardView
import com.speedcam.service.ProfileStore
import com.speedcam.service.ServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Manages home screen widgets (1x1 Quick Toggle and 2x1 Dashboard).
 */
object SpeedCamWidgetManager {
    const val ACTION_TOGGLE_DETECTION = "com.speedcam.app.widget.ACTION_TOGGLE_DETECTION"
    private const val REQUEST_CODE_TOGGLE_QUICK = 101
    private const val REQUEST_CODE_TOGGLE_DASHBOARD = 102
    private const val REQUEST_CODE_OPEN_APP = 103

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initialized = false

    /**
     * Initializes background observation of detection state to update home screen widgets.
     */
    fun init(appContext: Context) {
        if (initialized) return
        initialized = true

        scope.launch {
            DetectionState.state.collect { state ->
                updateAllWidgets(appContext, state)
            }
        }
    }

    /**
     * Toggles speed camera radar detection from widget clicks.
     */
    fun handleToggle(context: Context) {
        val appContext = context.applicationContext
        val profiles = ProfileStore(appContext)
        val isRunning = DetectionState.state.value.running

        if (isRunning) {
            ServiceController.stop(appContext)
            profiles.isEnabled = false
            DetectionState.update { it.copy(running = false) }
        } else {
            profiles.isEnabled = true
            ServiceController.start(appContext)
            DetectionState.update { it.copy(running = true) }
        }
        updateAllWidgets(appContext)
    }

    /**
     * Pushes current detection state to all placed 1x1 and 2x1 widgets.
     */
    fun updateAllWidgets(
        context: Context,
        state: DetectionUiState = DetectionState.state.value,
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val quickIds =
            manager.getAppWidgetIds(
                ComponentName(context, SpeedCamQuickToggleWidgetProvider::class.java),
            )
        if (quickIds.isNotEmpty()) {
            val quickViews = buildQuickToggleViews(context, state)
            manager.updateAppWidget(quickIds, quickViews)
        }

        val dashIds =
            manager.getAppWidgetIds(
                ComponentName(context, SpeedCamDashboardWidgetProvider::class.java),
            )
        if (dashIds.isNotEmpty()) {
            val dashViews = buildDashboardViews(context, state)
            manager.updateAppWidget(dashIds, dashViews)
        }
    }

    fun updateQuickToggleWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: DetectionUiState = DetectionState.state.value,
    ) {
        val views = buildQuickToggleViews(context, state)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    fun updateDashboardWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        state: DetectionUiState = DetectionState.state.value,
    ) {
        val views = buildDashboardViews(context, state)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    fun buildQuickToggleViews(
        context: Context,
        state: DetectionUiState,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_toggle)
        if (state.running) {
            views.setInt(R.id.widget_quick_toggle_root, "setBackgroundResource", R.drawable.widget_bg_active)
            views.setInt(R.id.widget_power_icon, "setColorFilter", SpeedCamWidgetFormatter.COLOR_SAFE)
            views.setInt(R.id.widget_status_dot, "setImageResource", R.drawable.widget_dot_active)
            val speedText =
                SpeedCamWidgetFormatter.formatQuickSpeed(
                    speedKmh = state.speedKmh,
                    defaultOnText = context.getString(R.string.widget_status_on),
                    unitText = context.getString(R.string.widget_speed_unit),
                )
            views.setTextViewText(R.id.widget_status_text, speedText)
            views.setTextColor(R.id.widget_status_text, SpeedCamWidgetFormatter.COLOR_SAFE)
        } else {
            views.setInt(R.id.widget_quick_toggle_root, "setBackgroundResource", R.drawable.widget_bg_dark)
            views.setInt(R.id.widget_power_icon, "setColorFilter", SpeedCamWidgetFormatter.COLOR_TEXT_MUTED)
            views.setInt(R.id.widget_status_dot, "setImageResource", R.drawable.widget_dot_inactive)
            views.setTextViewText(R.id.widget_status_text, context.getString(R.string.widget_status_off))
            views.setTextColor(R.id.widget_status_text, SpeedCamWidgetFormatter.COLOR_TEXT_SECONDARY)
        }

        val toggleIntent =
            Intent(context, SpeedCamQuickToggleWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_DETECTION
            }
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_TOGGLE_QUICK,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_quick_toggle_root, pendingIntent)
        return views
    }

    fun buildDashboardViews(
        context: Context,
        state: DetectionUiState,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_dashboard)
        bindDashboardToggle(context, views, state.running)
        bindDashboardInfo(context, views, state)
        return views
    }

    private fun bindDashboardToggle(
        context: Context,
        views: RemoteViews,
        isRunning: Boolean,
    ) {
        if (isRunning) {
            views.setInt(R.id.widget_btn_toggle_inner, "setBackgroundResource", R.drawable.widget_button_toggle_on)
            views.setInt(R.id.widget_btn_icon, "setColorFilter", SpeedCamWidgetFormatter.COLOR_SAFE)
            views.setTextViewText(R.id.widget_btn_label, context.getString(R.string.widget_btn_stop))
            views.setTextColor(R.id.widget_btn_label, SpeedCamWidgetFormatter.COLOR_SAFE)
        } else {
            views.setInt(R.id.widget_btn_toggle_inner, "setBackgroundResource", R.drawable.widget_button_toggle_off)
            views.setInt(R.id.widget_btn_icon, "setColorFilter", SpeedCamWidgetFormatter.COLOR_TEXT_SECONDARY)
            views.setTextViewText(R.id.widget_btn_label, context.getString(R.string.widget_btn_start))
            views.setTextColor(R.id.widget_btn_label, SpeedCamWidgetFormatter.COLOR_TEXT_SECONDARY)
        }

        val toggleIntent =
            Intent(context, SpeedCamDashboardWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_DETECTION
            }
        val pendingToggle =
            PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_TOGGLE_DASHBOARD,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_btn_toggle, pendingToggle)
    }

    private fun bindDashboardInfo(
        context: Context,
        views: RemoteViews,
        state: DetectionUiState,
    ) {
        val appIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingOpen =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_APP,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_info_container, pendingOpen)

        if (!state.running) {
            views.setViewVisibility(R.id.widget_inactive_layout, View.VISIBLE)
            views.setViewVisibility(R.id.widget_active_clear_layout, View.GONE)
            views.setViewVisibility(R.id.widget_active_hazard_layout, View.GONE)
            return
        }

        val nearest = state.nearest
        if (nearest != null) {
            bindHazardInfo(views, nearest, state.overLimit)
        } else {
            bindClearRoadInfo(context, views, state.speedKmh)
        }
    }

    private fun bindHazardInfo(
        views: RemoteViews,
        nearest: HazardView,
        overLimit: Boolean,
    ) {
        views.setViewVisibility(R.id.widget_inactive_layout, View.GONE)
        views.setViewVisibility(R.id.widget_active_clear_layout, View.GONE)
        views.setViewVisibility(R.id.widget_active_hazard_layout, View.VISIBLE)

        val limit = nearest.limitKmh
        if (limit != null && limit > 0) {
            views.setTextViewText(R.id.widget_limit_text, limit.toString())
            views.setViewVisibility(R.id.widget_limit_badge, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_limit_badge, View.GONE)
        }

        val distText = SpeedCamWidgetFormatter.formatDistance(nearest.distanceM)
        views.setTextViewText(R.id.widget_hazard_distance, distText)

        val hazardColor = SpeedCamWidgetFormatter.resolveHazardColor(overLimit)
        views.setTextColor(R.id.widget_hazard_distance, hazardColor)
        views.setTextViewText(R.id.widget_hazard_type, nearest.kind.name.replace('_', ' '))
    }

    private fun bindClearRoadInfo(
        context: Context,
        views: RemoteViews,
        speedKmh: Double,
    ) {
        views.setViewVisibility(R.id.widget_inactive_layout, View.GONE)
        views.setViewVisibility(R.id.widget_active_clear_layout, View.VISIBLE)
        views.setViewVisibility(R.id.widget_active_hazard_layout, View.GONE)

        val speed = speedKmh.roundToInt()
        views.setTextViewText(R.id.widget_speed_value, speed.toString())
        views.setTextViewText(R.id.widget_road_status, context.getString(R.string.widget_road_clear))
    }
}
