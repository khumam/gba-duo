package com.khumam.gbaduo.companion.style

import java.io.File

/**
 * Parses a stylesheet: rules (`selectors { declarations }`), `@font-face`,
 * `@media` blocks (see [MediaQuery]) and comments. Fault-tolerant like a browser: an unsupported selector drops its rule,
 * an unknown property or invalid value drops that declaration, and parsing
 * carries on; each is reported in [Stylesheet.warnings]. Pure, no Android deps.
 *
 * [baseDir] is the stylesheet's folder (for `url()`); [packDir] bounds where
 * `url()` may point, so a pack can't reach files outside itself.
 */
object CssParser {

    fun parse(css: String, baseDir: File? = null, packDir: File? = null, sourceName: String = "stylesheet"): Stylesheet {
        val warnings = mutableListOf<CssWarning>()
        val rules = mutableListOf<Rule>()
        val fontFaces = mutableListOf<FontFace>()
        parseBlock(stripComments(css), emptyList(), baseDir, packDir, sourceName, rules, fontFaces, warnings)
        return Stylesheet(rules, fontFaces, warnings)
    }

    /** Rules (and `@font-face`, nested `@media`) in [text]; [media] are the enclosing `@media` conditions. */
    private fun parseBlock(
        text: String,
        media: List<MediaQuery>,
        baseDir: File?,
        packDir: File?,
        sourceName: String,
        rules: MutableList<Rule>,
        fontFaces: MutableList<FontFace>,
        warnings: MutableList<CssWarning>,
    ) {
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('{', i)
            if (open < 0) {
                if (text.substring(i).isNotBlank()) warnings += CssWarning("$sourceName: unexpected '${text.substring(i).trim().take(40)}'")
                break
            }
            val close = matchingBrace(text, open)
            if (close < 0) {
                warnings += CssWarning("$sourceName: missing '}' after '${text.substring(i, open).trim().take(40)}'")
                break
            }
            val prelude = text.substring(i, open).trim()
            val body = text.substring(open + 1, close)
            i = close + 1
            when {
                prelude.startsWith("@font-face", ignoreCase = true) -> {
                    parseFontFace(body, baseDir, packDir, sourceName, warnings)?.let { fontFaces += it }
                }
                prelude.startsWith("@media", ignoreCase = true) -> {
                    val query = MediaQuery.parse(prelude.substring(6))
                    if (query == null) {
                        warnings += CssWarning("$sourceName: unsupported media query '${prelude.take(60)}', block skipped")
                    } else {
                        parseBlock(body, media + query, baseDir, packDir, sourceName, rules, fontFaces, warnings)
                    }
                }
                prelude.startsWith("@") -> warnings += CssWarning("$sourceName: unsupported at-rule '${prelude.take(40)}' skipped")
                else -> {
                    val selectors = Selector.parseList(prelude)
                    if (selectors == null) {
                        warnings += CssWarning("$sourceName: unsupported selector '${prelude.take(60)}', rule skipped")
                        continue
                    }
                    val declarations = parseDeclarations(body, baseDir, sourceName, warnings)
                    if (declarations.isNotEmpty()) rules += Rule(selectors, declarations, media)
                }
            }
        }
    }

    /** Declarations of a rule body or a `style` object string (`a: 1; b: 2`). */
    fun parseDeclarations(body: String, baseDir: File?, sourceName: String = "style", warnings: MutableList<CssWarning> = mutableListOf()): List<Declaration> {
        val result = mutableListOf<Declaration>()
        for (raw in splitDeclarations(body)) {
            val colon = raw.indexOf(':')
            if (colon <= 0) {
                if (raw.isNotBlank()) warnings += CssWarning("$sourceName: ignored '${raw.trim().take(40)}'")
                continue
            }
            val property = raw.substring(0, colon).trim().let { if (Properties.isCustom(it)) it else it.lowercase() }
            val value = raw.substring(colon + 1).trim().removeSuffix("!important").trim()
            declaration(property, value, baseDir, sourceName, warnings)?.let { result += it }
        }
        return result
    }

    /** Validates one declaration (unless it uses `var()`, which can only be checked once resolved). */
    fun declaration(property: String, value: String, baseDir: File?, sourceName: String, warnings: MutableList<CssWarning>): Declaration? {
        if (value.isEmpty()) {
            warnings += CssWarning("$sourceName: empty value for '$property'")
            return null
        }
        if (!Properties.isKnown(property)) {
            warnings += CssWarning("$sourceName: unsupported property '$property'")
            return null
        }
        if (!value.contains("var(") && Properties.expand(property, value) == null) {
            warnings += CssWarning("$sourceName: invalid value '$value' for '$property'")
            return null
        }
        return Declaration(property, value, baseDir)
    }

    private fun parseFontFace(body: String, baseDir: File?, packDir: File?, sourceName: String, warnings: MutableList<CssWarning>): FontFace? {
        var family: String? = null
        var src: String? = null
        var weight = 400
        var italic = false
        for (raw in splitDeclarations(body)) {
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val property = raw.substring(0, colon).trim().lowercase()
            val value = raw.substring(colon + 1).trim()
            when (property) {
                "font-family" -> family = CssValues.unquote(value)
                "src" -> src = CssValues.splitCommas(value).firstNotNullOfOrNull { part -> CssValues.splitSpaces(part).firstNotNullOfOrNull { CssValues.url(it) } }
                "font-weight" -> weight = Properties.fontWeight(value, 400)
                "font-style" -> italic = value.trim().lowercase() != "normal"
            }
        }
        if (family.isNullOrBlank() || src == null) {
            warnings += CssWarning("$sourceName: @font-face needs font-family and src: url(...)")
            return null
        }
        val file = resolveUrl(src, baseDir, packDir)
        if (file == null) {
            warnings += CssWarning("$sourceName: @font-face src '$src' is outside the pack")
            return null
        }
        return FontFace(family, file, weight, italic)
    }

    /**
     * Resolves a `url()` path against [baseDir]; null if it would leave [packDir]
     * (or is absolute / a URL scheme, which packs can't use).
     */
    fun resolveUrl(path: String, baseDir: File?, packDir: File?): File? {
        if (path.contains("://") || path.startsWith("/") || path.startsWith("data:")) return null
        val base = baseDir ?: packDir ?: return null
        val file = File(base, path)
        val root = packDir ?: return file
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        return canonical.takeIf { it.path == rootPath || it.path.startsWith(rootPath + File.separator) }
    }

    private fun stripComments(css: String): String {
        val out = StringBuilder(css.length)
        var i = 0
        var quote: Char? = null
        while (i < css.length) {
            val c = css[i]
            if (quote != null) {
                out.append(c)
                if (c == quote) quote = null
                i++
            } else if (c == '"' || c == '\'') {
                quote = c
                out.append(c)
                i++
            } else if (c == '/' && css.getOrNull(i + 1) == '*') {
                val end = css.indexOf("*/", i + 2)
                i = if (end < 0) css.length else end + 2
                out.append(' ')
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var quote: Char? = null
        for (i in open until text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** Splits on `;` outside parentheses and quotes (so `url(a;b)` survives). */
    private fun splitDeclarations(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in body) {
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
                c == ';' && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }
}
