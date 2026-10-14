package com.khumam.gbaduo.companion.android

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose needs a lifecycle and a saved-state registry from its host view. An
 * Activity provides them; a Presentation (a Dialog on another display) doesn't,
 * so this supplies them for the companion screen's ComposeView.
 */
internal class PresentationComposeOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Call once the host view exists (in the Presentation's onCreate). */
    fun attach(host: View) {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        host.setViewTreeLifecycleOwner(this)
        host.setViewTreeSavedStateRegistryOwner(this)
    }

    /** Call when the Presentation is dismissed; disposes the compositions. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
