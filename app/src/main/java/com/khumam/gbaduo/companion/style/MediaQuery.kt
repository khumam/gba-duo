package com.khumam.gbaduo.companion.style

/**
 * An `@media` condition, tested against the companion screen's own size (its
 * viewport, in CSS px), not the whole device. Pure, no Android deps.
 *
 * Supported: media types `all` and `screen` (`print` never matches), `not` and
 * `only`, `and`, comma lists (any one matches), and the features `width`,
 * `height`, `min-`/`max-width`, `min-`/`max-height`, `orientation`,
 * `aspect-ratio`, `min-`/`max-aspect-ratio`, plus range syntax like
 * `(width >= 400px)` and `(400px <= width < 600px)`.
 */
class MediaQuery private constructor(private val alternatives: List<Alternative>) {

    private class Alternative(val negated: Boolean, val typeMatches: Boolean, val features: List<(Float, Float) -> Boolean>) {
        fun matches(width: Float, height: Float): Boolean {
            val all = typeMatches && features.all { it(width, height) }
            return if (negated) !all else all
        }
    }

    /** Whether the rules inside apply on a [width] x [height] screen. */
    fun matches(width: Float, height: Float): Boolean = alternatives.any { it.matches(width, height) }

    companion object {
        /** Parses the text after `@media`; null if any part of it isn't understood (the block is then skipped). */
        fun parse(condition: String): MediaQuery? {
            val text = condition.trim()
            if (text.isEmpty()) return MediaQuery(listOf(Alternative(false, true, emptyList())))
            val alternatives = CssValues.splitCommas(text).map { parseAlternative(it) ?: return null }
            return MediaQuery(alternatives)
        }

        private fun parseAlternative(raw: String): Alternative? {
            var rest = raw.trim().lowercase()
            var negated = false
            if (rest.startsWith("not ")) {
                negated = true
                rest = rest.removePrefix("not ").trim()
            } else if (rest.startsWith("only ")) {
                rest = rest.removePrefix("only ").trim()
            }
            var typeMatches = true
            val parts = splitAnd(rest) ?: return null
            val features = mutableListOf<(Float, Float) -> Boolean>()
            for ((index, part) in parts.withIndex()) {
                if (part.startsWith("(")) {
                    if (!part.endsWith(")")) return null
                    features += feature(part.substring(1, part.length - 1).trim()) ?: return null
                } else if (index == 0) {
                    typeMatches = when (part) {
                        "all", "screen" -> true
                        "print", "speech" -> false
                        else -> return null
                    }
                } else {
                    return null
                }
            }
            // `not` needs a media type in CSS; accept it anyway, negating the whole alternative.
            return Alternative(negated, typeMatches, features)
        }

        /** Splits on top-level ` and `; null for `or` (not supported). */
        private fun splitAnd(text: String): List<String>? {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth == 0 && text.startsWith(" and ", i)) {
                    parts += current.toString().trim()
                    current.clear()
                    i += 5
                    continue
                }
                if (depth == 0 && text.startsWith(" or ", i)) return null
                current.append(c)
                i++
            }
            if (current.isNotBlank()) parts += current.toString().trim()
            return parts.takeIf { it.isNotEmpty() && it.none(String::isEmpty) }
        }

        private fun feature(text: String): ((Float, Float) -> Boolean)? {
            // Range syntax: `width >= 400px`, `400px <= width < 600px`.
            if (Regex("[<>=]").containsMatchIn(text)) return range(text)
            val colon = text.indexOf(':')
            if (colon < 0) {
                return when (text) {
                    "width", "height" -> { _, _ -> true }
                    "orientation", "aspect-ratio" -> { _, _ -> true }
                    else -> null
                }
            }
            val name = text.substring(0, colon).trim()
            val value = text.substring(colon + 1).trim()
            return when (name) {
                "orientation" -> when (value) {
                    "portrait" -> { w, h -> h >= w }
                    "landscape" -> { w, h -> w > h }
                    else -> null
                }
                "aspect-ratio", "min-aspect-ratio", "max-aspect-ratio" -> {
                    val ratio = Properties.aspectRatio(value) ?: return null
                    when (name) {
                        "min-aspect-ratio" -> { w, h -> h > 0f && w / h >= ratio - EPS }
                        "max-aspect-ratio" -> { w, h -> h > 0f && w / h <= ratio + EPS }
                        else -> { w, h -> h > 0f && kotlin.math.abs(w / h - ratio) <= EPS }
                    }
                }
                else -> {
                    val length = px(value) ?: return null
                    val axis = axisOf(name.removePrefix("min-").removePrefix("max-")) ?: return null
                    when {
                        name.startsWith("min-") -> { w, h -> axis(w, h) >= length }
                        name.startsWith("max-") -> { w, h -> axis(w, h) <= length }
                        else -> { w, h -> kotlin.math.abs(axis(w, h) - length) < 0.5f }
                    }
                }
            }
        }

        private fun range(text: String): ((Float, Float) -> Boolean)? {
            val tokens = Regex("\\s*(<=|>=|<|>|=)\\s*").let { re ->
                val ops = re.findAll(text).map { it.groupValues[1] }.toList()
                val operands = text.split(re).map { it.trim() }
                if (operands.size != ops.size + 1 || operands.any(String::isEmpty)) return null
                operands to ops
            }
            val (operands, ops) = tokens
            if (ops.size !in 1..2) return null
            // Exactly one operand is the feature name; the rest are lengths (or ratios).
            val featureIndex = operands.indexOfFirst { it == "width" || it == "height" || it == "aspect-ratio" }
            if (featureIndex < 0 || operands.count { it == "width" || it == "height" || it == "aspect-ratio" } != 1) return null
            val featureName = operands[featureIndex]
            val read: (Float, Float) -> Float = when (featureName) {
                "width" -> { w, _ -> w }
                "height" -> { _, h -> h }
                else -> { w, h -> if (h > 0f) w / h else 0f }
            }
            val values = operands.mapIndexed { i, s ->
                if (i == featureIndex) null
                else if (featureName == "aspect-ratio") Properties.aspectRatio(s) ?: return null
                else px(s) ?: return null
            }
            val checks = ops.mapIndexed { i, op ->
                val leftValue = values[i]
                val rightValue = values[i + 1]
                val check: (Float, Float) -> Boolean = { w, h ->
                    val left = leftValue ?: read(w, h)
                    val right = rightValue ?: read(w, h)
                    when (op) {
                        "<" -> left < right
                        "<=" -> left <= right + EPS
                        ">" -> left > right
                        ">=" -> left + EPS >= right
                        else -> kotlin.math.abs(left - right) <= EPS
                    }
                }
                check
            }
            return { w, h -> checks.all { it(w, h) } }
        }

        private fun axisOf(name: String): ((Float, Float) -> Float)? = when (name) {
            "width" -> { w, _ -> w }
            "height" -> { _, h -> h }
            else -> null
        }

        /** Media lengths: px (and unitless 0), em/rem against the default 14px font. No %. */
        private fun px(value: String): Float? =
            (CssValues.length(value, LengthContext(), allowPercent = false, allowAuto = false) as? Length.Px)?.px

        private const val EPS = 0.001f
    }
}
