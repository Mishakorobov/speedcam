package com.speedcam.app.hud

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The owners a [androidx.compose.ui.platform.ComposeView] insists on finding.
 *
 * A ComposeView mounted in an ordinary activity gets all three from the view tree, because
 * the activity supplies them. An overlay window is a bare [android.widget.FrameLayout] over
 * nothing, so Compose throws `IllegalStateException: ViewTreeLifecycleOwner not found` the
 * first time it tries to build a recomposer - an outright crash, not a degraded HUD.
 *
 * The overlay therefore supplies its own. It is not an activity and outlives one, so it
 * drives the state itself rather than borrowing an activity's lifecycle.
 */
class HudLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val registry: LifecycleRegistry = LifecycleRegistry(this)
    private val store: ViewModelStore = ViewModelStore()
    private val savedState: SavedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Publishes the owners on [view]'s view tree so Compose can find them by walking up. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    fun start() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Must be called before the view detaches, or the recomposer never stops. */
    fun stop() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
