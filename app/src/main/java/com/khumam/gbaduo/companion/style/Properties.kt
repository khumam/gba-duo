package com.khumam.gbaduo.companion.style

/**
 * Every supported longhand property: whether it inherits, its initial value, and
 * how to validate a value. Unknown properties and invalid values are dropped
 * with a warning, the way browsers do. Pure, no Android deps.
 */
object Properties {

    class Def(val inherited: Boolean, val initial: String, val valid: (String) -> Boolean)

    private fun keywords(vararg words: String): (String) -> Boolean = { it.trim().lowercase() in words }
    private val anyLength: (String) -> Boolean = { CssValues.length(it) != null }
    private val lengthNoAuto: (String) -> Boolean = { CssValues.length(it, allowAuto = false) != null }
    // A calc()/min()/max()/clamp() result can only be checked for sign once the screen size is
    // known (`calc(5vw - 4px)` is negative on an unmeasured screen), so it's accepted here and
    // clamped to 0 where used, as CSS does.
    private val pxOnly: (String) -> Boolean = { v -> CssValues.px(v)?.let { it >= 0 || MathFunctions.isMath(v) } ?: false }
    private val color: (String) -> Boolean = { CssValues.color(it) != null }
    private val nonNegativeNumber: (String) -> Boolean = { (CssValues.number(it) ?: -1f) >= 0f }
    private val integer: (String) -> Boolean = { it.trim().toIntOrNull() != null }
    private val maxSize: (String) -> Boolean = { it.trim().lowercase() == "none" || lengthNoAuto(it) }
    private val radius: (String) -> Boolean = { v ->
        (CssValues.length(v, allowAuto = false) as? Length.Px)?.px?.let { px -> px >= 0 || MathFunctions.isMath(v) }
            ?: (CssValues.length(v, allowAuto = false) is Length.Percent)
    }
    private val offset: (String) -> Boolean = anyLength

    val SIDES = listOf("top", "right", "bottom", "left")
    val CORNERS = listOf("top-left", "top-right", "bottom-right", "bottom-left")

    private val ALIGN = arrayOf("stretch", "flex-start", "start", "flex-end", "end", "center", "baseline", "normal")
    private val JUSTIFY = arrayOf("flex-start", "start", "flex-end", "end", "center", "space-between", "space-around", "space-evenly", "normal", "left", "right")

    val defs: Map<String, Def> = buildMap {
        fun def(name: String, inherited: Boolean, initial: String, valid: (String) -> Boolean) = put(name, Def(inherited, initial, valid))

        // Layout
        def("display", false, "flex", keywords("flex", "grid", "stack", "none", "block"))
        def("flex-direction", false, "row", keywords("row", "row-reverse", "column", "column-reverse"))
        def("flex-wrap", false, "nowrap", keywords("nowrap", "wrap", "wrap-reverse"))
        def("justify-content", false, "normal", keywords(*JUSTIFY))
        def("align-items", false, "normal", keywords(*ALIGN))
        def("align-self", false, "auto", keywords("auto", *ALIGN))
        def("align-content", false, "normal", keywords("stretch", "flex-start", "start", "flex-end", "end", "center", "space-between", "space-around", "space-evenly", "normal"))
        def("justify-items", false, "normal", keywords("stretch", "start", "end", "center", "normal", "left", "right", "flex-start", "flex-end"))
        def("justify-self", false, "auto", keywords("auto", "stretch", "start", "end", "center", "normal", "left", "right", "flex-start", "flex-end"))
        def("flex-grow", false, "0", nonNegativeNumber)
        def("flex-shrink", false, "1", nonNegativeNumber)
        def("flex-basis", false, "auto", { it.trim().lowercase() == "content" || anyLength(it) })
        def("order", false, "0", integer)
        def("row-gap", false, "0", pxOnly)
        def("column-gap", false, "0", pxOnly)
        def("grid-template-columns", false, "none", { GridTracks.parse(it) != null })
        def("grid-template-rows", false, "none", { GridTracks.parse(it) != null })
        def("grid-column", false, "auto", { GridTracks.placement(it) != null })
        def("grid-row", false, "auto", { GridTracks.placement(it) != null })
        for (side in listOf("width", "height")) {
            def(side, false, "auto", anyLength)
            def("min-$side", false, "auto", anyLength)
            def("max-$side", false, "none", maxSize)
        }
        def("aspect-ratio", false, "auto", { it.trim().lowercase() == "auto" || aspectRatio(it) != null })
        def("box-sizing", false, "border-box", keywords("border-box", "content-box"))
        for (side in SIDES) {
            def("margin-$side", false, "0", anyLength)
            def("padding-$side", false, "0", pxOnly)
            def(side, false, "auto", offset)
            def("border-$side-width", false, "0", { pxOnly(it) || it.trim().lowercase() in setOf("thin", "medium", "thick") })
            def("border-$side-style", false, "none", keywords("none", "solid", "dashed", "dotted", "hidden"))
            def("border-$side-color", false, "currentcolor", { it.trim().lowercase() == "currentcolor" || color(it) })
        }
        for (corner in CORNERS) def("border-$corner-radius", false, "0", radius)
        def("position", false, "static", keywords("static", "relative", "absolute"))
        def("z-index", false, "auto", { it.trim().lowercase() == "auto" || integer(it) })
        def("overflow", false, "visible", keywords("visible", "hidden", "clip", "auto", "scroll"))

        // Paint
        def("background-color", false, "transparent", color)
        def("background-image", false, "none", { it.trim().lowercase() == "none" || CssValues.url(it) != null || CssValues.linearGradient(it) != null })
        def("background-size", false, "auto", { backgroundSize(it) })
        def("background-position", false, "0% 0%", { backgroundPosition(it) != null })
        def("background-repeat", false, "repeat", keywords("repeat", "no-repeat", "repeat-x", "repeat-y"))
        def("box-shadow", false, "none", { it.trim().lowercase() == "none" || CssValues.shadow(it) != null })
        def("opacity", false, "1", { v -> CssValues.numberWithUnit(v)?.let { (n, unit) -> unit == "" || unit == "%" } == true })
        def("visibility", true, "visible", keywords("visible", "hidden", "collapse"))
        def("transform", false, "none", { CssValues.transform(it) != null })
        def("transform-origin", false, "50% 50%", { backgroundPosition(it) != null })
        def("transition", false, "none", { CssValues.transitions(it) != null })
        def("accent-color", true, "auto", { it.trim().lowercase() == "auto" || color(it) })
        def("image-rendering", true, "auto", keywords("auto", "smooth", "pixelated", "crisp-edges"))
        def("object-fit", false, "contain", keywords("contain", "cover", "fill", "none", "scale-down"))

        // Text
        def("color", true, "#ffffff", color)
        def("font-family", true, "sans-serif", { it.isNotBlank() })
        def("font-size", true, "14px", { fontSizeValid(it) })
        def("font-weight", true, "normal", { fontWeightValid(it) })
        def("font-style", true, "normal", keywords("normal", "italic", "oblique"))
        def("letter-spacing", true, "normal", { it.trim().lowercase() == "normal" || CssValues.length(it, allowPercent = false, allowAuto = false) != null })
        def("line-height", true, "normal", { it.trim().lowercase() == "normal" || CssValues.number(it) != null || lengthNoAuto(it) })
        def("text-align", true, "start", keywords("left", "right", "center", "start", "end", "justify"))
        def("text-transform", true, "none", keywords("none", "uppercase", "lowercase", "capitalize"))
        def("text-shadow", true, "none", { it.trim().lowercase() == "none" || CssValues.shadow(it, allowSpread = false) != null })
        def("text-decoration-line", false, "none", { v -> CssValues.splitSpaces(v.lowercase()).all { it in setOf("none", "underline", "line-through") } })
        def("white-space", true, "normal", keywords("normal", "nowrap", "pre", "pre-wrap", "pre-line"))
        def("text-overflow", false, "clip", keywords("clip", "ellipsis"))
        def("line-clamp", false, "none", { it.trim().lowercase() == "none" || (it.trim().toIntOrNull() ?: 0) > 0 })
    }

    fun isCustom(property: String) = property.startsWith("--")

    fun def(property: String): Def? = defs[property]

    fun isKnown(property: String) = isCustom(property) || property in defs || property in SHORTHANDS

    private val SHORTHANDS = setOf(
        "margin", "padding", "inset", "border", "border-top", "border-right", "border-bottom", "border-left",
        "border-width", "border-style", "border-color", "border-radius", "background", "flex", "flex-flow",
        "gap", "grid-gap", "place-items", "place-self", "text-decoration", "-webkit-line-clamp",
    )

    // ---- value helpers ------------------------------------------------------

    fun aspectRatio(value: String): Float? {
        val v = value.trim()
        val parts = v.split('/').map { it.trim() }
        val ratio = when (parts.size) {
            1 -> CssValues.number(parts[0])
            2 -> {
                val w = CssValues.number(parts[0]) ?: return null
                val h = CssValues.number(parts[1]) ?: return null
                if (h == 0f) return null
                w / h
            }
            else -> null
        }
        return ratio?.takeIf { it > 0f }
    }

    private fun backgroundSize(value: String): Boolean {
        val t = value.trim().lowercase()
        if (t == "cover" || t == "contain" || t == "auto") return true
        val parts = CssValues.splitSpaces(t)
        return parts.size in 1..2 && parts.all { CssValues.length(it) != null }
    }

    /** `background-position`/`transform-origin` as (x, y) fractions of the box: keywords and percentages. */
    fun backgroundPosition(value: String): Pair<Float, Float>? {
        val parts = CssValues.splitSpaces(value.trim().lowercase())
        if (parts.isEmpty() || parts.size > 2) return null
        var x: Float? = null
        var y: Float? = null
        val loose = mutableListOf<Float>()
        for (p in parts) {
            when (p) {
                "left" -> x = 0f
                "right" -> x = 1f
                "top" -> y = 0f
                "bottom" -> y = 1f
                "center" -> loose += 0.5f
                else -> {
                    val (n, unit) = CssValues.numberWithUnit(p) ?: return null
                    if (unit != "%") return null
                    loose += n / 100f
                }
            }
        }
        for (v in loose) {
            if (x == null) x = v else if (y == null) y = v else return null
        }
        return (x ?: 0.5f) to (y ?: 0.5f)
    }

    private val FONT_SIZE_KEYWORDS = mapOf(
        "xx-small" to 9f, "x-small" to 10f, "small" to 12f, "medium" to 14f,
        "large" to 18f, "x-large" to 24f, "xx-large" to 32f,
    )

    private fun fontSizeValid(value: String): Boolean {
        val t = value.trim().lowercase()
        return t in FONT_SIZE_KEYWORDS || t == "smaller" || t == "larger" || CssValues.length(t, allowAuto = false) != null
    }

    /** Computed font size in px, relative to the parent's ([parentPx]) for em/%/smaller/larger. */
    fun fontSizePx(value: String, parentPx: Float, rootPx: Float, viewportWidth: Float, viewportHeight: Float): Float? {
        val t = value.trim().lowercase()
        FONT_SIZE_KEYWORDS[t]?.let { return it }
        if (t == "smaller") return parentPx / 1.2f
        if (t == "larger") return parentPx * 1.2f
        return when (val len = CssValues.length(t, LengthContext(parentPx, rootPx, viewportWidth, viewportHeight), allowAuto = false)) {
            is Length.Px -> if (MathFunctions.isMath(t)) len.px.coerceAtLeast(0f) else len.px.takeIf { it >= 0 }
            is Length.Percent -> parentPx * len.percent / 100f
            is Length.Calc -> (len.px + parentPx * len.percent / 100f).coerceAtLeast(0f)
            else -> null
        }
    }

    private fun fontWeightValid(value: String): Boolean {
        val t = value.trim().lowercase()
        return t in setOf("normal", "bold", "bolder", "lighter") || (t.toIntOrNull()?.let { it in 1..1000 } ?: false)
    }

    fun fontWeight(value: String, parentWeight: Int): Int {
        val t = value.trim().lowercase()
        return when (t) {
            "normal" -> 400
            "bold" -> 700
            "bolder" -> when {
                parentWeight < 350 -> 400
                parentWeight < 550 -> 700
                else -> 900
            }
            "lighter" -> when {
                parentWeight < 550 -> 100
                parentWeight < 750 -> 400
                else -> 700
            }
            else -> t.toIntOrNull()?.coerceIn(1, 1000) ?: 400
        }
    }

    // ---- shorthand expansion --------------------------------------------------

    /**
     * Expands [property] into longhands. A longhand comes back as itself; an
     * unknown property or invalid value gives null.
     */
    fun expand(property: String, value: String): List<Pair<String, String>>? {
        val v = value.trim()
        if (isCustom(property)) return listOf(property to v)
        val lower = v.lowercase()
        if (lower == "inherit" || lower == "initial" || lower == "unset") {
            return longhandsOf(property)?.map { it to lower }
        }
        defs[property]?.let { def -> return if (def.valid(v)) listOf(property to v) else null }
        return when (property) {
            "margin" -> boxSides("margin-", v, "")
            "padding" -> boxSides("padding-", v, "")
            "inset" -> boxSides("", v, "")
            "border-width" -> boxSides("border-", v, "-width")
            "border-style" -> boxSides("border-", v, "-style")
            "border-color" -> boxSides("border-", v, "-color")
            "border" -> border(v)?.let { (w, s, c) -> SIDES.flatMap { side -> listOf("border-$side-width" to w, "border-$side-style" to s, "border-$side-color" to c) } }
            "border-top", "border-right", "border-bottom", "border-left" -> {
                val side = property.removePrefix("border-")
                border(v)?.let { (w, s, c) -> listOf("border-$side-width" to w, "border-$side-style" to s, "border-$side-color" to c) }
            }
            "border-radius" -> {
                val parts = CssValues.splitSpaces(v.substringBefore('/'))
                val corners = fourValues(parts) ?: return null
                CORNERS.zip(corners).map { (corner, r) -> "border-$corner-radius" to r }.validated()
            }
            "background" -> background(v)
            "flex" -> flex(v)
            "flex-flow" -> {
                val result = mutableListOf<Pair<String, String>>()
                for (t in CssValues.splitSpaces(v)) {
                    result += when {
                        defs.getValue("flex-direction").valid(t) -> "flex-direction" to t
                        defs.getValue("flex-wrap").valid(t) -> "flex-wrap" to t
                        else -> return null
                    }
                }
                result
            }
            "gap", "grid-gap" -> {
                val parts = CssValues.splitSpaces(v)
                if (parts.size !in 1..2) return null
                listOf("row-gap" to parts[0], "column-gap" to parts.getOrElse(1) { parts[0] }).validated()
            }
            "place-items" -> {
                val parts = CssValues.splitSpaces(v)
                if (parts.size !in 1..2) return null
                listOf("align-items" to parts[0], "justify-items" to parts.getOrElse(1) { parts[0] }).validated()
            }
            "place-self" -> {
                val parts = CssValues.splitSpaces(v)
                if (parts.size !in 1..2) return null
                listOf("align-self" to parts[0], "justify-self" to parts.getOrElse(1) { parts[0] }).validated()
            }
            "text-decoration" -> {
                val lines = CssValues.splitSpaces(lower).filter { it in setOf("none", "underline", "line-through") }
                listOf("text-decoration-line" to (lines.joinToString(" ").ifEmpty { "none" }))
            }
            "-webkit-line-clamp" -> listOf("line-clamp" to v).validated()
            else -> null
        }
    }

    private fun List<Pair<String, String>>.validated(): List<Pair<String, String>>? =
        takeIf { all { (p, value) -> defs[p]?.valid?.invoke(value) == true } }

    /** Longhands a property sets, for `inherit`/`initial` on shorthands. */
    private fun longhandsOf(property: String): List<String>? = when {
        property in defs -> listOf(property)
        property == "margin" -> SIDES.map { "margin-$it" }
        property == "padding" -> SIDES.map { "padding-$it" }
        property == "inset" -> SIDES
        property == "border" -> SIDES.flatMap { listOf("border-$it-width", "border-$it-style", "border-$it-color") }
        property.startsWith("border-") && property.removePrefix("border-") in SIDES -> {
            val side = property.removePrefix("border-")
            listOf("border-$side-width", "border-$side-style", "border-$side-color")
        }
        property == "border-radius" -> CORNERS.map { "border-$it-radius" }
        property == "background" -> listOf("background-color", "background-image", "background-size", "background-position", "background-repeat")
        property == "flex" -> listOf("flex-grow", "flex-shrink", "flex-basis")
        property == "gap" -> listOf("row-gap", "column-gap")
        else -> null
    }

    private fun fourValues(parts: List<String>): List<String>? = when (parts.size) {
        1 -> listOf(parts[0], parts[0], parts[0], parts[0])
        2 -> listOf(parts[0], parts[1], parts[0], parts[1])
        3 -> listOf(parts[0], parts[1], parts[2], parts[1])
        4 -> parts
        else -> null
    }

    private fun boxSides(prefix: String, value: String, suffix: String): List<Pair<String, String>>? {
        val values = fourValues(CssValues.splitSpaces(value)) ?: return null
        return SIDES.zip(values).map { (side, v) -> "$prefix$side$suffix" to v }.validated()
    }

    /** `border: <width> || <style> || <color>` in any order → (width, style, color). */
    private fun border(value: String): Triple<String, String, String>? {
        if (value.trim().lowercase() == "none") return Triple("0", "none", "currentcolor")
        var width = "medium"
        var style = "none"
        var color = "currentcolor"
        for (t in CssValues.splitSpaces(value)) {
            val lower = t.lowercase()
            when {
                lower in setOf("none", "solid", "dashed", "dotted", "hidden") -> style = lower
                lower in setOf("thin", "medium", "thick") || CssValues.px(t) != null -> width = t
                CssValues.color(t) != null || lower == "currentcolor" -> color = t
                else -> return null
            }
        }
        return Triple(width, style, color)
    }

    private fun background(value: String): List<Pair<String, String>>? {
        val v = value.trim()
        if (v.lowercase() == "none") return listOf("background-color" to "transparent", "background-image" to "none")
        // `<position> / <size>`: the size is the 1–2 tokens right after a top-level '/';
        // anything after those (repeat, color, ...) still belongs to the main list.
        var main = v
        var size: String? = null
        val slash = topLevelSlash(v)
        if (slash >= 0) {
            val after = CssValues.splitSpaces(v.substring(slash + 1))
            val sizeTokens = after.takeWhile { t -> t.lowercase() in setOf("cover", "contain", "auto") || CssValues.length(t) != null }.take(2)
            if (sizeTokens.isEmpty()) return null
            size = sizeTokens.joinToString(" ")
            main = (v.substring(0, slash) + " " + after.drop(sizeTokens.size).joinToString(" ")).trim()
        }
        var color = "transparent"
        var image = "none"
        var repeat = "repeat"
        val positionParts = mutableListOf<String>()
        val tokens = CssValues.splitSpaces(main)
        for (t in tokens) {
            val lower = t.lowercase()
            when {
                CssValues.url(t) != null || CssValues.linearGradient(t) != null -> image = t
                lower in setOf("repeat", "no-repeat", "repeat-x", "repeat-y") -> repeat = lower
                lower in setOf("left", "right", "top", "bottom", "center") || lower.endsWith("%") -> positionParts += lower
                CssValues.color(t) != null -> color = t
                else -> return null
            }
        }
        val result = mutableListOf("background-color" to color, "background-image" to image, "background-repeat" to repeat)
        result += "background-position" to (if (positionParts.isEmpty()) "0% 0%" else positionParts.joinToString(" "))
        result += "background-size" to (size ?: "auto")
        return result.validated()
    }

    private fun topLevelSlash(value: String): Int {
        var depth = 0
        for ((i, c) in value.withIndex()) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
                '/' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun flex(value: String): List<Pair<String, String>>? {
        val lower = value.trim().lowercase()
        when (lower) {
            "none" -> return listOf("flex-grow" to "0", "flex-shrink" to "0", "flex-basis" to "auto")
            "auto" -> return listOf("flex-grow" to "1", "flex-shrink" to "1", "flex-basis" to "auto")
        }
        val parts = CssValues.splitSpaces(lower)
        val numbers = mutableListOf<String>()
        var basis: String? = null
        for (p in parts) {
            when {
                CssValues.number(p) != null && basis == null -> numbers += p
                basis == null && (p == "content" || CssValues.length(p) != null) -> basis = p
                else -> return null
            }
        }
        if (numbers.size > 2 || (numbers.isEmpty() && basis == null)) return null
        return listOf(
            "flex-grow" to (numbers.getOrNull(0) ?: "1"),
            "flex-shrink" to (numbers.getOrNull(1) ?: "1"),
            "flex-basis" to (basis ?: "0%"),
        ).validated()
    }
}

/** Grid track lists and `grid-column`/`grid-row` placement. */
object GridTracks {

    sealed class Track {
        data class Fixed(val length: Length) : Track()
        data class Fr(val fr: Float) : Track()
        object Auto : Track() {
            override fun toString() = "Auto"
        }

        /** `minmax(min, max)`: [min] is [Fixed] or [Auto]; [max] is [Fixed], [Fr] or [Auto]. */
        data class MinMax(val min: Track, val max: Track) : Track()

        /**
         * `repeat(auto-fill | auto-fit, tracks)`: as many repetitions as fit the container,
         * worked out in layout. `auto-fit` ([fit]) also drops the repetitions no item uses,
         * so the used ones share the space.
         */
        data class AutoRepeat(val tracks: List<Track>, val fit: Boolean) : Track()
    }

    /** `span n`, `n`, `n / span m`, `n / m` → (start line or null for auto, span). */
    data class Placement(val start: Int?, val span: Int)

    /**
     * `none` gives an empty list. Supports px/%/fr/auto (and calc()), `repeat(n, ...)`,
     * `repeat(auto-fill | auto-fit, ...)` (once per list, with fixed-size tracks or
     * `minmax(<fixed>, ...)`, so a repetition has a known size) and `minmax(min, max)`.
     */
    fun parse(value: String): List<Track>? {
        val v = value.trim().lowercase()
        if (v == "none") return emptyList()
        return parseList(v, allowAutoRepeat = true)?.takeIf { list ->
            list.isNotEmpty() && list.count { it is Track.AutoRepeat } <= 1
        }
    }

    private fun parseList(v: String, allowAutoRepeat: Boolean): List<Track>? {
        val tracks = mutableListOf<Track>()
        for (token in CssValues.splitSpaces(v)) {
            val fn = CssValues.function(token)
            when {
                fn?.first == "repeat" -> {
                    val args = CssValues.splitCommas(fn.second)
                    if (args.size != 2) return null
                    val inner = parseList(args[1], allowAutoRepeat = false) ?: return null
                    if (inner.isEmpty()) return null
                    when (val countText = args[0].trim()) {
                        "auto-fill", "auto-fit" -> {
                            if (!allowAutoRepeat || inner.any { fixedMinimum(it) == null }) return null
                            tracks += Track.AutoRepeat(inner, fit = countText == "auto-fit")
                        }
                        else -> {
                            val count = countText.toIntOrNull()?.takeIf { it in 1..64 } ?: return null
                            repeat(count) { tracks += inner }
                        }
                    }
                }
                fn?.first == "minmax" -> tracks += minmax(fn.second) ?: return null
                else -> tracks += single(token) ?: return null
            }
        }
        return tracks
    }

    private fun minmax(args: String): Track? {
        val parts = CssValues.splitCommas(args)
        if (parts.size != 2) return null
        val min = single(parts[0]) ?: return null
        val max = single(parts[1]) ?: return null
        if (min is Track.Fr) return null // a flexible minimum isn't valid CSS
        return Track.MinMax(min, max)
    }

    /** The definite size a track needs at least (for counting auto-fill repetitions); null if it has none. */
    fun fixedMinimum(track: Track): Length? = when (track) {
        is Track.Fixed -> track.length
        is Track.MinMax -> (track.min as? Track.Fixed)?.length ?: (track.max as? Track.Fixed)?.length
        else -> null
    }

    private fun single(token: String): Track? {
        val t = token.trim().lowercase()
        if (t == "auto" || t == "min-content" || t == "max-content") return Track.Auto
        CssValues.numberWithUnit(t)?.let { (n, unit) -> if (unit == "fr") return if (n >= 0) Track.Fr(n) else null }
        return CssValues.length(t, allowAuto = false)?.let { Track.Fixed(it) }
    }

    fun placement(value: String): Placement? {
        val t = value.trim().lowercase()
        if (t == "auto") return Placement(null, 1)
        val parts = t.split('/').map { it.trim() }
        fun span(p: String): Int? = if (p.startsWith("span")) p.removePrefix("span").trim().toIntOrNull()?.takeIf { it >= 1 } else null
        return when (parts.size) {
            1 -> span(parts[0])?.let { Placement(null, it) } ?: parts[0].toIntOrNull()?.takeIf { it >= 1 }?.let { Placement(it, 1) }
            2 -> {
                val start = if (parts[0] == "auto") null else parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val spanValue = span(parts[1])
                    ?: parts[1].toIntOrNull()?.let { end -> if (start != null && end > start) end - start else null }
                    ?: return null
                Placement(start, spanValue)
            }
            else -> null
        }
    }
}
