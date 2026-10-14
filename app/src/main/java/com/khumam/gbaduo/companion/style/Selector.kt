package com.khumam.gbaduo.companion.style

/**
 * One element in the style tree: a widget (`text`, `box`, ...) or a part the
 * renderer generates for one (a bar's `.fill`, a tab's label). Pure data; the
 * renderer keeps it in sync with its views.
 */
class StyleNode(
    val type: String,
    val id: String? = null,
    val staticClasses: Set<String> = emptySet(),
    /** Declarations between the built-in stylesheet and the pack's, e.g. a legacy `box` defaulting to `display: stack`. */
    val defaults: List<Declaration> = emptyList(),
    /** Inline declarations: legacy widget keys, then the widget's `style` object. Beat every rule. */
    val inline: List<Declaration> = emptyList(),
    /** False for helper parts (e.g. a list's empty-state label) that `:nth-child()` should not count. */
    val countsAsSibling: Boolean = true,
) {
    var parent: StyleNode? = null
        private set
    private val _children = mutableListOf<StyleNode>()
    val children: List<StyleNode> get() = _children

    /** Classes switched on by `classIf` bindings. */
    var dynamicClasses: Set<String> = emptySet()
    var states: Set<PseudoState> = emptySet()

    /** Values from `bindStyle` and from the renderer itself (e.g. a bar fill's width). Beat inline styles. */
    var bound: List<Declaration> = emptyList()

    val classes: Set<String> get() = if (dynamicClasses.isEmpty()) staticClasses else staticClasses + dynamicClasses

    fun add(child: StyleNode) {
        child.parent?._children?.remove(child)
        child.parent = this
        _children += child
    }

    fun remove(child: StyleNode) {
        if (_children.remove(child)) child.parent = null
    }

    private val siblings: List<StyleNode> get() = parent?._children?.filter { it.countsAsSibling } ?: listOf(this)

    /** Position among siblings, for `:nth-child()` and friends. */
    val index: Int get() = siblings.indexOf(this).coerceAtLeast(0)
    val siblingCount: Int get() = siblings.size
}

/** (ids, classes + pseudo-classes, types), compared in that order like CSS. */
data class Specificity(val ids: Int, val classes: Int, val types: Int) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int =
        compareValuesBy(this, other, Specificity::ids, Specificity::classes, Specificity::types)
}

class Selector private constructor(
    /** Rightmost compound first; each paired with the combinator linking it to the next one on its left. */
    private val parts: List<Pair<Compound, Combinator?>>,
    val text: String,
) {
    enum class Combinator { DESCENDANT, CHILD }

    sealed class Pseudo {
        object Root : Pseudo()
        object Empty : Pseudo()
        data class NthChild(val a: Int, val b: Int, val fromEnd: Boolean) : Pseudo()
        data class State(val state: PseudoState) : Pseudo()
    }

    data class Compound(val type: String?, val id: String?, val classes: List<String>, val pseudos: List<Pseudo>) {
        fun matches(node: StyleNode, states: Set<PseudoState>): Boolean {
            if (type != null && type != node.type) return false
            if (id != null && id != node.id) return false
            if (classes.isNotEmpty()) {
                val nodeClasses = node.classes
                if (!classes.all { it in nodeClasses }) return false
            }
            return pseudos.all { pseudo ->
                when (pseudo) {
                    Pseudo.Root -> node.parent == null
                    Pseudo.Empty -> node.children.isEmpty()
                    is Pseudo.State -> pseudo.state in states
                    is Pseudo.NthChild -> {
                        val position = if (pseudo.fromEnd) node.siblingCount - node.index else node.index + 1
                        nthMatches(pseudo.a, pseudo.b, position)
                    }
                }
            }
        }
    }

    val specificity: Specificity = parts.fold(Specificity(0, 0, 0)) { acc, (c, _) ->
        Specificity(
            acc.ids + (if (c.id != null) 1 else 0),
            acc.classes + c.classes.size + c.pseudos.size,
            acc.types + (if (c.type != null) 1 else 0),
        )
    }

    /** Uses [StyleNode.states] for the subject and its ancestors alike. */
    fun matches(node: StyleNode): Boolean = matchFrom(0, node)

    private fun matchFrom(partIndex: Int, node: StyleNode): Boolean {
        val (compound, combinator) = parts[partIndex]
        if (!compound.matches(node, node.states)) return false
        if (partIndex == parts.size - 1) return true
        return when (combinator) {
            Combinator.CHILD -> node.parent?.let { matchFrom(partIndex + 1, it) } ?: false
            Combinator.DESCENDANT -> {
                var ancestor = node.parent
                while (ancestor != null) {
                    if (matchFrom(partIndex + 1, ancestor)) return true
                    ancestor = ancestor.parent
                }
                false
            }
            null -> true
        }
    }

    override fun toString(): String = text

    companion object {
        private val IDENT = Regex("-?[_a-zA-Z][_a-zA-Z0-9-]*")

        /** A comma-separated selector list; null if any part is invalid (the whole rule is then dropped, like CSS). */
        fun parseList(text: String): List<Selector>? {
            val items = text.split(',').map { it.trim() }
            if (items.any { it.isEmpty() }) return null
            return items.map { parse(it) ?: return null }
        }

        fun parse(text: String): Selector? {
            val compounds = mutableListOf<Compound>()
            val combinators = mutableListOf<Combinator>()
            var i = 0
            val s = text.trim()
            if (s.isEmpty()) return null
            var pendingCombinator: Combinator? = null
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> {
                        if (pendingCombinator == null && compounds.isNotEmpty()) pendingCombinator = Combinator.DESCENDANT
                        i++
                    }
                    c == '>' -> {
                        if (compounds.isEmpty()) return null
                        pendingCombinator = Combinator.CHILD
                        i++
                    }
                    c == '+' || c == '~' -> return null // sibling combinators aren't supported
                    else -> {
                        if (compounds.isNotEmpty()) {
                            combinators += pendingCombinator ?: return null
                        }
                        pendingCombinator = null
                        val (compound, next) = parseCompound(s, i) ?: return null
                        compounds += compound
                        i = next
                    }
                }
            }
            if (compounds.isEmpty() || (pendingCombinator == Combinator.CHILD)) return null
            // Store right-to-left: parts[k] pairs compound k (from the right) with the combinator to its left.
            val parts = compounds.indices.reversed().map { k -> compounds[k] to combinators.getOrNull(k - 1) }
            return Selector(parts, s)
        }

        private fun parseCompound(s: String, start: Int): Pair<Compound, Int>? {
            var i = start
            var type: String? = null
            var id: String? = null
            val classes = mutableListOf<String>()
            val pseudos = mutableListOf<Pseudo>()

            fun ident(): String? {
                val match = IDENT.find(s, i) ?: return null
                if (match.range.first != i) return null
                i = match.range.last + 1
                return match.value
            }

            if (i < s.length && s[i] == '*') {
                i++
            } else if (i < s.length && (s[i].isLetter() || s[i] == '_')) {
                type = ident()?.lowercase() ?: return null
            }
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '+' && s[i] != '~') {
                when (s[i]) {
                    '#' -> {
                        i++
                        if (id != null) return null
                        id = ident() ?: return null
                    }
                    '.' -> {
                        i++
                        classes += ident() ?: return null
                    }
                    ':' -> {
                        i++
                        if (i < s.length && s[i] == ':') return null // pseudo-elements aren't supported
                        val name = ident()?.lowercase() ?: return null
                        var arg: String? = null
                        if (i < s.length && s[i] == '(') {
                            val close = s.indexOf(')', i)
                            if (close < 0) return null
                            arg = s.substring(i + 1, close).trim()
                            i = close + 1
                        }
                        pseudos += pseudo(name, arg) ?: return null
                    }
                    else -> return null
                }
            }
            if (type == null && id == null && classes.isEmpty() && pseudos.isEmpty() && s.getOrNull(start) != '*') return null
            return Compound(type, id, classes, pseudos) to i
        }

        private fun pseudo(name: String, arg: String?): Pseudo? = when (name) {
            "root" -> Pseudo.Root.takeIf { arg == null }
            "empty" -> Pseudo.Empty.takeIf { arg == null }
            "first-child" -> Pseudo.NthChild(0, 1, false).takeIf { arg == null }
            "last-child" -> Pseudo.NthChild(0, 1, true).takeIf { arg == null }
            "only-child" -> null // would need two conditions; use :first-child:last-child
            "nth-child" -> arg?.let { nth(it) }?.let { (a, b) -> Pseudo.NthChild(a, b, false) }
            "nth-last-child" -> arg?.let { nth(it) }?.let { (a, b) -> Pseudo.NthChild(a, b, true) }
            "pressed" -> Pseudo.State(PseudoState.PRESSED).takeIf { arg == null }
            "active" -> Pseudo.State(PseudoState.ACTIVE).takeIf { arg == null }
            "disabled" -> Pseudo.State(PseudoState.DISABLED).takeIf { arg == null }
            else -> null
        }

        /** `odd`, `even`, `3`, `2n+1`, `-n+3`, `n` → (a, b) for an+b. */
        fun nth(text: String): Pair<Int, Int>? {
            val t = text.replace(" ", "").lowercase()
            return when {
                t == "odd" -> 2 to 1
                t == "even" -> 2 to 0
                'n' !in t -> t.toIntOrNull()?.let { 0 to it }
                else -> {
                    val (aText, bText) = t.split('n', limit = 2)
                    val a = when (aText) {
                        "", "+" -> 1
                        "-" -> -1
                        else -> aText.toIntOrNull() ?: return null
                    }
                    val b = if (bText.isEmpty()) 0 else bText.toIntOrNull() ?: return null
                    a to b
                }
            }
        }

        fun nthMatches(a: Int, b: Int, position: Int): Boolean {
            if (a == 0) return position == b
            val diff = position - b
            return diff % a == 0 && diff / a >= 0
        }
    }
}
