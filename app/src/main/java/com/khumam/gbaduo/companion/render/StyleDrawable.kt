package com.khumam.gbaduo.companion.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.khumam.gbaduo.companion.style.CssGeometry
import com.khumam.gbaduo.companion.style.LinearGradient as CssLinearGradient
import com.khumam.gbaduo.companion.style.Shadow

/** Everything a box paints behind its content, in device pixels. */
data class BoxPaint(
    val backgroundColor: Int = 0,
    val gradient: CssLinearGradient? = null,
    val image: Bitmap? = null,
    /** `cover`, `contain`, `auto`, or explicit sizes in px (null = auto for that axis). */
    val imageSize: ImageSize = ImageSize.Auto,
    val imagePosition: Pair<Float, Float> = 0f to 0f,
    val imageRepeatX: Boolean = true,
    val imageRepeatY: Boolean = true,
    val imageSmooth: Boolean = true,
    /** top, right, bottom, left. */
    val borderWidths: FloatArray = FloatArray(4),
    val borderColors: IntArray = IntArray(4),
    val borderStyles: Array<String> = arrayOf("none", "none", "none", "none"),
    /** CSS radii as declared (tl, tr, br, bl) in px, or negative = percent of min(width, height) * -1. */
    val radii: FloatArray = FloatArray(4),
    val shadow: Shadow? = null,
) {
    sealed class ImageSize {
        object Auto : ImageSize()
        object Cover : ImageSize()
        object Contain : ImageSize()
        data class Explicit(val width: Float?, val height: Float?) : ImageSize()
    }

    val isEmpty: Boolean
        get() = backgroundColor ushr 24 == 0 && gradient == null && image == null && borderWidths.all { it <= 0f } && shadow == null

    override fun equals(other: Any?): Boolean = other is BoxPaint &&
        backgroundColor == other.backgroundColor && gradient == other.gradient && image === other.image &&
        imageSize == other.imageSize && imagePosition == other.imagePosition && imageRepeatX == other.imageRepeatX &&
        imageRepeatY == other.imageRepeatY && imageSmooth == other.imageSmooth &&
        borderWidths.contentEquals(other.borderWidths) && borderColors.contentEquals(other.borderColors) &&
        borderStyles.contentEquals(other.borderStyles) && radii.contentEquals(other.radii) && shadow == other.shadow

    override fun hashCode(): Int = backgroundColor * 31 + radii.contentHashCode()
}

/**
 * Paints a CSS box: shadow (outside only), background color, gradient or image,
 * per-corner radii and per-side borders. Its outline matches the rounded box so
 * `overflow: hidden` clips children to it.
 */
class StyleDrawable(paint: BoxPaint) : Drawable() {

    var spec: BoxPaint = paint
        set(value) {
            if (field != value) {
                field = value
                invalidateSelf()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val inner = Path()
    private val rect = RectF()

    private fun radii(width: Float, height: Float): FloatArray {
        val declared = FloatArray(4) { i ->
            val r = spec.radii[i]
            if (r < 0f) minOf(width, height) * -r / 100f else r
        }
        return CssGeometry.fitRadii(declared, width, height)
    }

    private fun roundRect(target: Path, left: Float, top: Float, right: Float, bottom: Float, r: FloatArray) {
        target.reset()
        rect.set(left, top, right, bottom)
        target.addRoundRect(rect, floatArrayOf(r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3]), Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = radii(w, h)
        roundRect(path, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r)

        spec.shadow?.let { shadow -> drawShadow(canvas, shadow, r) }

        if (spec.backgroundColor ushr 24 != 0) {
            fill.shader = null
            fill.color = spec.backgroundColor
            canvas.drawPath(path, fill)
        }
        spec.gradient?.let { g ->
            val line = CssGeometry.gradientLine(g, w, h)
            fill.shader = LinearGradient(
                b.left + line[0], b.top + line[1], b.left + line[2], b.top + line[3],
                g.stops.map { it.color }.toIntArray(), CssGeometry.stopPositions(g.stops), Shader.TileMode.CLAMP,
            )
            fill.color = 0xFF000000.toInt()
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        spec.image?.let { drawImage(canvas, it, w, h) }
        drawBorders(canvas, r)
    }

    private fun drawShadow(canvas: Canvas, shadow: Shadow, r: FloatArray) {
        val b = bounds
        val s = shadow.spread
        val shadowPath = Path()
        roundRect(shadowPath, b.left - s, b.top - s, b.right + s, b.bottom + s, FloatArray(4) { maxOf(0f, r[it] + s) })
        shadowPath.offset(shadow.offsetX, shadow.offsetY)
        val save = canvas.save()
        canvas.clipOutPath(path) // box-shadow is never painted under the box itself
        if (shadow.blur > 0f) {
            fill.shader = null
            fill.color = shadow.color
            fill.setShadowLayer(shadow.blur / 2f, 0f, 0f, shadow.color)
            canvas.drawPath(shadowPath, fill)
            fill.clearShadowLayer()
        } else {
            fill.color = shadow.color
            canvas.drawPath(shadowPath, fill)
        }
        canvas.restoreToCount(save)
    }

    private fun drawImage(canvas: Canvas, image: Bitmap, w: Float, h: Float) {
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val (dw, dh) = when (val size = spec.imageSize) {
            BoxPaint.ImageSize.Auto -> iw to ih
            BoxPaint.ImageSize.Cover -> maxOf(w / iw, h / ih).let { iw * it to ih * it }
            BoxPaint.ImageSize.Contain -> minOf(w / iw, h / ih).let { iw * it to ih * it }
            is BoxPaint.ImageSize.Explicit -> when {
                size.width != null && size.height != null -> size.width to size.height
                size.width != null -> size.width to ih * size.width / iw
                size.height != null -> iw * size.height / ih to size.height
                else -> iw to ih
            }
        }
        if (dw <= 0f || dh <= 0f) return
        val x = bounds.left + (w - dw) * spec.imagePosition.first
        val y = bounds.top + (h - dh) * spec.imagePosition.second
        val matrix = Matrix().apply {
            setScale(dw / iw, dh / ih)
            postTranslate(x, y)
        }
        val save = canvas.save()
        canvas.clipPath(path)
        fill.color = 0xFF000000.toInt()
        fill.isFilterBitmap = spec.imageSmooth
        if (!spec.imageRepeatX && !spec.imageRepeatY) {
            fill.shader = null
            canvas.drawBitmap(image, matrix, fill)
        } else {
            // Repeating on one axis only: tile both ways but clip to the image's band
            // (TileMode.DECAL would do this but needs API 31; minSdk is 30).
            if (!spec.imageRepeatY) canvas.clipRect(bounds.left.toFloat(), y, bounds.right.toFloat(), y + dh)
            if (!spec.imageRepeatX) canvas.clipRect(x, bounds.top.toFloat(), x + dw, bounds.bottom.toFloat())
            val shader = BitmapShader(image, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            shader.setLocalMatrix(matrix)
            fill.shader = shader
            canvas.drawPath(path, fill)
            fill.shader = null
        }
        fill.isFilterBitmap = true
        canvas.restoreToCount(save)
    }

    private fun drawBorders(canvas: Canvas, r: FloatArray) {
        val widths = spec.borderWidths
        if (widths.all { it <= 0f }) return
        val b = bounds
        val uniform = widths.all { it == widths[0] } && spec.borderColors.all { it == spec.borderColors[0] } &&
            spec.borderStyles.all { it == spec.borderStyles[0] }
        if (uniform) {
            val bw = widths[0]
            stroke.strokeWidth = bw
            stroke.color = spec.borderColors[0]
            stroke.pathEffect = when (spec.borderStyles[0]) {
                "dashed" -> DashPathEffect(floatArrayOf(bw * 3f, bw * 2f), 0f)
                "dotted" -> DashPathEffect(floatArrayOf(0.01f, bw * 2f), 0f)
                else -> null
            }
            stroke.strokeCap = if (spec.borderStyles[0] == "dotted") Paint.Cap.ROUND else Paint.Cap.BUTT
            val half = bw / 2f
            roundRect(inner, b.left + half, b.top + half, b.right - half, b.bottom - half, FloatArray(4) { maxOf(0f, r[it] - half) })
            canvas.drawPath(inner, stroke)
            return
        }
        // Per-side borders: fill the ring between the outer and inner edges, one side at a time.
        val (top, right, bottom, left) = widths.toList()
        val innerRadii = floatArrayOf(maxOf(0f, r[0] - maxOf(left, top)), maxOf(0f, r[1] - maxOf(right, top)), maxOf(0f, r[2] - maxOf(right, bottom)), maxOf(0f, r[3] - maxOf(left, bottom)))
        roundRect(inner, b.left + left, b.top + top, b.right - right, b.bottom - bottom, innerRadii)
        val ring = Path().apply { op(path, inner, Path.Op.DIFFERENCE) }
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val rr = b.right.toFloat()
        val bb = b.bottom.toFloat()
        val sides = listOf(
            floatArrayOf(l, t, rr, t, rr - right, t + top, l + left, t + top),          // top
            floatArrayOf(rr, t, rr, bb, rr - right, bb - bottom, rr - right, t + top),  // right
            floatArrayOf(rr, bb, l, bb, l + left, bb - bottom, rr - right, bb - bottom), // bottom
            floatArrayOf(l, bb, l, t, l + left, t + top, l + left, bb - bottom),         // left
        )
        fill.shader = null
        for (i in 0 until 4) {
            if (widths[i] <= 0f) continue
            val p = sides[i]
            val trapezoid = Path().apply {
                moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close()
            }
            val save = canvas.save()
            canvas.clipPath(trapezoid)
            fill.color = spec.borderColors[i]
            canvas.drawPath(ring, fill)
            canvas.restoreToCount(save)
        }
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        if (b.isEmpty) return
        val r = radii(b.width().toFloat(), b.height().toFloat())
        if (r.all { it == r[0] }) {
            outline.setRoundRect(b, r[0])
        } else {
            roundRect(path, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r)
            outline.setPath(path)
        }
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
