package com.khumam.gbaduo.companion.style

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A length as declared: px (incl. resolved em/rem/vw/vh), a percentage of the
 * containing box, `px + percent%` (from `calc()`, e.g. `calc(100% - 20px)`), or `auto`.
 */
sealed class Length {
    data class Px(val px: Float) : Length()
    data class Percent(val percent: Float) : Length()
    /** `calc()` mixing both: [px] plus [percent]% of the containing box. */
    data class Calc(val px: Float, val percent: Float) : Length()
    object Auto : Length() {
        override fun toString() = "Auto"
    }

    /** px for [Px], [percent]% of [base] for [Percent] and [Calc] (null base → null), null for [Auto]. */
    fun resolve(base: Float?): Float? = when (this) {
        is Px -> px
        is Percent -> base?.let { it * percent / 100f }
        is Calc -> base?.let { px + it * percent / 100f }
        Auto -> null
    }
}

/** What relative units resolve against. */
data class LengthContext(
    val fontSize: Float = 14f,
    val rootFontSize: Float = 14f,
    val viewportWidth: Float = 0f,
    val viewportHeight: Float = 0f,
)

data class GradientStop(val color: Int, val position: Float?)

/** `linear-gradient(...)`: [angleDeg] (0 = to top, 90 = to right), or a corner resolved at draw time. */
data class LinearGradient(val angleDeg: Float?, val toCorner: Pair<Int, Int>?, val stops: List<GradientStop>)

data class Shadow(val offsetX: Float, val offsetY: Float, val blur: Float, val spread: Float, val color: Int)

data class Transform(
    val translateX: Length = Length.Px(0f),
    val translateY: Length = Length.Px(0f),
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotateDeg: Float = 0f,
) {
    companion object {
        val NONE = Transform()
    }
}

/** Timing function as cubic-bezier control points (linear = 0,0,1,1). */
data class Easing(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    companion object {
        val LINEAR = Easing(0f, 0f, 1f, 1f)
        val EASE = Easing(0.25f, 0.1f, 0.25f, 1f)
        val EASE_IN = Easing(0.42f, 0f, 1f, 1f)
        val EASE_OUT = Easing(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = Easing(0.42f, 0f, 0.58f, 1f)
    }
}

data class TransitionSpec(val property: String, val durationMs: Long, val easing: Easing, val delayMs: Long)

/** Parsers for CSS values. All return null for anything they don't understand. Pure, no Android deps. */
object CssValues {

    // ---- tokenizing -------------------------------------------------------------

    /** Splits on top-level whitespace, keeping `fn(a, b)` and quoted strings intact. */
    fun splitSpaces(value: String): List<String> = splitTopLevel(value) { it.isWhitespace() }

    /** Splits on top-level commas. */
    fun splitCommas(value: String): List<String> = splitTopLevel(value) { it == ',' }

    private inline fun splitTopLevel(value: String, isSeparator: (Char) -> Boolean): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in value) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth = maxOf(0, depth - 1)
                    current.append(c)
                }
                depth == 0 && isSeparator(c) -> {
                    if (current.isNotBlank()) parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts
    }

    /** `name(args)` → (name, args) for a single function token, else null. */
    fun function(token: String): Pair<String, String>? {
        val open = token.indexOf('(')
        if (open <= 0 || !token.endsWith(")")) return null
        val name = token.substring(0, open).trim().lowercase()
        if (!name.all { it.isLetterOrDigit() || it == '-' }) return null
        return name to token.substring(open + 1, token.length - 1).trim()
    }

    fun unquote(value: String): String {
        val v = value.trim()
        return if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]) v.substring(1, v.length - 1) else v
    }

    // ---- numbers, lengths, time, angles -------------------------------------

    private val NUMBER = Regex("^([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)([a-zA-Z%]*)$")

    /** (number, unit) where unit is lower-case and may be empty. */
    fun numberWithUnit(token: String): Pair<Float, String>? {
        val match = NUMBER.matchEntire(token.trim()) ?: return null
        val number = match.groupValues[1].toFloatOrNull() ?: return null
        return number to match.groupValues[2].lowercase()
    }

    fun number(token: String): Float? = numberWithUnit(token)?.takeIf { it.second.isEmpty() }?.first

    /** A length; `allowPercent=false` rejects %, `allowAuto=false` rejects `auto`. Unitless numbers are px. */
    fun length(token: String, context: LengthContext = LengthContext(), allowPercent: Boolean = true, allowAuto: Boolean = true): Length? {
        val t = token.trim().lowercase()
        if (t == "auto") return if (allowAuto) Length.Auto else null
        if (MathFunctions.isMath(t)) return MathFunctions.length(t, context, allowPercent)
        val (n, unit) = numberWithUnit(t) ?: return null
        return when (unit) {
            "", "px", "dp" -> Length.Px(n)
            "%" -> if (allowPercent) Length.Percent(n) else null
            "em" -> Length.Px(n * context.fontSize)
            "rem" -> Length.Px(n * context.rootFontSize)
            "vw" -> Length.Px(n * context.viewportWidth / 100f)
            "vh" -> Length.Px(n * context.viewportHeight / 100f)
            "vmin" -> Length.Px(n * minOf(context.viewportWidth, context.viewportHeight) / 100f)
            "vmax" -> Length.Px(n * maxOf(context.viewportWidth, context.viewportHeight) / 100f)
            else -> null
        }
    }

    /** A length that must resolve to px right away (borders, radii, shadows, gaps). */
    fun px(token: String, context: LengthContext = LengthContext()): Float? =
        (length(token, context, allowPercent = false, allowAuto = false) as? Length.Px)?.px

    fun timeMs(token: String): Long? {
        val (n, unit) = numberWithUnit(token) ?: return null
        return when (unit) {
            "ms" -> n.roundToInt().toLong()
            "s" -> (n * 1000).roundToInt().toLong()
            else -> null
        }.takeIf { it != null && it >= 0 }
    }

    fun angleDeg(token: String): Float? {
        val (n, unit) = numberWithUnit(token) ?: return null
        return when (unit) {
            "deg" -> n
            "turn" -> n * 360f
            "rad" -> Math.toDegrees(n.toDouble()).toFloat()
            "grad" -> n * 0.9f
            "" -> if (n == 0f) 0f else null
            else -> null
        }
    }

    // ---- colors ---------------------------------------------------------------

    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(), "lime" to 0xFF00FF00.toInt(), "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "aqua" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(), "fuchsia" to 0xFFFF00FF.toInt(), "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(), "silver" to 0xFFC0C0C0.toInt(), "maroon" to 0xFF800000.toInt(),
        "olive" to 0xFF808000.toInt(), "navy" to 0xFF000080.toInt(), "purple" to 0xFF800080.toInt(),
        "teal" to 0xFF008080.toInt(), "orange" to 0xFFFFA500.toInt(), "pink" to 0xFFFFC0CB.toInt(),
        "brown" to 0xFFA52A2A.toInt(), "gold" to 0xFFFFD700.toInt(), "indigo" to 0xFF4B0082.toInt(),
        "violet" to 0xFFEE82EE.toInt(), "crimson" to 0xFFDC143C.toInt(), "coral" to 0xFFFF7F50.toInt(),
        "salmon" to 0xFFFA8072.toInt(), "tomato" to 0xFFFF6347.toInt(), "khaki" to 0xFFF0E68C.toInt(),
        "darkgray" to 0xFFA9A9A9.toInt(), "darkgrey" to 0xFFA9A9A9.toInt(), "lightgray" to 0xFFD3D3D3.toInt(),
        "lightgrey" to 0xFFD3D3D3.toInt(), "dimgray" to 0xFF696969.toInt(), "dimgrey" to 0xFF696969.toInt(),
        "darkred" to 0xFF8B0000.toInt(), "darkgreen" to 0xFF006400.toInt(), "darkblue" to 0xFF00008B.toInt(),
        "skyblue" to 0xFF87CEEB.toInt(), "steelblue" to 0xFF4682B4.toInt(), "royalblue" to 0xFF4169E1.toInt(),
        "limegreen" to 0xFF32CD32.toInt(), "seagreen" to 0xFF2E8B57.toInt(), "forestgreen" to 0xFF228B22.toInt(),
        "orangered" to 0xFFFF4500.toInt(), "hotpink" to 0xFFFF69B4.toInt(), "deeppink" to 0xFFFF1493.toInt(),
        "turquoise" to 0xFF40E0D0.toInt(), "tan" to 0xFFD2B48C.toInt(), "beige" to 0xFFF5F5DC.toInt(),
        "ivory" to 0xFFFFFFF0.toInt(), "lavender" to 0xFFE6E6FA.toInt(), "plum" to 0xFFDDA0DD.toInt(),
        "orchid" to 0xFFDA70D6.toInt(), "chocolate" to 0xFFD2691E.toInt(), "firebrick" to 0xFFB22222.toInt(),
        "slategray" to 0xFF708090.toInt(), "slategrey" to 0xFF708090.toInt(), "midnightblue" to 0xFF191970.toInt(),
        "transparent" to 0x00000000,
    )

    /** ARGB int. Hex follows CSS order: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`. */
    fun color(token: String): Int? {
        val t = token.trim().lowercase()
        NAMED_COLORS[t]?.let { return it }
        if (t.startsWith("#")) return hexColor(t.substring(1))
        val (name, args) = function(t) ?: return null
        return when (name) {
            "rgb", "rgba" -> rgbFunction(args)
            "hsl", "hsla" -> hslFunction(args)
            else -> null
        }
    }

    private fun hexColor(hex: String): Int? {
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        fun nibble(i: Int) = hex[i].digitToInt(16) * 17
        fun byte(i: Int) = hex.substring(i, i + 2).toInt(16)
        val (r, g, b, a) = when (hex.length) {
            3 -> listOf(nibble(0), nibble(1), nibble(2), 255)
            4 -> listOf(nibble(0), nibble(1), nibble(2), nibble(3))
            6 -> listOf(byte(0), byte(2), byte(4), 255)
            8 -> listOf(byte(0), byte(2), byte(4), byte(6))
            else -> return null
        }
        return argb(a, r, g, b)
    }

    /** `r, g, b[, a]` or `r g b[ / a]`. */
    private fun colorArgs(args: String): Pair<List<String>, String?>? {
        val (main, slashAlpha) = if ('/' in args) {
            val idx = args.indexOf('/')
            args.substring(0, idx) to args.substring(idx + 1).trim()
        } else {
            args to null
        }
        val parts = if (',' in main) main.split(',').map { it.trim() } else splitSpaces(main)
        return when {
            slashAlpha != null && parts.size == 3 -> parts to slashAlpha
            slashAlpha == null && parts.size == 4 -> parts.take(3) to parts[3]
            slashAlpha == null && parts.size == 3 -> parts to null
            else -> null
        }
    }

    private fun alpha(token: String?): Int? {
        if (token == null) return 255
        val (n, unit) = numberWithUnit(token) ?: return null
        val fraction = when (unit) {
            "" -> n
            "%" -> n / 100f
            else -> return null
        }
        return (fraction.coerceIn(0f, 1f) * 255).roundToInt()
    }

    private fun rgbFunction(args: String): Int? {
        val (parts, alphaToken) = colorArgs(args) ?: return null
        val channels = parts.map { part ->
            val (n, unit) = numberWithUnit(part) ?: return null
            when (unit) {
                "" -> n.roundToInt().coerceIn(0, 255)
                "%" -> (n / 100f * 255).roundToInt().coerceIn(0, 255)
                else -> return null
            }
        }
        val a = alpha(alphaToken) ?: return null
        return argb(a, channels[0], channels[1], channels[2])
    }

    private fun hslFunction(args: String): Int? {
        val (parts, alphaToken) = colorArgs(args) ?: return null
        val h = (numberWithUnit(parts[0])?.let { (n, unit) -> if (unit == "" || unit == "deg") n else angleDeg(parts[0]) } ?: return null)
        val s = numberWithUnit(parts[1])?.takeIf { it.second == "%" }?.first?.div(100f) ?: return null
        val l = numberWithUnit(parts[2])?.takeIf { it.second == "%" }?.first?.div(100f) ?: return null
        val a = alpha(alphaToken) ?: return null
        val hue = ((h % 360f) + 360f) % 360f / 360f
        val (r, g, b) = hslToRgb(hue, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        return argb(a, r, g, b)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Int, Int, Int> {
        if (s == 0f) {
            val v = (l * 255).roundToInt()
            return Triple(v, v, v)
        }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(tIn: Float): Int {
            var t = tIn
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            val v = when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
            return (v * 255).roundToInt().coerceIn(0, 255)
        }
        return Triple(channel(h + 1f / 3), channel(h), channel(h - 1f / 3))
    }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Linear blend between two ARGB colors (used by transitions). */
    fun mixColor(from: Int, to: Int, fraction: Float): Int {
        fun ch(c: Int, shift: Int) = (c ushr shift) and 0xFF
        fun lerp(a: Int, b: Int) = (a + (b - a) * fraction).roundToInt().coerceIn(0, 255)
        return argb(lerp(ch(from, 24), ch(to, 24)), lerp(ch(from, 16), ch(to, 16)), lerp(ch(from, 8), ch(to, 8)), lerp(ch(from, 0), ch(to, 0)))
    }

    /**
     * Android's `Color.parseColor` reads 8-digit hex as `#AARRGGBB`. Widget keys from
     * older layouts (`background`, `color`, `borderColor`) were written for it, so they're
     * converted to an unambiguous `rgba()` before entering the CSS cascade.
     */
    fun androidColorToCss(value: String): String {
        val t = value.trim()
        if (t.startsWith("#") && t.length == 9 && t.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val v = t.substring(1).toLong(16)
            val a = (v shr 24) and 0xFF
            val r = (v shr 16) and 0xFF
            val g = (v shr 8) and 0xFF
            val b = v and 0xFF
            val alphaText = "%.3f".format(java.util.Locale.ROOT, a / 255f).trimEnd('0').trimEnd('.').ifEmpty { "0" }
            return "rgba($r, $g, $b, $alphaText)"
        }
        return t
    }

    // ---- images, gradients, shadows, transforms, transitions ----------------

    /** `url(path)` → path, else null. */
    fun url(token: String): String? {
        val (name, args) = function(token) ?: return null
        return if (name == "url") unquote(args).takeIf { it.isNotBlank() } else null
    }

    fun linearGradient(token: String): LinearGradient? {
        val (name, args) = function(token) ?: return null
        if (name != "linear-gradient") return null
        val parts = splitCommas(args)
        if (parts.isEmpty()) return null
        var angle: Float? = 180f
        var corner: Pair<Int, Int>? = null
        var stopParts = parts
        val first = parts[0].lowercase()
        if (first.startsWith("to ")) {
            val words = first.removePrefix("to ").trim().split(Regex("\\s+"))
            var x = 0
            var y = 0
            for (w in words) {
                when (w) {
                    "left" -> x = -1
                    "right" -> x = 1
                    "top" -> y = -1
                    "bottom" -> y = 1
                    else -> return null
                }
            }
            when {
                x != 0 && y != 0 -> {
                    angle = null
                    corner = x to y
                }
                x == 1 -> angle = 90f
                x == -1 -> angle = 270f
                y == -1 -> angle = 0f
                y == 1 -> angle = 180f
            }
            stopParts = parts.drop(1)
        } else {
            angleDeg(first)?.let {
                angle = it
                stopParts = parts.drop(1)
            }
        }
        val stops = stopParts.map { part ->
            val tokens = splitSpaces(part)
            val color = color(tokens.firstOrNull() ?: return null) ?: return null
            val position = tokens.getOrNull(1)?.let { p ->
                val (n, unit) = numberWithUnit(p) ?: return null
                if (unit != "%") return null
                n / 100f
            }
            GradientStop(color, position)
        }
        if (stops.size < 2) return null
        return LinearGradient(angle, corner, stops)
    }

    /** First shadow of a `box-shadow`/`text-shadow` list: `x y [blur [spread]] color`. `inset` isn't supported. */
    fun shadow(value: String, context: LengthContext = LengthContext(), allowSpread: Boolean = true): Shadow? {
        val v = value.trim().lowercase()
        if (v == "none") return null
        val tokens = splitSpaces(splitCommas(v).firstOrNull() ?: return null)
        if ("inset" in tokens) return null
        var color: Int? = null
        val lengths = mutableListOf<Float>()
        for (t in tokens) {
            val asColor = if (numberWithUnit(t) == null) color(t) else null
            when {
                asColor != null -> color = asColor
                else -> lengths += px(t, context) ?: return null
            }
        }
        if (lengths.size < 2 || lengths.size > (if (allowSpread) 4 else 3)) return null
        return Shadow(lengths[0], lengths[1], lengths.getOrElse(2) { 0f }.coerceAtLeast(0f), lengths.getOrElse(3) { 0f }, color ?: 0x80000000.toInt())
    }

    fun transform(value: String, context: LengthContext = LengthContext()): Transform? {
        if (value.trim().lowercase() == "none") return Transform.NONE
        var result = Transform.NONE
        for (fn in splitSpaces(value)) {
            val (name, args) = function(fn) ?: return null
            val parts = splitCommas(args)
            fun len(i: Int) = length(parts[i], context, allowAuto = false)
            fun num(i: Int) = number(parts[i])
            result = when (name) {
                "translate" -> {
                    if (parts.isEmpty() || parts.size > 2) return null
                    result.copy(translateX = len(0) ?: return null, translateY = if (parts.size == 2) len(1) ?: return null else Length.Px(0f))
                }
                "translatex" -> result.copy(translateX = (if (parts.size == 1) len(0) else null) ?: return null)
                "translatey" -> result.copy(translateY = (if (parts.size == 1) len(0) else null) ?: return null)
                "scale" -> {
                    if (parts.isEmpty() || parts.size > 2) return null
                    val sx = num(0) ?: return null
                    result.copy(scaleX = sx, scaleY = if (parts.size == 2) num(1) ?: return null else sx)
                }
                "scalex" -> result.copy(scaleX = (if (parts.size == 1) num(0) else null) ?: return null)
                "scaley" -> result.copy(scaleY = (if (parts.size == 1) num(0) else null) ?: return null)
                "rotate" -> result.copy(rotateDeg = (if (parts.size == 1) angleDeg(parts[0]) else null) ?: return null)
                else -> return null
            }
        }
        return result
    }

    fun easing(token: String): Easing? {
        val t = token.trim().lowercase()
        return when (t) {
            "linear" -> Easing.LINEAR
            "ease" -> Easing.EASE
            "ease-in" -> Easing.EASE_IN
            "ease-out" -> Easing.EASE_OUT
            "ease-in-out" -> Easing.EASE_IN_OUT
            else -> {
                val (name, args) = function(t) ?: return null
                if (name != "cubic-bezier") return null
                val n = splitCommas(args).map { number(it) ?: return null }
                if (n.size != 4 || n[0] !in 0f..1f || n[2] !in 0f..1f) return null
                Easing(n[0], n[1], n[2], n[3])
            }
        }
    }

    /** `transition: <property> <duration> [<easing>] [<delay>], ...`. */
    fun transitions(value: String): List<TransitionSpec>? {
        if (value.trim().lowercase() == "none") return emptyList()
        return splitCommas(value).map { item ->
            var property = "all"
            var duration: Long? = null
            var delay: Long? = null
            var easing = Easing.EASE
            for (t in splitSpaces(item)) {
                val time = timeMs(t)
                val ease = easing(t)
                when {
                    time != null && duration == null -> duration = time
                    time != null -> delay = time
                    ease != null -> easing = ease
                    t.all { it.isLetterOrDigit() || it == '-' } -> property = t.lowercase()
                    else -> return null
                }
            }
            TransitionSpec(property, duration ?: 0L, easing, delay ?: 0L)
        }
    }

    /** Float comparison with a tolerance, for animations deciding whether a value changed. */
    fun nearlyEqual(a: Float, b: Float, epsilon: Float = 0.001f) = abs(a - b) <= epsilon
}
