package com.khumam.gbaduo.companion.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.khumam.gbaduo.companion.layout.Align
import com.khumam.gbaduo.companion.layout.BoxStyle
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.layout.Display
import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.CssValues
import com.khumam.gbaduo.companion.style.Length
import com.khumam.gbaduo.companion.style.Properties
import com.khumam.gbaduo.companion.style.TransitionSpec
import com.khumam.gbaduo.companion.tree.CompanionNode
import com.khumam.gbaduo.companion.tree.CompanionTree
import androidx.compose.ui.graphics.Shadow as TextShadow

/**
 * The companion screen: renders a [CompanionTree] with CSS layout, painting,
 * text, images and transitions. Each node recomposes only when its own state
 * (style, text, image, visibility, children) changes.
 */
@Composable
fun CompanionScreen(tree: CompanionTree, platform: CompanionPlatform, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(size) {
        if (size != IntSize.Zero) tree.setViewport(size.width / density, size.height / density)
    }
    CssLayout(VIEWPORT, Insets(), modifier.fillMaxSize().onSizeChanged { size = it }) {
        CssNode(tree.root, tree, platform)
    }
}

private val VIEWPORT = ContainerStyle(display = Display.STACK, alignItems = Align.START, justifyItems = Align.START)

/** Text fills its box's width (so `text-align` works) and sits at the top. */
private val TEXT_CONTENT = ContainerStyle(display = Display.STACK, alignItems = Align.START, justifyItems = Align.STRETCH)

/** Images fill their box; `object-fit` decides how the bitmap sits inside it. */
private val IMAGE_CONTENT = ContainerStyle(display = Display.STACK, alignItems = Align.STRETCH, justifyItems = Align.STRETCH)

private const val DEFAULT_ACCENT = 0xFF6C4AB6.toInt()

@Composable
private fun CssNode(node: CompanionNode, tree: CompanionTree, platform: CompanionPlatform) {
    val style = node.computed ?: return
    if (node.hidden || style.keyword("display") == "none") return
    val density = LocalDensity.current.density
    val transitions = remember(style) { style.transitions }

    // Box, with width/height transitions.
    val target = remember(style, density) { BoxStyle.from(style).scaled(density) }
    val box = target.copy(
        width = animateLength(target.width, spec(transitions, "width")),
        height = animateLength(target.height, spec(transitions, "height")),
    )

    // Paint, with a background-color transition.
    val parentStyle = node.parent?.computed
    val paintTarget = remember(style, density, if (node.kind == CompanionNode.Kind.FILL) parentStyle else null) {
        boxPaint(node, style, density, platform)
    }
    val background by animateColorAsState(paintTarget.backgroundColor, spec(transitions, "background-color"), label = "background-color")
    val paint = paintTarget.copy(backgroundColor = background)

    // Opacity and transform.
    val visible = style.keyword("visibility").let { it != "hidden" && it != "collapse" }
    val opacity by animateFloatAsState(if (visible) style.opacity else 0f, spec(transitions, "opacity"), label = "opacity")
    val t = remember(style) { style.transform }
    val translateX = animateLength(t.translateX, spec(transitions, "transform"))
    val translateY = animateLength(t.translateY, spec(transitions, "transform"))
    val scaleX by animateFloatAsState(t.scaleX, spec(transitions, "transform"), label = "scaleX")
    val scaleY by animateFloatAsState(t.scaleY, spec(transitions, "transform"), label = "scaleY")
    val rotation by animateFloatAsState(t.rotateDeg, spec(transitions, "transform"), label = "rotate")
    val origin = remember(style) { style.transformOrigin }
    val relative = remember(style, density) { relativeOffset(style, density) }

    var modifier = Modifier
        .then(CssParentData(box, style.zIndex ?: 0))
        .graphicsLayer {
            alpha = opacity
            translationX = resolveTranslate(translateX, size.width, density) + relative.first
            translationY = resolveTranslate(translateY, size.height, density) + relative.second
            this.scaleX = scaleX
            this.scaleY = scaleY
            rotationZ = rotation
            transformOrigin = TransformOrigin(origin.first, origin.second)
        }
        .drawBehind { drawCssBox(paint, platform) }
    if (style.keyword("overflow") != "visible") modifier = modifier.clip(CssShape(paint))
    // `overflow: auto|scroll` makes this box's content actually scrollable (vertically — the
    // engine has no per-axis overflow-x/overflow-y, and vertical lists are the overwhelmingly
    // common case). Compose's verticalScroll modifier works transparently with CssMeasurePolicy
    // below: it removes the incoming height constraint before the child content is measured
    // (so e.g. a `list` can grow to its natural, un-clamped height) while still reporting a
    // height clamped to what's actually available to this node's own parent, offsetting the
    // oversized content by scroll position and clipping it to that available viewport.
    if (style.keyword("overflow") == "auto" || style.keyword("overflow") == "scroll") {
        modifier = modifier.verticalScroll(rememberScrollState())
    }
    node.onClick?.let { onClick ->
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        LaunchedEffect(pressed) { tree.setPressed(node, pressed) }
        modifier = modifier.clickable(interactionSource = interactions, indication = null, onClick = onClick)
    }

    fun inset(side: String) = (style.padding(side) + style.borderWidth(side)) * density
    val insets = remember(style, density) { Insets(inset("left"), inset("top"), inset("right"), inset("bottom")) }
    val text = node.text
    val image = node.image
    val container = when {
        text != null -> TEXT_CONTENT
        node.isImage -> IMAGE_CONTENT
        else -> remember(style, density) { ContainerStyle.from(style).scaled(density) }
    }
    CssLayout(container, insets, modifier) {
        when {
            text != null -> CssText(text, style, transitions, platform)
            node.isImage -> if (image != null) CssImage(image, style)
            else -> for (child in node.children) key(child) { CssNode(child, tree, platform) }
        }
    }
}

@Composable
private fun CssText(raw: String, style: ComputedStyle, transitions: List<TransitionSpec>, platform: CompanionPlatform) {
    val density = LocalDensity.current.density
    val color by animateColorAsState(Color(style.color), spec(transitions, "color"), label = "color")
    val family = remember(style.fontFamilies) { platform.fontFamily(style.fontFamilies) }
    val text = when (style.keyword("text-transform")) {
        "uppercase" -> raw.uppercase()
        "lowercase" -> raw.lowercase()
        "capitalize" -> raw.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
        else -> raw
    }
    val decorations = CssValues.splitSpaces(style.keyword("text-decoration-line"))
    val decoration = TextDecoration.combine(
        listOfNotNull(
            TextDecoration.Underline.takeIf { "underline" in decorations },
            TextDecoration.LineThrough.takeIf { "line-through" in decorations },
        ),
    )
    val shadow = style.textShadow?.let {
        TextShadow(Color(it.color), androidx.compose.ui.geometry.Offset(it.offsetX * density, it.offsetY * density), it.blur * density)
    }
    val noWrap = style.keyword("white-space").let { it == "nowrap" || it == "pre" }
    val ellipsis = style.keyword("text-overflow") == "ellipsis"
    val clamp = style.lineClamp
    BasicText(
        text = text,
        style = TextStyle(
            color = color,
            // CSS px are dp; font sizes use sp so the system text size setting still applies.
            fontSize = style.fontSizePx.sp,
            fontWeight = FontWeight(style.fontWeight.coerceIn(1, 1000)),
            fontStyle = if (style.keyword("font-style") == "normal") FontStyle.Normal else FontStyle.Italic,
            fontFamily = family,
            letterSpacing = style.letterSpacingPx.sp,
            lineHeight = style.lineHeightPx?.sp ?: TextUnit.Unspecified,
            textAlign = when (style.keyword("text-align")) {
                "left" -> TextAlign.Left
                "right" -> TextAlign.Right
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                "justify" -> TextAlign.Justify
                else -> TextAlign.Start
            },
            textDecoration = decoration,
            shadow = shadow,
        ),
        overflow = if (ellipsis || clamp != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        softWrap = !noWrap,
        maxLines = when {
            noWrap -> 1
            clamp != null -> clamp
            else -> Int.MAX_VALUE
        },
    )
}

@Composable
private fun CssImage(image: ImageBitmap, style: ComputedStyle) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = when (style.keyword("object-fit")) {
            "cover" -> ContentScale.Crop
            "fill" -> ContentScale.FillBounds
            "none" -> ContentScale.None
            "scale-down" -> ContentScale.Inside
            else -> ContentScale.Fit
        },
        filterQuality = if (style.keyword("image-rendering").let { it == "pixelated" || it == "crisp-edges" }) FilterQuality.None else FilterQuality.Low,
    )
}

// ---- styles → paint --------------------------------------------------------------

private fun boxPaint(node: CompanionNode, style: ComputedStyle, density: Float, platform: CompanionPlatform): BoxPaint {
    val image = style.backgroundUrl?.let { platform.cssImage(it, style.baseDir("background-image")) }
    var backgroundColor = style.backgroundColor
    if (node.kind == CompanionNode.Kind.FILL && backgroundColor ushr 24 == 0 && image == null && style.backgroundGradient == null) {
        // A bar's fill defaults to the bar's accent-color.
        backgroundColor = node.parent?.computed?.takeIf { it.keyword("accent-color") != "auto" }?.color("accent-color") ?: DEFAULT_ACCENT
    }
    val sizeParts = CssValues.splitSpaces(style.keyword("background-size"))
    fun px(token: String?): Float? = token?.let { CssValues.length(it, style.lengthContext) }?.let { (it as? Length.Px)?.px?.times(density) }
    val imageSize = when (sizeParts.firstOrNull()) {
        "cover" -> BoxPaint.ImageSize.Cover
        "contain" -> BoxPaint.ImageSize.Contain
        null, "auto" -> if (sizeParts.size < 2) BoxPaint.ImageSize.Auto else BoxPaint.ImageSize.Explicit(px(sizeParts[0]), px(sizeParts[1]))
        else -> BoxPaint.ImageSize.Explicit(px(sizeParts.getOrNull(0)), px(sizeParts.getOrNull(1)))
    }
    val repeat = style.keyword("background-repeat")
    val sides = Properties.SIDES
    return BoxPaint(
        backgroundColor = Color(backgroundColor),
        gradient = style.backgroundGradient,
        image = image,
        imageSize = imageSize,
        imagePosition = style.backgroundPosition,
        imageRepeatX = repeat == "repeat" || repeat == "repeat-x",
        imageRepeatY = repeat == "repeat" || repeat == "repeat-y",
        imageSmooth = style.keyword("image-rendering").let { it != "pixelated" && it != "crisp-edges" },
        borderWidths = sides.map { style.borderWidth(it) * density },
        borderColors = sides.map { Color(style.color("border-$it-color")) },
        borderStyles = sides.map { style.keyword("border-$it-style") },
        radii = Properties.CORNERS.map { corner ->
            when (val r = style.length("border-$corner-radius")) {
                is Length.Px -> r.px * density
                is Length.Percent -> -r.percent
                Length.Auto -> 0f
            }
        },
        shadow = style.boxShadow?.let { it.copy(offsetX = it.offsetX * density, offsetY = it.offsetY * density, blur = it.blur * density, spread = it.spread * density) },
    )
}

/** `position: relative` offsets (px only), in device px. */
private fun relativeOffset(style: ComputedStyle, density: Float): Pair<Float, Float> {
    if (style.keyword("position") != "relative") return 0f to 0f
    val box = BoxStyle.from(style)
    val x = (box.left as? Length.Px)?.px ?: (box.right as? Length.Px)?.px?.unaryMinus() ?: 0f
    val y = (box.top as? Length.Px)?.px ?: (box.bottom as? Length.Px)?.px?.unaryMinus() ?: 0f
    return x * density to y * density
}

private fun resolveTranslate(length: Length, size: Float, density: Float): Float = when (length) {
    is Length.Px -> length.px * density
    is Length.Percent -> size * length.percent / 100f
    Length.Auto -> 0f
}

// ---- transitions -------------------------------------------------------------------

/** The CSS transition for [property] as a Compose animation spec (an immediate jump if none). */
private fun <T> spec(transitions: List<TransitionSpec>, property: String): AnimationSpec<T> {
    val t = transitions.lastOrNull { it.property == property || it.property == "all" }?.takeIf { it.durationMs > 0 } ?: return snap()
    return tween(
        durationMillis = t.durationMs.toInt(),
        delayMillis = t.delayMs.toInt(),
        easing = CubicBezierEasing(t.easing.x1, t.easing.y1, t.easing.x2, t.easing.y2),
    )
}

/** Animates a length within its unit; switching unit (px ↔ %) jumps. */
@Composable
private fun animateLength(target: Length, spec: AnimationSpec<Float>): Length = when (target) {
    is Length.Px -> Length.Px(animateFloatAsState(target.px, spec, label = "px").value)
    is Length.Percent -> Length.Percent(animateFloatAsState(target.percent, spec, label = "percent").value)
    Length.Auto -> Length.Auto
}
