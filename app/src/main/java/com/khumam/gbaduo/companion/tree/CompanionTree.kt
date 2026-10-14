package com.khumam.gbaduo.companion.tree

import androidx.compose.ui.graphics.ImageBitmap
import com.khumam.gbaduo.companion.Binding
import com.khumam.gbaduo.companion.ScopedValues
import com.khumam.gbaduo.companion.Widget
import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.CssWarning
import com.khumam.gbaduo.companion.style.Declaration
import com.khumam.gbaduo.companion.style.PseudoState
import com.khumam.gbaduo.companion.style.StyleNode
import com.khumam.gbaduo.companion.style.StyleResolver
import com.khumam.gbaduo.companion.style.Stylesheet
import java.util.Locale

/**
 * The companion screen's model: the pack's [Widget] tree turned into
 * [CompanionNode]s, styled with CSS (the app's built-in stylesheet, then the
 * pack's, then each widget's inline styles), and kept up to date from game
 * snapshots. Each [update] re-runs bindings and re-resolves only the nodes whose
 * classes, states or bound styles changed; the Compose UI just reads the result.
 *
 * Platform-neutral: images come from [loadImage] (an `asset:` path or a `gfx:`
 * sprite key; null if not available yet). Main thread only.
 */
class CompanionTree(
    rootWidget: Widget,
    builtIn: List<Stylesheet> = emptyList(),
    pack: List<Stylesheet> = emptyList(),
    private val loadImage: (String) -> ImageBitmap? = { null },
) {
    private val resolver = StyleResolver(builtIn, pack)
    private val dirty = LinkedHashSet<CompanionNode>()

    val root: CompanionNode = inflate(rootWidget, parent = null)

    /** Problems found while resolving styles (e.g. a `var()` with no value). */
    val warnings: Collection<String> get() = resolver.warnings

    val fontFaces get() = resolver.fontFaces

    init {
        markDirty(root)
        flush()
    }

    /**
     * Applies a snapshot: a `Map<String, Any?>` at the top level, or a
     * [ScopedValues] for a list item's template.
     */
    fun update(context: Any?) {
        updateNode(root, context)
        flush()
    }

    /** The screen's size in CSS px, for `vw`/`vh`. */
    fun setViewport(width: Float, height: Float) {
        if (width == resolver.viewportWidth && height == resolver.viewportHeight) return
        resolver.viewportWidth = width
        resolver.viewportHeight = height
        markDirty(root)
        flush()
    }

    /** For `:pressed`. */
    fun setPressed(node: CompanionNode, pressed: Boolean) {
        val states = if (pressed) node.style.states + PseudoState.PRESSED else node.style.states - PseudoState.PRESSED
        if (states == node.style.states) return
        node.style.states = states
        markDirty(node)
        flush()
    }

    // ---- building ---------------------------------------------------------------

    private fun typeName(widget: Widget): String = when (widget) {
        is Widget.Column -> "column"
        is Widget.Row -> "row"
        is Widget.Tabs -> "tabs"
        is Widget.Text -> "text"
        is Widget.Bar -> "bar"
        is Widget.Image -> "image"
        is Widget.Spacer -> "spacer"
        is Widget.ItemList -> "list"
        is Widget.Box -> "box"
    }

    private fun attach(node: CompanionNode, parent: CompanionNode?) {
        node.parent = parent
        parent?.let {
            it.children += node
            it.style.add(node.style)
        }
    }

    /** A generated part (bar fill, tab, ...): named by type and by a class of the same name. */
    private fun part(parent: CompanionNode, type: String, kind: CompanionNode.Kind, countsAsSibling: Boolean = true): CompanionNode {
        val node = CompanionNode(StyleNode(type, staticClasses = setOf(type), countsAsSibling = countsAsSibling), kind, null)
        attach(node, parent)
        return node
    }

    private fun inflate(widget: Widget, parent: CompanionNode?): CompanionNode {
        val meta = widget.meta
        val defaults = if (widget is Widget.Box && widget.legacyChild) LEGACY_BOX_DEFAULTS else emptyList()
        val node = CompanionNode(StyleNode(typeName(widget), meta.id, meta.classes, defaults, meta.inline), CompanionNode.Kind.WIDGET, widget)
        attach(node, parent)
        bindCommon(node, widget)

        when (widget) {
            is Widget.Column -> widget.children.forEach { inflate(it, node) }
            is Widget.Row -> widget.children.forEach { inflate(it, node) }
            is Widget.Box -> widget.children.forEach { inflate(it, node) }
            is Widget.Text -> {
                // Placeholders render empty until the first snapshot instead of showing "{hp}".
                node.text = Binding.interpolate(widget.text, null)
                node.bindings += { ctx -> node.text = Binding.interpolate(widget.text, ctx) }
            }
            is Widget.Image -> bindImage(node, widget.src)
            is Widget.Spacer -> Unit
            is Widget.Bar -> buildBar(node, widget)
            is Widget.ItemList -> buildList(node, widget)
            is Widget.Tabs -> buildTabs(node, widget)
        }
        return node
    }

    private fun bindCommon(node: CompanionNode, widget: Widget) {
        widget.visibleIf?.let { path ->
            node.bindings += { ctx -> node.hiddenByBinding = !Binding.isTruthy(Binding.resolve(path, ctx)) }
        }
        val meta = widget.meta
        if (meta.classIf.isNotEmpty()) {
            node.bindings += { ctx ->
                val classes = meta.classIf.filter { (_, path) -> Binding.isTruthy(Binding.resolve(path, ctx)) }.keys
                if (classes != node.style.dynamicClasses) {
                    node.style.dynamicClasses = classes
                    markDirty(node)
                }
            }
        }
        if (meta.bindStyle.isNotEmpty()) {
            node.bindings += { ctx ->
                val ignored = mutableListOf<CssWarning>()
                val declarations = meta.bindStyle.mapNotNull { (property, template) ->
                    CssParser.declaration(property, Binding.interpolate(template, ctx), null, "bindStyle", ignored)
                }
                setBound(node, declarations)
            }
        }
    }

    private fun setBound(node: CompanionNode, declarations: List<Declaration>) {
        if (declarations != node.style.bound) {
            node.style.bound = declarations
            markDirty(node)
        }
    }

    // ---- images -----------------------------------------------------------------

    private fun bindImage(node: CompanionNode, src: String) {
        when {
            src.startsWith("asset:") -> showImage(node, src)
            // A literal sprite key only exists once the pack's script has decoded it, so keep trying until it does.
            src.startsWith("gfx:") -> node.bindings += { _ -> showImage(node, src) }
            src.startsWith("$") -> {
                val path = src.removePrefix("$")
                node.bindings += { ctx -> showImage(node, Binding.resolve(path, ctx) as? String) }
            }
        }
    }

    private fun showImage(node: CompanionNode, key: String?) {
        if (key == node.imageKey && (node.image != null || key == null)) return
        node.imageKey = key
        node.image = key?.let(loadImage)
    }

    // ---- bar ------------------------------------------------------------------------

    private fun buildBar(node: CompanionNode, widget: Widget.Bar) {
        val fill = part(node, "fill", CompanionNode.Kind.FILL)
        val label = widget.label?.let { template ->
            part(node, "label", CompanionNode.Kind.LABEL).also { it.text = Binding.interpolate(template, null) }
        }
        setBound(fill, listOf(Declaration("width", "0%")))
        node.bindings += { ctx ->
            val value = Binding.asDouble(Binding.resolve(widget.valueKey, ctx))
            val max = widget.maxKey?.let { Binding.asDouble(Binding.resolve(it, ctx)) } ?: widget.max
            val fraction = if (max > 0.0) (value / max).coerceIn(0.0, 1.0) else 0.0
            setBound(fill, listOf(Declaration("width", String.format(Locale.ROOT, "%.3f%%", fraction * 100))))
            if (label != null && widget.label != null) label.text = Binding.interpolate(widget.label, ctx)
        }
    }

    // ---- list -----------------------------------------------------------------------

    /**
     * Data-driven repeat: rebuilds item nodes only when the resolved list's length
     * changes; otherwise just re-binds each existing item with its new values.
     */
    private fun buildList(node: CompanionNode, widget: Widget.ItemList) {
        node.managesChildren = true
        val empty = part(node, "empty", CompanionNode.Kind.EMPTY, countsAsSibling = false)
        empty.text = widget.emptyText.orEmpty()
        empty.hiddenByBinding = true // the binding below decides on the first update
        val items = mutableListOf<CompanionNode>()
        node.bindings += { ctx ->
            // Items resolve against the current list item first, then the snapshot root,
            // so a nested list can iterate its parent item's own list (e.g. `$moves`).
            val snapshotRoot = if (ctx is ScopedValues) ctx.root else ctx
            val values = Binding.asList(Binding.resolve(widget.itemsPath, ctx))
            if (values.size != items.size) {
                for (item in items) remove(item)
                items.clear()
                repeat(values.size) { items += inflate(widget.template, node) }
                markDirty(node)
            }
            empty.hiddenByBinding = !(values.isEmpty() && widget.emptyText != null)
            items.forEachIndexed { index, item -> updateNode(item, ScopedValues(item = values.getOrNull(index), index = index, root = snapshotRoot)) }
        }
    }

    private fun remove(node: CompanionNode) {
        val parent = node.parent ?: return
        fun forget(n: CompanionNode) {
            dirty.remove(n)
            n.children.forEach { forget(it) }
        }
        forget(node)
        parent.children.remove(node)
        parent.style.remove(node.style)
        node.parent = null
    }

    // ---- tabs -----------------------------------------------------------------------

    private fun buildTabs(node: CompanionNode, widget: Widget.Tabs) {
        val bar = part(node, "tab-bar", CompanionNode.Kind.TAB_BAR)
        val content = part(node, "tab-content", CompanionNode.Kind.TAB_CONTENT)
        val labels = widget.tabs.map { tab -> part(bar, "tab", CompanionNode.Kind.TAB).also { it.text = tab.title } }
        val panels = widget.tabs.map { inflate(it.child, content) }
        fun select(index: Int) {
            labels.forEachIndexed { i, label ->
                label.style.states = if (i == index) label.style.states + PseudoState.ACTIVE else label.style.states - PseudoState.ACTIVE
            }
            panels.forEachIndexed { i, panel -> panel.hiddenByTabs = i != index }
            markDirty(node)
        }
        labels.forEachIndexed { i, label ->
            label.onClick = {
                select(i)
                flush()
            }
        }
        select(0)
    }

    // ---- updating and styling -------------------------------------------------

    private fun updateNode(node: CompanionNode, context: Any?) {
        for (binding in node.bindings) binding(context)
        if (node.managesChildren) return
        for (child in node.children.toList()) updateNode(child, context)
    }

    private fun markDirty(node: CompanionNode) {
        dirty += node
    }

    /** Re-resolves the styles of every dirty node's subtree (descendants inherit and match on ancestors). */
    private fun flush() {
        if (dirty.isEmpty()) return
        val tops = dirty.filter { node -> generateSequence(node.parent) { it.parent }.none { it in dirty } }
        dirty.clear()
        for (node in tops) {
            if (node !== root && node.parent == null) continue // removed meanwhile
            resolveSubtree(node, node.parent?.computed)
        }
    }

    private fun resolveSubtree(node: CompanionNode, parentStyle: ComputedStyle?) {
        val style = resolver.resolve(node.style, parentStyle)
        if (style != node.computed) node.computed = style // equal styles don't recompose
        for (child in node.children.toList()) resolveSubtree(child, style)
    }

    companion object {
        /** The original single-`child` box: a frame around its child that doesn't stretch in its parent. */
        private val LEGACY_BOX_DEFAULTS = listOf(
            Declaration("display", "stack"),
            Declaration("place-items", "stretch"),
            Declaration("align-self", "flex-start"),
        )
    }
}
