package com.khumam.gbaduo.library.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.TypedValue

/**
 * The GBA Duo look (gbaduo-web/DESIGN.md): cream graph paper, 3dp ink outlines and hard
 * shadows with no blur, flat brand colours.
 */
object Pixel {
    const val CREAM = 0xFFFDF6E1.toInt()
    const val GRID = 0x47D6C48C            // rgb(214 196 140 / 0.28)
    const val INK = 0xFF241A45.toInt()
    const val INK_SOFT = 0xFF4A3F6B.toInt()
    const val PURPLE = 0xFF5B4B95.toInt()
    const val PURPLE_LIGHT = 0xFF7B6BB5.toInt()
    const val PURPLE_DARK = 0xFF3D2F6E.toInt()
    const val LAVENDER = 0xFFF1EEFB.toInt()
    const val YELLOW = 0xFFF3C234.toInt()
    const val YELLOW_SOFT = 0xFFFFE9A3.toInt()
    const val CORAL = 0xFFF2707A.toInt()
    const val PINK = 0xFFF25C8A.toInt()
    const val SCREEN = 0xFFE4E1EA.toInt()
    const val MINT = 0xFFA8E6B0.toInt()
    /** "White" for items inside cards (DESIGN.md), tinted so it isn't #fff. */
    const val WHITE_SOFT = 0xFFFFFDF7.toInt()

    /** Text on ink surfaces. */
    const val ON_INK = 0xFFF1EEFB.toInt()
    const val ON_INK_SOFT = 0xFFB3ABD3.toInt()

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    /** Honour "Remove animations" / animator scale 0, like prefers-reduced-motion on the web. */
    fun motionEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}

/** The page: cream with a faint 24dp grid. */
class GraphPaperDrawable(context: Context) : Drawable() {
    private val step = Pixel.dp(context, 24f)
    private val line = Paint().apply { color = Pixel.GRID; strokeWidth = Pixel.dp(context, 1f) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Pixel.CREAM)
        var x = b.left.toFloat()
        while (x <= b.right) { canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), line); x += step }
        var y = b.top.toFloat()
        while (y <= b.bottom) { canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, line); y += step }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * A flat face with an ink outline and a hard offset shadow (no blur). The face fills the
 * bounds minus the shadow; pressed, the shadow goes away (the view itself moves into the
 * shadow's place, see [PixelButton]) so it looks pushed in.
 */
class PixelBoxDrawable(
    context: Context,
    var fill: Int,
    shadowDp: Float = 4f,
    borderDp: Float = 3f,
    var border: Int = Pixel.INK,
    var shadowColor: Int = Pixel.INK,
    /** Fill while the view has D-pad focus (null: no focus look). */
    var focusedFill: Int? = null,
    /** Outline colour while focused (DESIGN.md's focus ring is pink). */
    var focusedBorder: Int? = null,
) : Drawable() {
    val shadow = Pixel.dp(context, shadowDp)
    private val borderW = Pixel.dp(context, borderDp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = false
    private var focused = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = android.R.attr.state_pressed in state
        val nowFocused = android.R.attr.state_focused in state
        if (nowPressed == pressed && nowFocused == focused) return false
        pressed = nowPressed
        focused = nowFocused
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val faceRight = b.right - shadow
        val faceBottom = b.bottom - shadow
        if (!pressed && shadow > 0) {
            paint.style = Paint.Style.FILL
            paint.color = shadowColor
            canvas.drawRect(b.left + shadow, b.top + shadow, b.right.toFloat(), b.bottom.toFloat(), paint)
        }
        rect.set(b.left.toFloat(), b.top.toFloat(), faceRight, faceBottom)
        paint.style = Paint.Style.FILL
        paint.color = if (focused) focusedFill ?: fill else fill
        canvas.drawRect(rect, paint)
        if (borderW > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = borderW
            paint.color = if (focused) focusedBorder ?: border else border
            val h = borderW / 2
            canvas.drawRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * DESIGN.md's dialog box: a lavender panel with ink outline and hard shadow, plus an inner
 * 3dp purple outline inset 10dp. One per screen, for the one thing to explain.
 */
class DialogBoxDrawable(context: Context) : Drawable() {
    private val box = PixelBoxDrawable(context, Pixel.LAVENDER, shadowDp = 6f)
    private val inset = Pixel.dp(context, 10f)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Pixel.dp(context, 3f)
        color = Pixel.PURPLE
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) { box.bounds = bounds }

    override fun draw(canvas: Canvas) {
        box.draw(canvas)
        val b = bounds
        val h = inner.strokeWidth / 2
        canvas.drawRect(b.left + inset + h, b.top + inset + h, b.right - box.shadow - inset - h, b.bottom - box.shadow - inset - h, inner)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Ink strip for HUD readouts and control hints (no border, no shadow). */
fun inkStrip(): Drawable = android.graphics.drawable.ColorDrawable(Pixel.INK)

/** Mixes [color] toward black/white by [amount] (-1..1), for shades on illustration surfaces. */
fun shade(color: Int, amount: Float): Int {
    val t = if (amount < 0) 0 else 255
    val a = kotlin.math.abs(amount)
    fun ch(c: Int) = (c + (t - c) * a).toInt().coerceIn(0, 255)
    return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
}
