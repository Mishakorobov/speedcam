package com.speedcam.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.doOnLayout
import com.speedcam.app.hud.ui.HudContent
import com.speedcam.service.ProfileStore
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating hazard display overlay over other applications (`SYSTEM_ALERT_WINDOW`).
 *
 * A `TYPE_APPLICATION_OVERLAY` window that floats unobtrusively over Google Maps,
 * Yandex Navigator, or 2GIS, providing real-time speed, camera alerts, and tactile gestures.
 */
class HudOverlay(
    private val context: Context,
    private val positionStore: HudPositionStore,
    private val onBlockNearest: (() -> Unit)? = null,
    private val onMuteNearest: (() -> Unit)? = null,
    private val onReportHazard: (() -> Unit)? = null,
) {
    private val lifecycleOwner = HudLifecycleOwner()
    private val isDraggingState = mutableStateOf(false)

    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = container != null

    /**
     * Adds the floating window to the WindowManager.
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

        layout.doOnLayout {
            val resolved = windowParams
            resolved.x = positionStore.xOrDefault(layout.width)
            resolved.y = positionStore.yOrDefault(layout.height)
            runCatching { manager.updateViewLayout(layout, resolved) }
        }

        return try {
            lifecycleOwner.attachTo(layout)
            lifecycleOwner.start()
            manager.addView(layout, windowParams)
            windowManager = manager
            container = layout
            params = windowParams
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not add the HUD window: ${e.message}")
            false
        }
    }

    /** Moves the window by [dx] and [dy] pixels, clamping to screen boundaries. */
    fun onDragDelta(
        dx: Int,
        dy: Int,
    ) {
        val layout = container ?: return
        val resolved = params ?: return
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - layout.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - layout.height).coerceAtLeast(0)
        resolved.x = (resolved.x + dx).coerceIn(0, maxX)
        resolved.y = (resolved.y + dy).coerceIn(0, maxY)
        runCatching { windowManager?.updateViewLayout(layout, resolved) }
    }

    /** Moves the window to exact coordinates [x] and [y]. */
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

    /** Resets the floating window to the default screen position. */
    fun resetToDefaultPosition() {
        val layout = container ?: return
        val resolved = params ?: return
        resolved.x = positionStore.xOrDefault(layout.width)
        resolved.y = positionStore.yOrDefault(layout.height)
        runCatching { windowManager?.updateViewLayout(layout, resolved) }
    }

    /** Persists the current position so it survives a restart. */
    fun persistPosition() {
        val resolved = params ?: return
        positionStore.save(resolved.x, resolved.y)
    }

    fun hide() {
        val layout = container ?: return
        runCatching { windowManager?.removeView(layout) }
        lifecycleOwner.stop()
        container = null
        params = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLayout(): FrameLayout {
        val displayDensity =
            Density(
                density = context.resources.displayMetrics.density,
                fontScale = context.resources.configuration.fontScale,
            )
        val profiles = ProfileStore(context)
        val compose =
            ComposeView(context).apply {
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides displayDensity,
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                    ) {
                        HudContent(
                            isDragging = isDraggingState.value,
                            autoDimOnClearRoad = profiles.hudAutoHide,
                        )
                    }
                }
            }

        val rootLayout =
            FrameLayout(context).apply {
                addView(
                    compose,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

        val touchListener =
            HudTouchListener(
                context = context,
                onDragDelta = ::onDragDelta,
                onCommitDrag = ::persistPosition,
                onTap = { onMuteNearest?.invoke() },
                onDoubleTap = {
                    rootLayout.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onReportHazard?.invoke()
                },
                onLongPress = {
                    rootLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onBlockNearest?.invoke()
                },
                onDraggingChanged = { isDraggingState.value = it },
            )
        compose.setOnTouchListener(touchListener)
        rootLayout.setOnTouchListener(touchListener)

        return rootLayout
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
                gravity = Gravity.TOP or Gravity.START
            }
    }

    companion object {
        private const val TAG = "HudOverlay"

        /** True when the driver has granted "display over other apps". */
        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
    }
}

/**
 * Handles automotive touch interactions on the floating HUD overlay.
 */
private class HudTouchListener(
    context: Context,
    private val onDragDelta: (dx: Int, dy: Int) -> Unit,
    private val onCommitDrag: () -> Unit,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onDraggingChanged: (Boolean) -> Unit,
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTap()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTap()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    onLongPress()
                }
            },
        )

    override fun onTouch(
        v: View,
        event: MotionEvent,
    ): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = event.rawX
                initialY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                val dist = hypot(event.rawX - initialX, event.rawY - initialY)
                if (dist > touchSlop) {
                    if (!isDragging) {
                        isDragging = true
                        onDraggingChanged(true)
                    }
                    onDragDelta(dx.roundToInt(), dy.roundToInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    onCommitDrag()
                    isDragging = false
                    onDraggingChanged(false)
                }
            }
        }
        return true
    }
}
