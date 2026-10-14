package com.khumam.gbaduo.companion.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.khumam.gbaduo.companion.style.CssGeometry
import com.khumam.gbaduo.companion.style.LinearGradient
import com.khumam.gbaduo.companion.style.Shadow
import kotlin.math.roundToInt

/** Everything a box paints behind its content, in device pixels. */
internal data class BoxPaint(
    val backgroundColor: Color = Color.Transparent,
    val gradient: LinearGradient? = null,
    val image: ImageBitmap? = null,
    val imageSize: ImageSize = ImageSize.Auto,
    val imagePosition: Pair<Float, Float> = 0f to 0f,
    val imageRepeatX: Boolean = true,
    val imageRepeatY: Boolean = true,
    val imageSmooth: Boolean = true,
    /** top, right, bottom, left. */
    val borderWidths: List<Float> = listOf(0f, 0f, 0f, 0f),
    val borderColors: List<Color> = listOf(Color.Transparent, Color.Transparent, Color.Transparent, Color.Transparent),
    val borderStyles: List<String> = listOf("none", "none", "none", "none"),
    /** CSS radii (tl, tr, br, bl): px, or a negative number = that percent of min(width, height). */
    val radii: List<Float> = listOf(0f, 0f, 0f, 0f),
    val shadow: Shadow? = null,
) {
    sealed class ImageSize {
        object Auto : ImageSize()
        object Cover : ImageSize()
        object Contain : ImageSize()
        data class Explicit(val width: Float?, val height: Float?) : ImageSize()
    }

    val isEmpty: Boolean
        get() = backgroundColor.alpha == 0f && gradient == null && image == null && borderWidths.all { it <= 0f } && shadow == null

    fun radiiFor(size: Size): FloatArray {
        val declared = FloatArray(4) { i -> radii[i].let { r -> if (r < 0f) minOf(size.width, size.height) * -r / 100f else r } }
        return CssGeometry.fitRadii(declared, size.width, size.height)
    }
}

private fun roundRectPath(rect: Rect, r: FloatArray): Path = Path().apply {
    addRoundRect(
        RoundRect(
            rect,
            topLeft = CornerRadius(r[0]),
            topRight = CornerRadius(r[1]),
            bottomRight = CornerRadius(r[2]),
            bottomLeft = CornerRadius(r[3]),
        ),
    )
}

/** The box's rounded shape, for `overflow: hidden` clipping. */
internal class CssShape(private val paint: BoxPaint) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = paint.radiiFor(size)
        return Outline.Rounded(
            RoundRect(Rect(Offset.Zero, size), CornerRadius(r[0]), CornerRadius(r[1]), CornerRadius(r[2]), CornerRadius(r[3])),
        )
    }
}

/** Paints a CSS box: shadow (outside only), background color, gradient or image, radii, per-side borders. */
internal fun DrawScope.drawCssBox(paint: BoxPaint, platform: CompanionPlatform) {
    if (size.width <= 0f || size.height <= 0f || paint.isEmpty) return
    val r = paint.radiiFor(size)
    val bounds = Rect(Offset.Zero, size)
    val path = roundRectPath(bounds, r)

    paint.shadow?.let { drawShadow(it, path, bounds, r, platform) }
    if (paint.backgroundColor.alpha > 0f) drawPath(path, paint.backgroundColor)
    paint.gradient?.let { g ->
        val line = CssGeometry.gradientLine(g, size.width, size.height)
        val positions = CssGeometry.stopPositions(g.stops)
        val stops = g.stops.mapIndexed { i, stop -> positions[i] to Color(stop.color) }.toTypedArray()
        drawPath(path, Brush.linearGradient(*stops, start = Offset(line[0], line[1]), end = Offset(line[2], line[3])))
    }
    paint.image?.let { clipPath(path) { drawBackgroundImage(paint, it) } }
    drawBorders(paint, path, bounds, r)
}

private fun DrawScope.drawShadow(shadow: Shadow, boxPath: Path, bounds: Rect, r: FloatArray, platform: CompanionPlatform) {
    val s = shadow.spread
    val shadowRect = Rect(bounds.left - s, bounds.top - s, bounds.right + s, bounds.bottom + s).translate(shadow.offsetX, shadow.offsetY)
    val shadowPath = roundRectPath(shadowRect, FloatArray(4) { maxOf(0f, r[it] + s) })
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(boxPath, ClipOp.Difference) // box-shadow is never painted under the box itself
        val p = Paint().apply { color = Color(shadow.color) }
        if (shadow.blur > 0f) platform.setBlur(p, shadow.blur / 2f)
        canvas.drawPath(shadowPath, p)
        canvas.restore()
    }
}

private fun DrawScope.drawBackgroundImage(paint: BoxPaint, image: ImageBitmap) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    val (dw, dh) = when (val s = paint.imageSize) {
        BoxPaint.ImageSize.Auto -> iw to ih
        BoxPaint.ImageSize.Cover -> maxOf(size.width / iw, size.height / ih).let { iw * it to ih * it }
        BoxPaint.ImageSize.Contain -> minOf(size.width / iw, size.height / ih).let { iw * it to ih * it }
        is BoxPaint.ImageSize.Explicit -> when {
            s.width != null && s.height != null -> s.width to s.height
            s.width != null -> s.width to ih * s.width / iw
            s.height != null -> iw * s.height / ih to s.height
            else -> iw to ih
        }
    }
    if (dw < 1f || dh < 1f) return
    val x0 = (size.width - dw) * paint.imagePosition.first
    val y0 = (size.height - dh) * paint.imagePosition.second
    val quality = if (paint.imageSmooth) FilterQuality.Low else FilterQuality.None
    // Tile from the positioned image outwards on the repeating axes (bounded, so a 1px tile can't hang).
    val xs = if (paint.imageRepeatX) generateSequence(x0 - dw * kotlin.math.ceil(x0 / dw)) { it + dw }.takeWhile { it < size.width }.take(MAX_TILES).toList() else listOf(x0)
    val ys = if (paint.imageRepeatY) generateSequence(y0 - dh * kotlin.math.ceil(y0 / dh)) { it + dh }.takeWhile { it < size.height }.take(MAX_TILES).toList() else listOf(y0)
    for (y in ys) for (x in xs) {
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
            filterQuality = quality,
        )
    }
}

private const val MAX_TILES = 256

private fun DrawScope.drawBorders(paint: BoxPaint, outer: Path, bounds: Rect, r: FloatArray) {
    val widths = paint.borderWidths
    if (widths.all { it <= 0f }) return
    val uniform = widths.all { it == widths[0] } && paint.borderColors.all { it == paint.borderColors[0] } &&
        paint.borderStyles.all { it == paint.borderStyles[0] }
    if (uniform) {
        val bw = widths[0]
        val half = bw / 2f
        val inset = Rect(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        val style = paint.borderStyles[0]
        drawPath(
            roundRectPath(inset, FloatArray(4) { maxOf(0f, r[it] - half) }),
            paint.borderColors[0],
            style = Stroke(
                width = bw,
                cap = if (style == "dotted") StrokeCap.Round else StrokeCap.Butt,
                pathEffect = when (style) {
                    "dashed" -> PathEffect.dashPathEffect(floatArrayOf(bw * 3f, bw * 2f))
                    "dotted" -> PathEffect.dashPathEffect(floatArrayOf(0.01f, bw * 2f))
                    else -> null
                },
            ),
        )
        return
    }
    // Per-side borders: fill the ring between the outer and inner edges, one side at a time.
    val (top, right, bottom, left) = widths
    val innerRadii = floatArrayOf(
        maxOf(0f, r[0] - maxOf(left, top)), maxOf(0f, r[1] - maxOf(right, top)),
        maxOf(0f, r[2] - maxOf(right, bottom)), maxOf(0f, r[3] - maxOf(left, bottom)),
    )
    val inner = roundRectPath(Rect(bounds.left + left, bounds.top + top, bounds.right - right, bounds.bottom - bottom), innerRadii)
    val ring = Path().apply { op(outer, inner, PathOperation.Difference) }
    val l = bounds.left
    val t = bounds.top
    val rr = bounds.right
    val bb = bounds.bottom
    val sides = listOf(
        floatArrayOf(l, t, rr, t, rr - right, t + top, l + left, t + top),
        floatArrayOf(rr, t, rr, bb, rr - right, bb - bottom, rr - right, t + top),
        floatArrayOf(rr, bb, l, bb, l + left, bb - bottom, rr - right, bb - bottom),
        floatArrayOf(l, bb, l, t, l + left, t + top, l + left, bb - bottom),
    )
    for (i in 0 until 4) {
        if (widths[i] <= 0f) continue
        val p = sides[i]
        val trapezoid = Path().apply {
            moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close()
        }
        clipPath(trapezoid) { drawPath(ring, paint.borderColors[i]) }
    }
}
