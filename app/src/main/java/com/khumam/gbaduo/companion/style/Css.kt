package com.khumam.gbaduo.companion.style

import java.io.File

/**
 * One `property: value` pair. [baseDir] is where `url(...)` in [value] resolves
 * from: the stylesheet's folder for rules, the pack folder for inline styles.
 */
data class Declaration(val property: String, val value: String, val baseDir: File? = null)

/** [media]: the `@media` condition(s) the rule sits in (all must match), empty when none. */
data class Rule(val selectors: List<Selector>, val declarations: List<Declaration>, val media: List<MediaQuery> = emptyList())

/** `@font-face { font-family; src: url(); font-weight; font-style }`, with [file] already resolved. */
data class FontFace(val family: String, val file: File, val weight: Int, val italic: Boolean)

/** Problems found while parsing or resolving; skipped, never fatal (like browsers). */
data class CssWarning(val message: String)

data class Stylesheet(
    val rules: List<Rule>,
    val fontFaces: List<FontFace> = emptyList(),
    val warnings: List<CssWarning> = emptyList(),
) {
    companion object {
        val EMPTY = Stylesheet(emptyList())
    }
}

/** Interaction states a selector can test with `:pressed`, `:active` and `:disabled`. */
enum class PseudoState { PRESSED, ACTIVE, DISABLED }
