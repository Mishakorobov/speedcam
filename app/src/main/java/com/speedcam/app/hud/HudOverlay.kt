package com.speedcam.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.doOnLayout
import com.speedcam.app.hud.HudPositionStore
import com.speedcam.app.hud.ui.HudContent

/**
 * The floating hazard display.
 *
 * A `TYPE_APPLICATION_OVERLAY` window, which is the same choice the original makes and the
 * only one that survives the app not being in the foreground: a driver needs the warning
 * while a map is open, not only inside SpeedCam.
 *
 * Two details are taken from the original and are not obvious:
 *
 *  - **`FLAG_NOT_FOCUSABLE`.** Without it the overlay steals input from whatever is behind
 *    it, which for a full-screen navigation app means the driver cannot tap the map.
 *  - **Gravity `TOP or START` with absolute x and y**, rather than any of the eight screen
 *    corners. A corner gravity makes a dragged widget snap as it crosses a boundary, and the
 *    whole point of dragging is that the driver puts it where they can see it.
 */
class HudOverlay(
    private val context: Context,
    private val positionStore: HudPositionStore,
    private val onBlockNearest: (() -> Unit)? = null,
    private val onMuteNearest: (() -> Unit)? = null,
) {
    private val lifecycleOwner = HudLifecycleOwner()

    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = container != null

    /**
     * Adds the window. Safe to call twice; the second call is ignored.
     *
     * @return false when the overlay permission has not been granted, which is the common
     *   case and needs saying plainly rather than failing with a SecurityException.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (container != null) return true

        val manager =
            runCatching { context.getSystemService(WindowManager::class.java) }.getOrNull()
        if (manager == null) {
            Log.w(TAG, "No WindowManager; the HUD cannot be shown.")
            return false
        }

        if (!canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW is not granted; the HUD cannot be shown.")
            return false
        }

        val layout = buildLayout()
        val windowParams = buildParams()

        // x and y are only knowable once the view has been measured, so both are applied on
        // the first layout pass rather than guessed here.
        layout.doOnLayout {
            val resolved = windowParams
            resolved.x = positionStore.xOrDefault(layout.width)
            resolved.y = positionStore.yOrDefault(layout.height)
            runCatching { manager.updateViewLayout(layout, resolved) }
        }

        return try {
            // Before addView: the ComposeView reads these owners the moment its composition
            // starts, and an overlay window is a bare FrameLayout with no activity above it.
            lifecycleOwner.attachTo(layout)
            lifecycleOwner.start()
            manager.addView(layout, windowParams)
            windowManager = manager
            container = layout
            params = windowParams
            true
        } catch (e: Exception) {
            // A window can be refused at the last moment - most often when the overlay
            // permission was revoked between the check and the add.
            Log.w(TAG, "Could not add the HUD window: ${e.message}")
            false
        }
    }

    /** Moves the window. Used by the drag gesture and by the settings preview. */
    fun updatePosition(
        x: Int,
        y: Int,
    ) {
        val layout = container ?: return
        val resolved = params ?: return
        resolved.x = x
        resolved.y = y
        runCatching { windowManager?.updateViewLayout(layout, resolved) }
            .onFailure { Log.w(TAG, "Could not move the HUD: ${it.message}") }
    }

    /** Persists the current position so it survives a restart. */
    fun persistPosition() {
        val resolved = params ?: return
        positionStore.save(resolved.x, resolved.y)
    }

    fun hide() {
        val layout = container ?: return
        runCatching { windowManager?.removeView(layout) }
        // Detach first, then stop: the reverse order leaves the recomposer running against a
        // view that is no longer in a window.
        lifecycleOwner.stop()
        container = null
        params = null
    }

    private fun buildLayout(): FrameLayout {
        val compose =
            ComposeView(context).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(3f),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                    ) {
                        HudContent(
                            onDragTo = ::onDrag,
                            onCommitDrag = ::persistPosition,
                            onBlock = { onBlockNearest?.invoke() },
                            onMute = { onMuteNearest?.invoke() },
                        )
                    }
                }
            }

        return FrameLayout(context).apply {
            addView(
                compose,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        return WindowManager
            .LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // Absolute positioning: see the class comment.
                gravity = Gravity.TOP or Gravity.START
            }
    }

    /**
     * Handles a drag reported by the Compose layer.
     *
     * A dead zone is applied here rather than in the gesture detector because the window can
     * move by a pixel or two while the view is being laid out, and without the zone the HUD
     * would creep on every tap.
     */
    private fun onDrag(
        x: Int,
        y: Int,
    ) {
        val layout = container ?: return
        val resolved = params ?: return
        if (layout.width == 0) return

        val anchorX = layout.width / 2
        val anchorY = layout.height / 2
        val rawX = (x + anchorX).coerceIn(0, context.resources.displayMetrics.widthPixels - layout.width)
        val rawY = (y + anchorY).coerceIn(0, context.resources.displayMetrics.heightPixels - layout.height)

        updatePosition(rawX, rawY)
    }

    companion object {
        private const val TAG = "HudOverlay"

        /** True when the driver has granted "display over other apps". */
        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
    }
}
