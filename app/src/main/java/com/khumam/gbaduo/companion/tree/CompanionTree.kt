package com.khumam.gbaduo.companion.tree

import androidx.compose.ui.graphics.ImageBitmap
import com.khumam.gbaduo.companion.Action
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
 * It also holds the UI state (`$ui.*`): which page each `pages` widget shows and
 * whatever taps `set`. Taps run their widget's `onClick` actions here; a `call`
 * goes to [onCall], and every UI state change is reported to [onUiChange] (so the
 * pack script sees it as `ui`).
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

    private val uiState = LinkedHashMap<String, Any?>()
    private val history = HashMap<String, ArrayDeque<String>>()
    private val initialPages = HashMap<String, String>()
    private var snapshot: Any? = null

    /** Called with a script action to run (`call`), after any UI state change from the same tap. */
    var onCall: (name: String, args: List<Any?>) -> Unit = { _, _ -> }

    /** Called with the new UI state whenever it changes. */
    var onUiChange: (Map<String, Any?>) -> Unit = {}

    val root: CompanionNode = inflate(rootWidget, parent = null)

    /** The current UI state (`$ui.*`): page names and values set by taps. */
    val ui: Map<String, Any?> get() = LinkedHashMap(uiState)

    /** Problems found while resolving styles (e.g. a `var()` with no value). */
    val warnings: Collection<String> get() = resolver.warnings

    val fontFaces get() = resolver.fontFaces

    init {
        markDirty(root)
        flush()
    }

    /** Applies a snapshot (the pack's values as a `Map<String, Any?>`); bindings also see `ui`. */
    fun update(context: Any?) {
        snapshot = context
        rebind()
    }

    private fun rebind() {
        updateNode(root, withUi(snapshot))
        flush()
    }

    private fun withUi(context: Any?): Any? {
        val ui = LinkedHashMap(uiState)
        return when (context) {
            is Map<*, *> -> LinkedHashMap<Any?, Any?>(context).apply { put(UI_KEY, ui) }
            null -> mapOf(UI_KEY to ui)
            else -> context
        }
    }

    /**
     * Runs [actions]: from a tap (with the tapped widget's binding [context]) or
     * returned by a script action (no context). UI changes apply at once.
     */
    fun perform(actions: List<Action>, context: Any? = null) {
        var changed = false
        val calls = mutableListOf<Action.Call>()
        fun setUi(key: String, value: Any?) {
            if (key in uiState && uiState[key] == value) return
            uiState[key] = value
            changed = true
        }
        for (action in actions) {
            when (action) {
                is Action.Set -> for ((key, value) in action.values) setUi(key, actionValue(value, context))
                is Action.Navigate -> {
                    val page = Binding.interpolate(action.page, context)
                    val current = uiState[action.state]?.toString()
                    if (page != current) {
                        if (current != null) {
                            val stack = history.getOrPut(action.state) { ArrayDeque() }
                            stack.addLast(current)
                            if (stack.size > MAX_HISTORY) stack.removeFirst()
                        }
                        setUi(action.state, page)
                    }
                }
                is Action.Back -> {
                    val previous = history[action.state]?.removeLastOrNull() ?: initialPages[action.state]
                    if (previous != null) setUi(action.state, previous)
                }
                is Action.Call -> calls += Action.Call(action.name, action.args.map { actionValue(it, context) })
            }
        }
        if (changed) {
            rebind()
            onUiChange(ui)
        }
        for (call in calls) onCall(call.name, call.args)
    }

    /** A whole-string `{path}` keeps the bound value's type (e.g. `{@index}` stays a number). */
    private fun actionValue(value: Any?, context: Any?): Any? {
        if (value !is String) return value
        val whole = WHOLE_BINDING.matchEntire(value) ?: return Binding.interpolate(value, context)
        return Binding.resolve(whole.groupValues[1], context)
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
        is Widget.Pages -> "pages"
        is Widget.Button -> "button"
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
        if (meta.onClick.isNotEmpty()) bindClick(node, meta.onClick)

        when (widget) {
            is Widget.Column -> widget.children.forEach { inflate(it, node) }
            is Widget.Row -> widget.children.forEach { inflate(it, node) }
            is Widget.Box -> widget.children.forEach { inflate(it, node) }
            is Widget.Button -> widget.children.forEach { inflate(it, node) }
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
            is Widget.Pages -> buildPages(node, widget)
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

    private fun bindClick(node: CompanionNode, actions: List<Action>) {
        var context: Any? = null
        node.bindings += { ctx -> context = ctx }
        node.onClick = { perform(actions, context) }
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
            panels.forEachIndexed { i, panel -> panel.hiddenBySwitch = i != index }
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

    // ---- pages ----------------------------------------------------------------------

    private fun buildPages(node: CompanionNode, widget: Widget.Pages) {
        initialPages.putIfAbsent(widget.state, widget.initial)
        if (widget.state !in uiState) uiState[widget.state] = widget.initial
        val panels = widget.pages.map { inflate(it.child, node) }
        fun show(name: String?) {
            // An unknown page name (e.g. a typo in a `set`) shows the initial page.
            val index = widget.pages.indexOfFirst { it.name == name }.takeIf { it >= 0 }
                ?: widget.pages.indexOfFirst { it.name == widget.initial }
            panels.forEachIndexed { i, panel -> panel.hiddenBySwitch = i != index }
        }
        show(widget.initial)
        node.bindings += { _ -> show(uiState[widget.state]?.toString()) }
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
        /** The snapshot key bindings read UI state from (`$ui.page`). */
        const val UI_KEY = "ui"
        private const val MAX_HISTORY = 32
        private val WHOLE_BINDING = Regex("^\\{([^{}]+)\\}$")

        /** The original single-`child` box: a frame around its child that doesn't stretch in its parent. */
        private val LEGACY_BOX_DEFAULTS = listOf(
            Declaration("display", "stack"),
            Declaration("place-items", "stretch"),
            Declaration("align-self", "flex-start"),
        )
    }
}
