package com.khumam.gbaduo.companion.android

import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.khumam.gbaduo.companion.CompanionController

/**
 * Compose needs a lifecycle and a saved-state registry from its host view. The
 * companion's hosts (a Presentation, a plain platform Activity) don't provide
 * them, so this supplies them for the companion screen's ComposeView.
 */
internal class ComposeHostOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Call once the host view exists (in the host's onCreate). */
    fun attach(host: View) {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        host.setViewTreeLifecycleOwner(this)
        host.setViewTreeSavedStateRegistryOwner(this)
    }

    /** Call when the host goes away; disposes the compositions. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** A ComposeView showing [controller]'s screen, owned by [owner]. */
internal fun companionView(context: Context, owner: ComposeHostOwner, controller: CompanionController): ComposeView =
    ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent { controller.Content() }
        owner.attach(this)
    }

/**
 * Sets up a window hosting the companion screen:
 * - **Not focusable.** Touching the companion never pulls controller focus away
 *   from the game on the other display; touch input still works. It still talks to the
 *   keyboard ([WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM]), so a pack's text field can be typed into.
 * - **Full screen.** The whole display belongs to the companion: no status bar
 *   and no navigation bar or gesture handle over the pack's layout. A swipe from
 *   an edge still shows them briefly. Uses only platform APIs (minSdk 30).
 */
internal fun Window.setUpForCompanion() {
    addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    attributes = attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    setDecorFitsSystemWindows(false)
    // Window.insetsController reads the decor without creating it, and a Presentation
    // calls this before setContentView, when there's no decor yet (NPE on Android 13).
    // Window.decorView creates it; its controller is the same one.
    decorView.windowInsetsController?.apply {
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsets.Type.systemBars())
    }
}

/**
 * Lets a companion window take the keyboard while one of its text fields is being edited, and puts it back to not
 * focusable afterwards. A window that is not focusable can never be typed into, and touching a focusable window on
 * the second screen only moves Android's focus there, where the game's buttons would no longer reach the game, so
 * it is focusable for exactly as long as a field has focus (the hosts forward key events to the game either way).
 */
internal fun Window.setTextEntry(editing: Boolean) {
    val both = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    if (editing) {
        clearFlags(both)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    } else {
        addFlags(both)
    }
}
