package com.khumam.gbaduo.companion.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.CssValues
import com.khumam.gbaduo.companion.style.Length
import com.khumam.gbaduo.companion.style.Properties
import com.khumam.gbaduo.companion.style.Transform
import com.khumam.gbaduo.companion.style.TransitionSpec
import kotlin.math.roundToInt

/** Runs CSS transitions for one view: one animator per property, restarted from the current value. */
internal class Transitions {
    private val running = HashMap<String, ValueAnimator>()

    fun spec(style: ComputedStyle, property: String): TransitionSpec? =
        style.transitions.lastOrNull { it.property == property || it.property == "all" }?.takeIf { it.durationMs > 0 }

    /** Animates progress 0→1 for [property], calling [onFrame] with the eased fraction. */
    fun run(property: String, spec: TransitionSpec, onFrame: (Float) -> Unit) {
        running.remove(property)?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = spec.durationMs
            startDelay = spec.delayMs
            interpolator = PathInterpolator(spec.easing.x1, spec.easing.y1, spec.easing.x2, spec.easing.y2)
            addUpdateListener { onFrame(it.animatedValue as Float) }
        }
        running[property] = animator
        animator.start()
    }

    fun cancel(property: String) {
        running.remove(property)?.cancel()
    }

    fun cancelAll() {
        running.values.forEach { it.cancel() }
        running.clear()
    }
}

/**
 * Applies a [ComputedStyle] to a node's view. CSS px are Android dp; `font-size`
 * uses sp so the system text-size setting still applies.
 */
internal class StyleApplier(private val context: Context, private val assets: AssetCache) {

    private val density = context.resources.displayMetrics.density

    fun apply(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val view = node.view
        applyBox(node, style, previous)
        applyPaint(node, style, previous)
        applyOpacity(node, style, previous)
        applyTransform(node, style, previous)
        applyOverflow(view, style)
        if (view is TextView) applyText(node, style, previous)
        if (view is ImageView) applyImage(node, style)
        applyVisibility(node)
    }

    // ---- box model ---------------------------------------------------------------

    private fun applyBox(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val view = node.view
        val params = view.layoutParams as? BoxParams ?: BoxParams().also { view.layoutParams = it }
        val target = BoxStyle.from(style).scaled(density)
        val shown = params.box
        val widthSpec = previous?.let { node.transitions.spec(style, "width") }
        val heightSpec = previous?.let { node.transitions.spec(style, "height") }
        val animateWidth = widthSpec != null && sameUnit(shown.width, target.width) && shown.width != target.width
        val animateHeight = heightSpec != null && sameUnit(shown.height, target.height) && shown.height != target.height
        if (params.box != target) {
            params.box = target.copy(width = if (animateWidth) shown.width else target.width, height = if (animateHeight) shown.height else target.height)
            view.requestLayout()
        }
        if (animateWidth) {
            val from = shown.width
            node.transitions.run("width", widthSpec!!) { f ->
                params.box = params.box.copy(width = lerp(from, target.width, f))
                view.requestLayout()
            }
        } else {
            node.transitions.cancel("width")
        }
        if (animateHeight) {
            val from = shown.height
            node.transitions.run("height", heightSpec!!) { f ->
                params.box = params.box.copy(height = lerp(from, target.height, f))
                view.requestLayout()
            }
        } else {
            node.transitions.cancel("height")
        }

        val z = style.zIndex ?: 0
        if (params.zIndex != z) {
            params.zIndex = z
            (view.parent as? BoxLayout)?.updateDrawingOrder()
        }

        fun inset(side: String) = ((style.padding(side) + style.borderWidth(side)) * density).roundToInt()
        view.setPadding(inset("left"), inset("top"), inset("right"), inset("bottom"))

        if (view is BoxLayout) view.container = ContainerStyle.from(style).scaled(density)
    }

    private fun sameUnit(a: Length, b: Length) = (a is Length.Px && b is Length.Px) || (a is Length.Percent && b is Length.Percent)

    private fun lerp(from: Length, to: Length, f: Float): Length = when {
        from is Length.Px && to is Length.Px -> Length.Px(from.px + (to.px - from.px) * f)
        from is Length.Percent && to is Length.Percent -> Length.Percent(from.percent + (to.percent - from.percent) * f)
        else -> to
    }

    // ---- background, border, shadow -------------------------------------------

    private fun boxPaint(node: UiNode, style: ComputedStyle): BoxPaint {
        val image = style.backgroundUrl?.let { url -> assets.packFile(url, style.baseDir("background-image"))?.let { assets.bitmap(it) } }
        var backgroundColor = style.backgroundColor
        if (node.kind == UiNode.Kind.FILL && backgroundColor ushr 24 == 0 && image == null && style.backgroundGradient == null) {
            // A bar's fill defaults to the bar's accent-color.
            val accent = node.parent?.computed?.takeIf { it.keyword("accent-color") != "auto" }?.color("accent-color")
            backgroundColor = accent ?: DEFAULT_ACCENT
        }
        val sizeParts = CssValues.splitSpaces(style.keyword("background-size"))
        val imageSize = when (sizeParts.firstOrNull()) {
            "cover" -> BoxPaint.ImageSize.Cover
            "contain" -> BoxPaint.ImageSize.Contain
            null, "auto" -> if (sizeParts.size < 2) BoxPaint.ImageSize.Auto else explicitSize(sizeParts, style)
            else -> explicitSize(sizeParts, style)
        }
        val repeat = style.keyword("background-repeat")
        val sides = Properties.SIDES
        return BoxPaint(
            backgroundColor = backgroundColor,
            gradient = style.backgroundGradient,
            image = image,
            imageSize = imageSize,
            imagePosition = style.backgroundPosition,
            imageRepeatX = repeat == "repeat" || repeat == "repeat-x",
            imageRepeatY = repeat == "repeat" || repeat == "repeat-y",
            imageSmooth = style.keyword("image-rendering").let { it != "pixelated" && it != "crisp-edges" },
            borderWidths = FloatArray(4) { style.borderWidth(sides[it]) * density },
            borderColors = IntArray(4) { style.color("border-${sides[it]}-color") },
            borderStyles = Array(4) { style.keyword("border-${sides[it]}-style") },
            radii = FloatArray(4) { i ->
                when (val r = style.length("border-${Properties.CORNERS[i]}-radius")) {
                    is Length.Px -> r.px * density
                    is Length.Percent -> -r.percent
                    Length.Auto -> 0f
                }
            },
            shadow = style.boxShadow?.let { it.copy(offsetX = it.offsetX * density, offsetY = it.offsetY * density, blur = it.blur * density, spread = it.spread * density) },
        )
    }

    private fun explicitSize(parts: List<String>, style: ComputedStyle): BoxPaint.ImageSize {
        fun px(token: String?): Float? = token?.let { CssValues.length(it, style.lengthContext) }?.let { (it as? Length.Px)?.px?.times(density) }
        return BoxPaint.ImageSize.Explicit(px(parts.getOrNull(0)), px(parts.getOrNull(1)))
    }

    private fun applyPaint(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val view = node.view
        val target = boxPaint(node, style)
        if (target.isEmpty) {
            node.transitions.cancel("background-color")
            if (view.background != null) view.background = null
            return
        }
        val drawable = view.background as? StyleDrawable ?: StyleDrawable(target).also { view.background = it }
        val spec = previous?.let { node.transitions.spec(style, "background-color") }
        val from = drawable.spec.backgroundColor
        drawable.spec = if (spec != null && from != target.backgroundColor) target.copy(backgroundColor = from) else target
        if (spec != null && from != target.backgroundColor) {
            node.transitions.run("background-color", spec) { f ->
                drawable.spec = drawable.spec.copy(backgroundColor = CssValues.mixColor(from, target.backgroundColor, f))
            }
        }
    }

    // ---- opacity, transform, overflow, visibility -----------------------------

    private fun applyOpacity(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val view = node.view
        val target = style.opacity
        val spec = previous?.let { node.transitions.spec(style, "opacity") }
        if (spec != null && view.alpha != target) {
            val from = view.alpha
            node.transitions.run("opacity", spec) { f -> view.alpha = from + (target - from) * f }
        } else {
            node.transitions.cancel("opacity")
            view.alpha = target
        }
    }

    private fun applyTransform(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val target = style.transform
        node.transformOrigin = style.transformOrigin
        val position = style.keyword("position")
        node.relativeOffset = if (position == "relative") {
            val box = BoxStyle.from(style)
            val x = (box.left as? Length.Px)?.px ?: (box.right as? Length.Px)?.px?.unaryMinus() ?: 0f
            val y = (box.top as? Length.Px)?.px ?: (box.bottom as? Length.Px)?.px?.unaryMinus() ?: 0f
            x * density to y * density
        } else {
            0f to 0f
        }
        val spec = previous?.let { node.transitions.spec(style, "transform") }
        val from = node.shownTransform
        if (spec != null && from != target) {
            node.transitions.run("transform", spec) { f ->
                node.shownTransform = lerp(from, target, f)
                updateTransform(node)
            }
        } else {
            node.transitions.cancel("transform")
            node.shownTransform = target
        }
        if (!node.transformListenerInstalled) {
            node.transformListenerInstalled = true
            node.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateTransform(node) }
        }
        updateTransform(node)
    }

    private fun lerp(from: Transform, to: Transform, f: Float) = Transform(
        translateX = lerp(from.translateX, to.translateX, f),
        translateY = lerp(from.translateY, to.translateY, f),
        scaleX = from.scaleX + (to.scaleX - from.scaleX) * f,
        scaleY = from.scaleY + (to.scaleY - from.scaleY) * f,
        rotateDeg = from.rotateDeg + (to.rotateDeg - from.rotateDeg) * f,
    )

    /** Percent translations and the transform origin depend on the view's own size, so re-run after layout. */
    private fun updateTransform(node: UiNode) {
        val view = node.view
        val t = node.shownTransform
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        fun translate(length: Length, size: Float) = when (length) {
            is Length.Px -> length.px * density
            is Length.Percent -> size * length.percent / 100f
            Length.Auto -> 0f
        }
        view.translationX = translate(t.translateX, w) + node.relativeOffset.first
        view.translationY = translate(t.translateY, h) + node.relativeOffset.second
        view.scaleX = t.scaleX
        view.scaleY = t.scaleY
        view.rotation = t.rotateDeg
        view.pivotX = w * node.transformOrigin.first
        view.pivotY = h * node.transformOrigin.second
    }

    private fun applyOverflow(view: View, style: ComputedStyle) {
        val clip = style.keyword("overflow") != "visible"
        view.outlineProvider = if (view.background is StyleDrawable) ViewOutlineProvider.BACKGROUND else ViewOutlineProvider.BOUNDS
        view.clipToOutline = clip
        if (view is BoxLayout) view.clipChildren = clip
    }

    fun applyVisibility(node: UiNode) {
        val style = node.computed
        val visibility = when {
            node.hiddenByBinding || node.hiddenByTabs || style?.keyword("display") == "none" -> View.GONE
            style?.keyword("visibility").let { it == "hidden" || it == "collapse" } -> View.INVISIBLE
            else -> View.VISIBLE
        }
        if (node.view.visibility != visibility) node.view.visibility = visibility
    }

    // ---- text and images --------------------------------------------------------

    private fun applyText(node: UiNode, style: ComputedStyle, previous: ComputedStyle?) {
        val view = node.view as TextView
        view.includeFontPadding = false
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSizePx)
        val italic = style.keyword("font-style") != "normal"
        view.typeface = assets.typeface(style.fontFamilies, style.fontWeight, italic)
        view.letterSpacing = if (style.fontSizePx > 0f) style.letterSpacingPx / style.fontSizePx else 0f
        style.lineHeightPx?.let { lh -> view.setLineHeight((view.textSize * lh / style.fontSizePx).roundToInt().coerceAtLeast(1)) }
            ?: view.setLineSpacing(0f, 1f)
        val horizontal = when (style.keyword("text-align")) {
            "left" -> Gravity.LEFT
            "right" -> Gravity.RIGHT
            "center" -> Gravity.CENTER_HORIZONTAL
            "end" -> Gravity.END
            else -> Gravity.START
        }
        view.gravity = horizontal or Gravity.TOP
        val shadow = style.textShadow
        if (shadow != null) {
            view.setShadowLayer(maxOf(0.01f, shadow.blur * density / 2f), shadow.offsetX * density, shadow.offsetY * density, shadow.color)
        } else {
            view.setShadowLayer(0f, 0f, 0f, 0)
        }
        val noWrap = style.keyword("white-space").let { it == "nowrap" || it == "pre" }
        val ellipsis = style.keyword("text-overflow") == "ellipsis"
        val clamp = style.lineClamp
        view.maxLines = when {
            noWrap -> 1
            clamp != null -> clamp
            else -> Int.MAX_VALUE
        }
        view.setHorizontallyScrolling(noWrap && !ellipsis)
        view.ellipsize = if (ellipsis || clamp != null) TextUtils.TruncateAt.END else null
        val decorations = CssValues.splitSpaces(style.keyword("text-decoration-line"))
        var flags = view.paintFlags and (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
        if ("underline" in decorations) flags = flags or Paint.UNDERLINE_TEXT_FLAG
        if ("line-through" in decorations) flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
        view.paintFlags = flags

        val target = style.color
        val spec = previous?.let { node.transitions.spec(style, "color") }
        val from = view.currentTextColor
        if (spec != null && from != target) {
            node.transitions.run("color", spec) { f -> view.setTextColor(CssValues.mixColor(from, target, f)) }
        } else {
            node.transitions.cancel("color")
            view.setTextColor(target)
        }
        showText(node)
    }

    /** Sets the node's text, applying `text-transform`. */
    fun showText(node: UiNode) {
        val view = node.view as? TextView ?: return
        val raw = node.rawText ?: ""
        val text = when (node.computed?.keyword("text-transform")) {
            "uppercase" -> raw.uppercase()
            "lowercase" -> raw.lowercase()
            "capitalize" -> raw.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
            else -> raw
        }
        if (view.text?.toString() != text) view.text = text
    }

    private fun applyImage(node: UiNode, style: ComputedStyle) {
        val view = node.view as ImageView
        view.scaleType = when (style.keyword("object-fit")) {
            "cover" -> ImageView.ScaleType.CENTER_CROP
            "fill" -> ImageView.ScaleType.FIT_XY
            "none" -> ImageView.ScaleType.CENTER
            "scale-down" -> ImageView.ScaleType.CENTER_INSIDE
            else -> ImageView.ScaleType.FIT_CENTER
        }
        (view.drawable as? BitmapDrawable)?.isFilterBitmap = smooth(node)
    }

    private fun smooth(node: UiNode) = node.computed?.keyword("image-rendering").let { it != "pixelated" && it != "crisp-edges" }

    /** Shows [bitmap] (or nothing), honoring `image-rendering: pixelated`. */
    fun setImage(node: UiNode, bitmap: Bitmap?) {
        val view = node.view as? ImageView ?: return
        if (bitmap == null) {
            view.setImageDrawable(null)
            return
        }
        view.setImageDrawable(BitmapDrawable(context.resources, bitmap).apply { isFilterBitmap = smooth(node) })
    }

    private companion object {
        const val DEFAULT_ACCENT = 0xFF6C4AB6.toInt()
    }
}
