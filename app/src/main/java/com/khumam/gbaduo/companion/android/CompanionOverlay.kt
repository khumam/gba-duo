package com.khumam.gbaduo.companion.android

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.khumam.gbaduo.companion.CompanionController
import kotlin.math.min

/**
 * The companion screen over the game, for one-screen devices (or when Settings asks for it). Hidden until [show].
 *
 * Packs are laid out for the Thor's second screen, so the companion keeps that screen's shape
 * ([SCREEN_WIDTH] x [SCREEN_HEIGHT]), as big as fits and centred, with the game dimmed around it. A tap on the dimmed
 * part calls [onDismiss]. Touch only: the controller stays with the game's window, which decides what its buttons do
 * while this is up. Only a pack's text field takes focus (to type into it); Compose asks for it when one is tapped.
 */
internal class CompanionOverlay(
    context: Context,
    controller: CompanionController,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {

    private val owner = ComposeHostOwner()

    // A clickable frame around the companion takes the touches the pack's layout leaves, so they don't reach the dim.
    private val screen = FrameLayout(context).apply {
        isClickable = true
        addView(companionView(context, owner, controller), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    init {
        setBackgroundColor(DIM)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isClickable = true
        setOnClickListener { onDismiss() }
        visibility = View.GONE
        addView(screen)
    }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    fun show() {
        visibility = View.VISIBLE
    }

    fun hide() {
        clearFocus()
        visibility = View.GONE
    }

    // Compose takes its lifecycle from the window's root view. As a Presentation the companion is that root; here the
    // root is the game's (a plain Activity, none of its own), so it gets this owner before the ComposeView attaches
    // (a parent's onAttachedToWindow runs before its children's).
    override fun onAttachedToWindow() {
        val root = rootView
        if (root.findViewTreeLifecycleOwner() == null) {
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
        }
        super.onAttachedToWindow()
    }

    /** Disposes the companion's composition; call when the game goes away. */
    fun destroy() {
        owner.destroy()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val scale = min(width.toFloat() / SCREEN_WIDTH, height.toFloat() / SCREEN_HEIGHT)
        (screen.layoutParams as LayoutParams).apply {
            this.width = (SCREEN_WIDTH * scale).toInt()
            this.height = (SCREEN_HEIGHT * scale).toInt()
            gravity = android.view.Gravity.CENTER
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        /** The Thor's second screen in pixels, the shape packs are designed for. */
        const val SCREEN_WIDTH = 1240f
        const val SCREEN_HEIGHT = 1080f
        const val DIM = 0xD9000000.toInt()
    }
}
