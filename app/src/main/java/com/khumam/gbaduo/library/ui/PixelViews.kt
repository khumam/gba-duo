package com.khumam.gbaduo.library.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.khumam.gbaduo.R

object Fonts {
    private var display: Typeface? = null
    private var displayBold: Typeface? = null
    private var body: Typeface? = null
    private var bodyMedium: Typeface? = null
    private var bodySemi: Typeface? = null

    /** Silkscreen: titles, labels, buttons. Short text only. */
    fun display(c: Context, bold: Boolean = false): Typeface =
        if (bold) displayBold ?: ResourcesCompat.getFont(c, R.font.silkscreen_bold)!!.also { displayBold = it }
        else display ?: ResourcesCompat.getFont(c, R.font.silkscreen)!!.also { display = it }

    /** IBM Plex Mono: anything you read. */
    fun body(c: Context, weight: Int = 400): Typeface = when {
        weight >= 600 -> bodySemi ?: ResourcesCompat.getFont(c, R.font.plex_mono_semibold)!!.also { bodySemi = it }
        weight >= 500 -> bodyMedium ?: ResourcesCompat.getFont(c, R.font.plex_mono_medium)!!.also { bodyMedium = it }
        else -> body ?: ResourcesCompat.getFont(c, R.font.plex_mono)!!.also { body = it }
    }
}

/**
 * A button that presses in: an ink-outlined face with a hard shadow; while pressed the
 * whole view moves into the shadow's place and the shadow disappears (DESIGN.md "Buttons").
 */
class PixelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    val box = PixelBoxDrawable(context, Pixel.YELLOW, shadowDp = 4f)

    init {
        background = box
        typeface = Fonts.display(context, bold = true)
        setTextColor(Pixel.INK)
        gravity = Gravity.CENTER
        isAllCaps = false
        textSize = 15f
        includeFontPadding = false
        val padH = Pixel.dp(context, 20f).toInt()
        // The shadow sits inside the bounds: pad the text away from it so it centres on the face.
        setPadding(padH, 0, padH + box.shadow.toInt(), box.shadow.toInt())
        minHeight = Pixel.dp(context, 48f).toInt()
        isClickable = true
        isFocusable = false
    }

    fun style(fill: Int, text: Int = Pixel.INK) {
        box.fill = fill
        box.invalidateSelf()
        setTextColor(text)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val s = if (isPressed) box.shadow else 0f
        translationX = s
        translationY = s
    }
}

/**
 * Text with an ink outline and a hard offset shadow, like the site's section titles:
 * stroke under fill (paint-order: stroke fill), shadow under both. Single line, ellipsized.
 */
class OutlineText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var text: CharSequence = ""
        set(value) { field = value; requestLayout(); invalidate() }
    var fillColor = Pixel.PURPLE
    var strokeColor = Pixel.INK
    var shadowColor = Pixel.INK
    /** Outline width outside the glyphs (the stroke is drawn centred, so twice this). */
    var strokeWidthPx = Pixel.dp(context, 0.8f)
    var shadowPx = Pixel.dp(context, 3f)
    var centered = false
    /** When set, a line too long for the width shrinks down to this size before it ellipsizes. */
    var minTextSizePx = 0f

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.display(context, bold = true)
        textSize = Pixel.dp(context, 24f)
    }

    fun setTextSizeSp(sp: Float) {
        paint.textSize = sp * resources.displayMetrics.scaledDensity
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = paint.fontMetrics
        val h = (fm.descent - fm.ascent + shadowPx + strokeWidthPx * 2).toInt() + 1
        val w = (paint.measureText(text.toString()) + shadowPx + strokeWidthPx * 2).toInt() + 1
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val avail = width - shadowPx - strokeWidthPx * 2
        val fullSize = paint.textSize
        if (minTextSizePx > 0f) {
            while (paint.textSize > minTextSizePx && paint.measureText(text.toString()) > avail) {
                paint.textSize = (paint.textSize - 1f).coerceAtLeast(minTextSizePx)
            }
        }
        val shown = TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.END).toString()
        val fm = paint.fontMetrics
        val textW = paint.measureText(shown)
        val x = strokeWidthPx + if (centered) ((avail - textW) / 2).coerceAtLeast(0f) else 0f
        val y = strokeWidthPx - fm.ascent
        paint.style = Paint.Style.FILL
        paint.color = shadowColor
        canvas.drawText(shown, x + shadowPx, y + shadowPx, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx * 2
        paint.strokeJoin = Paint.Join.MITER
        paint.color = strokeColor
        canvas.drawText(shown, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawText(shown, x, y, paint)
        paint.textSize = fullSize
    }
}
