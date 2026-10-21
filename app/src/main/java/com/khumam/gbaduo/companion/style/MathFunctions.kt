package com.khumam.gbaduo.companion.style

/**
 * `calc()`, `min()`, `max()` and `clamp()` for lengths. Pure, no Android deps.
 *
 * Every relative unit (em, rem, vw, vh, vmin, vmax) resolves to px when the style
 * is computed, so a value only stays symbolic when it mixes in `%`:
 * `calc(100% - 20px)` becomes `Length.Calc(-20, 100)`, resolved in layout once the
 * containing box is known. `min()`, `max()` and `clamp()` need to compare values
 * right away, so they take `%` only when every argument is a plain percentage
 * (e.g. `min(50%, 80%)`); mixing `%` with px inside them is refused, like an
 * invalid value (the declaration is dropped).
 */
object MathFunctions {

    private val NAMES = setOf("calc", "min", "max", "clamp")

    fun isMath(token: String): Boolean {
        val fn = CssValues.function(token.trim()) ?: return false
        return fn.first in NAMES
    }

    /** A linear value: [px] + [percent]%, or a plain number when [number]. */
    private data class Lin(val px: Float, val percent: Float, val number: Boolean) {
        val isPx get() = !number && percent == 0f
        val isPercent get() = !number && px == 0f && percent != 0f
    }

    fun length(token: String, context: LengthContext, allowPercent: Boolean): Length? {
        val value = runCatching { Parser(token.trim(), context).parseWhole() }.getOrNull() ?: return null
        if (!value.px.isFinite() || !value.percent.isFinite()) return null
        return when {
            value.number -> Length.Px(value.px) // a unitless result is px, like a unitless length
            value.percent == 0f -> Length.Px(value.px)
            !allowPercent -> null
            value.px == 0f -> Length.Percent(value.percent)
            else -> Length.Calc(value.px, value.percent)
        }
    }

    private class Parser(private val text: String, private val context: LengthContext) {
        private var pos = 0

        fun parseWhole(): Lin? {
            val v = function() ?: return null
            skipSpaces()
            return if (pos == text.length) v else null
        }

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        /** `name( ... )` for one of the math functions. */
        private fun function(): Lin? {
            skipSpaces()
            val start = pos
            while (pos < text.length && text[pos].isLetter()) pos++
            val name = text.substring(start, pos).lowercase()
            if (name !in NAMES || pos >= text.length || text[pos] != '(') return null
            pos++
            val args = mutableListOf<Lin>()
            while (true) {
                args += sum() ?: return null
                skipSpaces()
                if (pos >= text.length) return null
                when (text[pos]) {
                    ',' -> pos++
                    ')' -> {
                        pos++
                        break
                    }
                    else -> return null
                }
            }
            return when (name) {
                "calc" -> args.singleOrNull()
                "min" -> pick(args) { a, b -> a < b }
                "max" -> pick(args) { a, b -> a > b }
                else -> {
                    if (args.size != 3) return null
                    val lowered = pick(listOf(args[1], args[2])) { a, b -> a < b } ?: return null
                    pick(listOf(args[0], lowered)) { a, b -> a > b }
                }
            }
        }

        /** min()/max() choice: all arguments must be of one kind (all px, all %, or all numbers). */
        private fun pick(args: List<Lin>, better: (Float, Float) -> Boolean): Lin? {
            if (args.isEmpty()) return null
            val first = args[0]
            val key: (Lin) -> Float = when {
                args.all { it.number } -> { l -> l.px }
                args.all { it.isPx } -> { l -> l.px }
                args.all { it.isPercent } -> { l -> l.percent }
                else -> return null
            }
            var best = first
            for (a in args.drop(1)) if (better(key(a), key(best))) best = a
            return best
        }

        /** `a + b - c`; CSS needs spaces around + and -. */
        private fun sum(): Lin? {
            var left = product() ?: return null
            while (true) {
                val save = pos
                skipSpaces()
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-') && pos > save) {
                    val op = text[pos]
                    pos++
                    if (pos >= text.length || !text[pos].isWhitespace()) return null
                    val right = product() ?: return null
                    if (left.number != right.number) return null
                    left = if (op == '+') Lin(left.px + right.px, left.percent + right.percent, left.number)
                    else Lin(left.px - right.px, left.percent - right.percent, left.number)
                } else {
                    pos = save
                    return left
                }
            }
        }

        /** `a * b / c`: one side of `*` must be a number; the right of `/` must be a non-zero number. */
        private fun product(): Lin? {
            var left = term() ?: return null
            while (true) {
                val save = pos
                skipSpaces()
                if (pos < text.length && (text[pos] == '*' || text[pos] == '/')) {
                    val op = text[pos]
                    pos++
                    val right = term() ?: return null
                    left = if (op == '*') {
                        when {
                            right.number -> Lin(left.px * right.px, left.percent * right.px, left.number)
                            left.number -> Lin(right.px * left.px, right.percent * left.px, false)
                            else -> return null
                        }
                    } else {
                        if (!right.number || right.px == 0f) return null
                        Lin(left.px / right.px, left.percent / right.px, left.number)
                    }
                } else {
                    pos = save
                    return left
                }
            }
        }

        private fun term(): Lin? {
            skipSpaces()
            if (pos >= text.length) return null
            val c = text[pos]
            if (c == '(') {
                pos++
                val v = sum() ?: return null
                skipSpaces()
                if (pos >= text.length || text[pos] != ')') return null
                pos++
                return v
            }
            if (c.isLetter()) return function()
            // A number with an optional unit.
            val start = pos
            if (c == '+' || c == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E') && pos + 1 < text.length &&
                (text[pos + 1].isDigit() || ((text[pos + 1] == '+' || text[pos + 1] == '-') && pos + 2 < text.length && text[pos + 2].isDigit()))
            ) {
                pos += 2
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            while (pos < text.length && (text[pos].isLetter() || text[pos] == '%')) pos++
            val token = text.substring(start, pos)
            val (n, unit) = CssValues.numberWithUnit(token) ?: return null
            if (unit.isEmpty()) return Lin(n, 0f, true)
            if (unit == "%") return Lin(0f, n, false)
            val len = CssValues.length(token, context, allowPercent = false, allowAuto = false) as? Length.Px ?: return null
            return Lin(len.px, 0f, false)
        }
    }
}
