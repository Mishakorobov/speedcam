package com.speedcam.app.hud

import android.content.Context

/**
 * Where the HUD sits on screen.
 *
 * The original persists `backgroundWidgetX` and `backgroundWidgetY` in SharedPreferences and
 * treats `0/0` as "never positioned", falling back to bottom-centre. That quirk is kept
 * rather than fixed: a fresh install that lands at the top-left corner is worse than one
 * that lands where the driver expects, and it is the behaviour being ported.
 */
class HudPositionStore(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val displayMetrics
        get() = appContext.resources.displayMetrics

    private val appContext: Context = context.applicationContext

    var x: Int
        get() = prefs.getInt(KEY_X, 0)
        private set(value) = prefs.edit().putInt(KEY_X, value).apply()

    var y: Int
        get() = prefs.getInt(KEY_Y, 0)
        private set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    /** True once the driver has dragged it, which is what distinguishes 0/0 from bottom-centre. */
    val isPositioned: Boolean get() = x != 0 || y != 0

    fun save(
        x: Int,
        y: Int,
    ) {
        prefs
            .edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    fun clear() {
        prefs
            .edit()
            .remove(KEY_X)
            .remove(KEY_Y)
            .apply()
    }

    /** The x to use for a HUD [width] pixels wide. */
    fun xOrDefault(width: Int): Int = if (isPositioned) x else defaultX(width)

    /** The y to use for a HUD [height] pixels tall. */
    fun yOrDefault(height: Int): Int = if (isPositioned) y else defaultY(height)

    /** Bottom-centre, as the original computes it: half the screen minus half the bubble. */
    private fun defaultX(width: Int): Int = (displayMetrics.widthPixels / 2 - width / 2).coerceAtLeast(0)

    private fun defaultY(height: Int): Int = (displayMetrics.heightPixels / 2 - height).coerceAtLeast(0)

    companion object {
        const val NAME = "speedcam-hud"
        const val KEY_X = "hudX"
        const val KEY_Y = "hudY"
    }
}
