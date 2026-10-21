package com.speedcam.app.widget

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure formatting and presentation logic for home screen AppWidgets.
 */
object SpeedCamWidgetFormatter {
    const val COLOR_DANGER = 0xFFEF4444.toInt()
    const val COLOR_SAFE = 0xFF10B981.toInt()
    const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()
    const val COLOR_TEXT_MUTED = 0xFF64748B.toInt()
    const val COLOR_SIGN_TEXT = 0xFF111827.toInt()

    /** Formats speed display for the 1x1 quick toggle widget. */
    fun formatQuickSpeed(
        speedKmh: Double,
        defaultOnText: String,
        unitText: String,
    ): String =
        if (speedKmh > 2.0) {
            "${speedKmh.roundToInt()} $unitText"
        } else {
            defaultOnText
        }

    /** Formats hazard distance (e.g. "350 m" or "1.5 km"). */
    fun formatDistance(
        distanceM: Double,
        locale: Locale = Locale.US,
    ): String {
        val rounded = distanceM.roundToInt()
        return if (rounded >= 1000) {
            String.format(locale, "%.1f km", rounded / 1000.0)
        } else {
            "$rounded m"
        }
    }

    /** Resolves text color for hazard distance based on overspeed state. */
    fun resolveHazardColor(overLimit: Boolean): Int = if (overLimit) COLOR_DANGER else COLOR_TEXT_PRIMARY
}
