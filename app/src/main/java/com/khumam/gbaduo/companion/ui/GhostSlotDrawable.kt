package com.khumam.gbaduo.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.khumam.gbaduo.library.ui.Pixel

/**
 * An empty slot: a dashed outline on the page, no fill. It is the "room for one more" at the end of the pack
 * carousel, so it doesn't pass for a pack that failed to load. Lights up yellow while it has focus.
 */
class GhostSlotDrawable(context: Context) : Drawable() {
    private val stroke = Pixel.dp(context, 3f)
    private val dash = Pixel.dp(context, 8f)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
    }
    private val glow = Paint().apply { style = Paint.Style.FILL; color = FOCUS_GLOW }
    private val rect = RectF()
    private var focused = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_focused in state
        if (now == focused) return false
        focused = now
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val half = stroke / 2
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        if (focused) canvas.drawRect(rect, glow)
        outline.color = if (focused) Pixel.YELLOW else Pixel.ON_INK_SOFT
        canvas.drawRect(rect, outline)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** Yellow at about 14%. */
        const val FOCUS_GLOW = 0x24EAC34F
    }
}
