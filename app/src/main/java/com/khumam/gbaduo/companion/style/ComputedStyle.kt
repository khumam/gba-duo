package com.khumam.gbaduo.companion.style

import java.io.File

/**
 * The resolved style of one [StyleNode]: every longhand that was set (after
 * cascade, `var()` substitution and inheritance), plus typed accessors that fall
 * back to the property's initial value. Pure, no Android deps.
 */
class ComputedStyle(
    private val values: Map<String, String>,
    val customProperties: Map<String, String>,
    /** Where each property's `url()` resolves from. */
    private val baseDirs: Map<String, File?>,
    val fontSizePx: Float,
    val rootFontSizePx: Float,
    val fontWeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
) {
    val lengthContext: LengthContext get() = LengthContext(fontSizePx, rootFontSizePx, viewportWidth, viewportHeight)

    /** Declared (or inherited) value, else the initial value. */
    fun raw(property: String): String = values[property] ?: Properties.def(property)?.initial ?: ""

    fun isSet(property: String) = property in values

    fun keyword(property: String): String = raw(property).trim().lowercase()

    fun baseDir(property: String): File? = baseDirs[property]

    // ---- typed accessors ------------------------------------------------------

    fun length(property: String): Length = CssValues.length(raw(property), lengthContext)
        ?: CssValues.length(Properties.def(property)?.initial ?: "auto", lengthContext) ?: Length.Auto

    /** `max-width`/`max-height`: null for `none`. */
    fun maxLength(property: String): Length? = if (keyword(property) == "none") null else length(property)

    fun px(property: String): Float = CssValues.px(raw(property), lengthContext) ?: 0f

    fun number(property: String, default: Float = 0f): Float = CssValues.number(raw(property)) ?: default

    val color: Int get() = CssValues.color(raw("color")) ?: 0xFFFFFFFF.toInt()

    /** A color property; `currentcolor` (and invalid values) resolve to [color]. */
    fun color(property: String): Int {
        val value = keyword(property)
        if (value == "currentcolor") return color
        return CssValues.color(value) ?: color
    }

    fun borderWidth(side: String): Float {
        val style = keyword("border-$side-style")
        if (style == "none" || style == "hidden") return 0f
        return when (val w = keyword("border-$side-width")) {
            "thin" -> 1f
            "medium" -> 3f
            "thick" -> 5f
            else -> (CssValues.px(w, lengthContext) ?: 0f).coerceAtLeast(0f)
        }
    }

    fun padding(side: String): Float = px("padding-$side").coerceAtLeast(0f)

    val opacity: Float
        get() = CssValues.numberWithUnit(raw("opacity"))?.let { (n, unit) -> if (unit == "%") n / 100f else n }?.coerceIn(0f, 1f) ?: 1f

    val transform: Transform get() = CssValues.transform(raw("transform"), lengthContext) ?: Transform.NONE

    val transformOrigin: Pair<Float, Float> get() = Properties.backgroundPosition(raw("transform-origin")) ?: (0.5f to 0.5f)

    val transitions: List<TransitionSpec> get() = CssValues.transitions(raw("transition")) ?: emptyList()

    val boxShadow: Shadow? get() = CssValues.shadow(raw("box-shadow"), lengthContext)

    val textShadow: Shadow? get() = CssValues.shadow(raw("text-shadow"), lengthContext, allowSpread = false)

    val backgroundColor: Int get() = if (keyword("background-color") == "currentcolor") color else CssValues.color(raw("background-color")) ?: 0

    val backgroundGradient: LinearGradient? get() = CssValues.linearGradient(raw("background-image"))

    val backgroundUrl: String? get() = CssValues.url(raw("background-image"))

    val backgroundPosition: Pair<Float, Float> get() = Properties.backgroundPosition(raw("background-position")) ?: (0f to 0f)

    val aspectRatio: Float? get() = if (keyword("aspect-ratio") == "auto") null else Properties.aspectRatio(raw("aspect-ratio"))

    val zIndex: Int? get() = raw("z-index").trim().toIntOrNull()

    val lineClamp: Int? get() = raw("line-clamp").trim().toIntOrNull()

    /** Radius in px for [corner] given the box size (percentages are of the box's width/height). */
    fun radius(corner: String, width: Float, height: Float): Float = when (val r = length("border-$corner-radius")) {
        is Length.Px -> r.px
        is Length.Percent -> minOf(width, height) * r.percent / 100f
        is Length.Calc -> r.px + minOf(width, height) * r.percent / 100f
        Length.Auto -> 0f
    }.coerceAtLeast(0f)

    /** The font families in `font-family`, unquoted, in order. */
    val fontFamilies: List<String> get() = CssValues.splitCommas(raw("font-family")).map { CssValues.unquote(it) }

    /** Letter spacing in px (0 for `normal`). */
    val letterSpacingPx: Float get() = if (keyword("letter-spacing") == "normal") 0f else px("letter-spacing")

    /** Line height in px, or null for `normal`. A unitless number multiplies the font size. */
    val lineHeightPx: Float?
        get() {
            val v = keyword("line-height")
            if (v == "normal") return null
            CssValues.number(v)?.let { return it * fontSizePx }
            return when (val len = CssValues.length(v, lengthContext, allowAuto = false)) {
                is Length.Px -> len.px
                is Length.Percent -> fontSizePx * len.percent / 100f
                is Length.Calc -> len.px + fontSizePx * len.percent / 100f
                else -> null
            }
        }

    // ---- comparison, for change detection --------------------------------------

    private val identity by lazy { listOf(values, customProperties, fontSizePx, rootFontSizePx, fontWeight, viewportWidth, viewportHeight) }

    override fun equals(other: Any?): Boolean = other is ComputedStyle && other.identity == identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "ComputedStyle($values)"

    companion object {
        /** The style of a node with no rules at all (used by tests and as the root's parent). */
        fun initial(viewportWidth: Float = 0f, viewportHeight: Float = 0f): ComputedStyle =
            ComputedStyle(emptyMap(), emptyMap(), emptyMap(), 14f, 14f, 400, viewportWidth, viewportHeight)
    }
}
