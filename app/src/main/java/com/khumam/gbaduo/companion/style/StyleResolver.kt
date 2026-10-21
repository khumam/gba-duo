package com.khumam.gbaduo.companion.style

import java.io.File

/**
 * Computes a [StyleNode]'s style, CSS-style. Cascade order, lowest first:
 * 1. built-in stylesheet rules (the app's `default.css`)
 * 2. the node's own [StyleNode.defaults]
 * 3. the pack's stylesheet rules
 * 4. inline declarations (widget keys, then the widget's `style` object)
 * 5. bound declarations (`bindStyle`, renderer-driven values)
 *
 * Within 1 and 3, higher specificity wins, then later source order. Custom
 * properties (`--x`) and the usual text properties inherit. Pure, no Android deps.
 */
class StyleResolver(
    builtIn: List<Stylesheet>,
    pack: List<Stylesheet>,
    var viewportWidth: Float = 0f,
    var viewportHeight: Float = 0f,
) {
    private class IndexedRule(val origin: Int, val order: Int, val selector: Selector, val declarations: List<Declaration>, val media: List<MediaQuery>)

    private val rules: List<IndexedRule> = buildList {
        var order = 0
        for ((origin, sheets) in listOf(ORIGIN_BUILT_IN to builtIn, ORIGIN_PACK to pack)) {
            for (sheet in sheets) {
                for (rule in sheet.rules) {
                    val ruleOrder = order++
                    for (selector in rule.selectors) add(IndexedRule(origin, ruleOrder, selector, rule.declarations, rule.media))
                }
            }
        }
    }

    val fontFaces: List<FontFace> = (builtIn + pack).flatMap { it.fontFaces }

    /** Problems found while resolving (e.g. a `var()` with no value). Deduplicated. */
    val warnings = LinkedHashSet<String>()

    fun resolve(node: StyleNode, parent: ComputedStyle?): ComputedStyle {
        val declarations = cascade(node)

        // Pass 1: custom properties (inherit, and later declarations win).
        val customs = LinkedHashMap(parent?.customProperties ?: emptyMap())
        for (d in declarations) if (Properties.isCustom(d.property)) customs[d.property] = d.value

        // Pass 2: everything else, with var() substituted and shorthands expanded.
        val values = LinkedHashMap<String, String>()
        val baseDirs = HashMap<String, File?>()
        for (d in declarations) {
            if (Properties.isCustom(d.property)) continue
            val substituted = substituteVars(d.value, customs, 0)
            if (substituted == null) {
                warnings += "'${d.property}: ${d.value}' uses a var() with no value; ignored"
                continue
            }
            val longhands = Properties.expand(d.property, substituted)
            if (longhands == null) {
                warnings += "invalid value '$substituted' for '${d.property}' (after var()); ignored"
                continue
            }
            for ((property, value) in longhands) {
                values[property] = value
                baseDirs[property] = d.baseDir
            }
        }

        // Declared on this node (not inherited): only these are resolved relative to the parent.
        val declaredFontSize = values["font-size"]?.let { if (it.trim().lowercase() == "initial") Properties.defs.getValue("font-size").initial else it }
        val declaredFontWeight = values["font-weight"]?.let { if (it.trim().lowercase() == "initial") "normal" else it }

        // Keywords and inheritance.
        for (property in values.keys.toList()) {
            val def = Properties.def(property) ?: continue
            when (values.getValue(property).trim().lowercase()) {
                "initial" -> values[property] = def.initial
                "inherit" -> inherit(property, parent, values, baseDirs, def)
                "unset" -> if (def.inherited) inherit(property, parent, values, baseDirs, def) else values[property] = def.initial
            }
        }
        if (parent != null) {
            for ((property, def) in Properties.defs) {
                if (def.inherited && property !in values && parent.isSet(property)) {
                    values[property] = parent.raw(property)
                    baseDirs[property] = parent.baseDir(property)
                }
            }
        }

        val parentFontSize = parent?.fontSizePx ?: ROOT_FONT_SIZE
        val rootFontSize = parent?.rootFontSizePx
        val fontSize = declaredFontSize?.takeUnless { it.trim().lowercase() in INHERIT_KEYWORDS }
            ?.let { Properties.fontSizePx(it, parentFontSize, rootFontSize ?: parentFontSize, viewportWidth, viewportHeight) }
            ?: parentFontSize
        values["font-size"] = "${fontSize}px"
        val parentWeight = parent?.fontWeight ?: 400
        val fontWeight = declaredFontWeight?.takeUnless { it.trim().lowercase() in INHERIT_KEYWORDS }
            ?.let { Properties.fontWeight(it, parentWeight) }
            ?: parentWeight
        values["font-weight"] = fontWeight.toString()

        // Inherited lengths inherit their computed px value (like CSS), not the em text.
        val emContext = LengthContext(fontSize, rootFontSize ?: fontSize, viewportWidth, viewportHeight)
        for (property in INHERITED_LENGTHS) {
            val v = values[property] ?: continue
            if (CssValues.number(v) != null) continue // unitless line-height stays relative
            val len = CssValues.length(v, emContext, allowAuto = false)
            if (len is Length.Px && v.trim().lowercase() != "normal") values[property] = "${len.px}px"
        }

        return ComputedStyle(
            values = values,
            customProperties = customs,
            baseDirs = baseDirs,
            fontSizePx = fontSize,
            rootFontSizePx = rootFontSize ?: fontSize,
            fontWeight = fontWeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    private fun inherit(property: String, parent: ComputedStyle?, values: MutableMap<String, String>, baseDirs: MutableMap<String, File?>, def: Properties.Def) {
        if (parent != null) {
            values[property] = parent.raw(property)
            baseDirs[property] = parent.baseDir(property)
        } else {
            values[property] = def.initial
        }
    }

    /** All declarations that apply to [node], in cascade order (later wins). */
    private fun cascade(node: StyleNode): List<Declaration> {
        // `@media` rules apply only while the companion screen matches (re-checked when its size changes).
        val matched = rules.filter { rule ->
            rule.media.all { it.matches(viewportWidth, viewportHeight) } && rule.selector.matches(node)
        }
        fun sorted(origin: Int) = matched.filter { it.origin == origin }
            .sortedWith(compareBy<IndexedRule> { it.selector.specificity }.thenBy { it.order })
            .flatMap { it.declarations }
        return sorted(ORIGIN_BUILT_IN) + node.defaults + sorted(ORIGIN_PACK) + node.inline + node.bound
    }

    companion object {
        private const val ORIGIN_BUILT_IN = 0
        private const val ORIGIN_PACK = 1
        const val ROOT_FONT_SIZE = 14f
        private const val MAX_VAR_DEPTH = 16
        private val INHERITED_LENGTHS = listOf("letter-spacing", "line-height")
        private val INHERIT_KEYWORDS = setOf("inherit", "unset")

        /** Replaces every `var(--name[, fallback])`; null if one has no value and no fallback. */
        fun substituteVars(value: String, customs: Map<String, String>, depth: Int): String? {
            if (!value.contains("var(")) return value
            if (depth > MAX_VAR_DEPTH) return null
            val out = StringBuilder()
            var i = 0
            while (i < value.length) {
                val start = value.indexOf("var(", i)
                if (start < 0) {
                    out.append(value, i, value.length)
                    break
                }
                out.append(value, i, start)
                var depthParen = 0
                var end = -1
                for (j in start + 3 until value.length) {
                    when (value[j]) {
                        '(' -> depthParen++
                        ')' -> {
                            depthParen--
                            if (depthParen == 0) {
                                end = j
                                break
                            }
                        }
                    }
                }
                if (end < 0) return null
                val inner = value.substring(start + 4, end)
                val comma = topLevelComma(inner)
                val name = (if (comma >= 0) inner.substring(0, comma) else inner).trim()
                val fallback = if (comma >= 0) inner.substring(comma + 1).trim() else null
                val replacement = customs[name]?.let { substituteVars(it, customs, depth + 1) }
                    ?: fallback?.let { substituteVars(it, customs, depth + 1) }
                    ?: return null
                out.append(replacement)
                i = end + 1
            }
            return out.toString()
        }

        private fun topLevelComma(s: String): Int {
            var depth = 0
            for ((i, c) in s.withIndex()) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) return i
                }
            }
            return -1
        }
    }
}
