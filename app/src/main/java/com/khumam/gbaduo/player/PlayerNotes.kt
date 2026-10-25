package com.khumam.gbaduo.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable

/**
 * One-line notes over the game ("Saved to slot 2"), top-left so they never meet the
 * RetroAchievements popups top-right. Same ink panel look. Fade only, no slide: the
 * game surface sits below the window, and the window drops the part of a view that
 * wasn't opaque when it was laid out, so the region is gathered again on show and remove.
 * A new note replaces the current one. Main thread only.
 */
class PlayerNotes(private val container: FrameLayout) {

    private val context = container.context
    private val motion = Pixel.motionEnabled(context)
    private var showing: View? = null

    private fun dp(v: Float) = (v * context.resources.displayMetrics.density).toInt()

    fun show(text: String) {
        remove()
        val view = TextView(context).apply {
            this.text = text
            typeface = Fonts.body(context, 600)
            textSize = 14f
            setTextColor(Pixel.ON_INK)
            val box = PixelBoxDrawable(context, Pixel.INK, shadowDp = 4f, borderDp = 3f, border = Pixel.YELLOW)
            background = box
            setPadding(dp(12f), dp(8f), dp(12f) + box.shadow.toInt(), dp(8f) + box.shadow.toInt())
            alpha = if (motion) 0f else 1f
        }
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(16f), dp(16f), 0, 0) })
        showing = view
        container.requestTransparentRegion(view)
        if (motion) view.animate().alpha(1f).setDuration(FADE_MS).start()
        view.postDelayed({
            if (showing !== view) return@postDelayed
            if (motion) view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (showing === view) remove() }.start()
            else remove()
        }, SHOWN_MS)
    }

    fun remove() {
        showing?.let {
            it.animate().cancel()
            container.removeView(it)
            container.requestTransparentRegion(container)
        }
        showing = null
    }

    private companion object {
        const val SHOWN_MS = 1500L
        const val FADE_MS = 150L
    }
}
