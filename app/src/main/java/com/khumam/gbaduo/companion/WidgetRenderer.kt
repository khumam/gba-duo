package com.khumam.gbaduo.companion

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.khumam.gbaduo.companion.layout.Align
import com.khumam.gbaduo.companion.layout.ContainerStyle
import com.khumam.gbaduo.companion.layout.Display
import com.khumam.gbaduo.companion.render.AssetCache
import com.khumam.gbaduo.companion.render.BoxLayout
import com.khumam.gbaduo.companion.render.BoxParams
import com.khumam.gbaduo.companion.render.PressAware
import com.khumam.gbaduo.companion.render.StyleApplier
import com.khumam.gbaduo.companion.render.StyledImageView
import com.khumam.gbaduo.companion.render.StyledTextView
import com.khumam.gbaduo.companion.render.UiNode
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.CssWarning
import com.khumam.gbaduo.companion.style.Declaration
import com.khumam.gbaduo.companion.style.PseudoState
import com.khumam.gbaduo.companion.style.StyleNode
import com.khumam.gbaduo.companion.style.StyleResolver
import com.khumam.gbaduo.companion.style.Stylesheet
import java.io.File
import java.util.Locale

/**
 * Renders a pack's [Widget] tree as native views styled with CSS: the app's
 * built-in stylesheet, then the pack's stylesheets, then each widget's inline
 * styles. The view tree is built once; each [update] only re-runs bindings and
 * re-resolves the styles of nodes whose classes, states or bound styles changed.
 *
 * [update]'s argument is the binding context: a `Map<String, Any?>` snapshot at
 * the top level, or a [ScopedValues] for bindings inside a `list` widget's template.
 * Main thread only.
 */
class WidgetRenderer(
    private val context: Context,
    private val packDir: File,
    builtIn: List<Stylesheet> = emptyList(),
    pack: List<Stylesheet> = emptyList(),
) {
    private val resolver = StyleResolver(builtIn, pack)
    private val assets = AssetCache(packDir, resolver.fontFaces)
    private val applier = StyleApplier(context, assets)
    private val density = context.resources.displayMetrics.density

    private var root: UiNode? = null
    private val dirty = LinkedHashSet<UiNode>()

    /** Problems found while resolving styles (e.g. a `var()` with no value). */
    val warnings: Collection<String> get() = resolver.warnings

    fun build(widget: Widget): View {
        val viewport = BoxLayout(context)
        viewport.container = ContainerStyle(display = Display.STACK, alignItems = Align.START, justifyItems = Align.START)
        viewport.onSizeChanged = { w, h ->
            resolver.viewportWidth = w / density
            resolver.viewportHeight = h / density
            root?.let { markDirty(it) }
            viewport.post { flush() } // not during layout: applying styles requests layout
        }
        val rootNode = inflate(widget, parent = null, container = viewport)
        root = rootNode
        markDirty(rootNode)
        flush()
        return viewport
    }

    fun update(context: Any?) {
        val node = root ?: return
        updateNode(node, context)
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

    private fun attach(node: UiNode, parent: UiNode?, container: ViewGroup) {
        node.parent = parent
        parent?.let {
            it.children += node
            it.style.add(node.style)
        }
        container.addView(node.view, BoxParams())
        (node.view as? PressAware)?.onPressedChanged = { pressed ->
            node.style.states = if (pressed) node.style.states + PseudoState.PRESSED else node.style.states - PseudoState.PRESSED
            markDirty(node)
            flush()
        }
    }

    /** A renderer-generated part (bar fill, tab, ...): named by type and by a class of the same name. */
    private fun part(parent: UiNode, type: String, kind: UiNode.Kind, view: View, countsAsSibling: Boolean = true): UiNode {
        val node = UiNode(StyleNode(type, staticClasses = setOf(type), countsAsSibling = countsAsSibling), view, kind, null)
        attach(node, parent, parent.view as ViewGroup)
        return node
    }

    private fun inflate(widget: Widget, parent: UiNode?, container: ViewGroup): UiNode {
        val meta = widget.meta
        val defaults = if (widget is Widget.Box && widget.legacyChild) LEGACY_BOX_DEFAULTS else emptyList()
        val styleNode = StyleNode(typeName(widget), meta.id, meta.classes, defaults, meta.inline)
        val view: View = when (widget) {
            is Widget.Text -> StyledTextView(context)
            is Widget.Image -> StyledImageView(context).apply { adjustViewBounds = true }
            is Widget.Spacer -> View(context)
            else -> BoxLayout(context)
        }
        val node = UiNode(styleNode, view, UiNode.Kind.WIDGET, widget)
        attach(node, parent, container)
        bindCommon(node, widget)

        when (widget) {
            is Widget.Column -> widget.children.forEach { inflate(it, node, view as ViewGroup) }
            is Widget.Row -> widget.children.forEach { inflate(it, node, view as ViewGroup) }
            is Widget.Box -> widget.children.forEach { inflate(it, node, view as ViewGroup) }
            is Widget.Text -> {
                // Placeholders render empty until the first snapshot instead of showing "{hp}".
                node.rawText = Binding.interpolate(widget.text, null)
                node.bindings += { ctx -> setText(node, Binding.interpolate(widget.text, ctx)) }
            }
            is Widget.Image -> bindImage(node, widget.src)
            is Widget.Spacer -> Unit
            is Widget.Bar -> buildBar(node, widget)
            is Widget.ItemList -> buildList(node, widget)
            is Widget.Tabs -> buildTabs(node, widget)
        }
        return node
    }

    private fun bindCommon(node: UiNode, widget: Widget) {
        widget.visibleIf?.let { path ->
            node.bindings += { ctx ->
                val hidden = !Binding.isTruthy(Binding.resolve(path, ctx))
                if (hidden != node.hiddenByBinding) {
                    node.hiddenByBinding = hidden
                    applier.applyVisibility(node)
                }
            }
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

    private fun setBound(node: UiNode, declarations: List<Declaration>) {
        if (declarations != node.style.bound) {
            node.style.bound = declarations
            markDirty(node)
        }
    }

    private fun setText(node: UiNode, text: String) {
        if (text != node.rawText) {
            node.rawText = text
            applier.showText(node)
        }
    }

    // ---- images -----------------------------------------------------------------

    private fun bindImage(node: UiNode, src: String) {
        when {
            src.startsWith("asset:") -> {
                val bitmap = assets.packFile("assets/" + src.removePrefix("asset:"))?.let { assets.bitmap(it) }
                node.imageKey = src
                node.imageLoaded = bitmap != null
                applier.setImage(node, bitmap)
            }
            // A literal sprite key only exists once the pack's script has decoded it, so keep trying until it does.
            src.startsWith("gfx:") -> node.bindings += { _ -> showSprite(node, src) }
            src.startsWith("$") -> {
                val path = src.removePrefix("$")
                node.bindings += { ctx -> showSprite(node, Binding.resolve(path, ctx) as? String) }
            }
        }
    }

    private fun showSprite(node: UiNode, key: String?) {
        if (key == node.imageKey && (node.imageLoaded || key == null)) return
        val bitmap = when {
            key == null -> null
            key.startsWith("asset:") -> assets.packFile("assets/" + key.removePrefix("asset:"))?.let { assets.bitmap(it) }
            else -> assets.sprite(key)
        }
        node.imageKey = key
        node.imageLoaded = bitmap != null
        applier.setImage(node, bitmap)
    }

    // ---- bar ------------------------------------------------------------------------

    private fun buildBar(node: UiNode, widget: Widget.Bar) {
        val fill = part(node, "fill", UiNode.Kind.FILL, View(context))
        val label = widget.label?.let { template ->
            part(node, "label", UiNode.Kind.LABEL, StyledTextView(context)).also { it.rawText = Binding.interpolate(template, null) }
        }
        setBound(fill, listOf(Declaration("width", "0%")))
        node.bindings += { ctx ->
            val value = Binding.asDouble(Binding.resolve(widget.valueKey, ctx))
            val max = widget.maxKey?.let { Binding.asDouble(Binding.resolve(it, ctx)) } ?: widget.max
            val fraction = if (max > 0.0) (value / max).coerceIn(0.0, 1.0) else 0.0
            setBound(fill, listOf(Declaration("width", String.format(Locale.ROOT, "%.3f%%", fraction * 100))))
            if (label != null) setText(label, Binding.interpolate(widget.label, ctx))
        }
    }

    // ---- list -----------------------------------------------------------------------

    /**
     * Data-driven repeat: rebuilds item views only when the resolved list's length
     * changes; otherwise just re-binds each existing item with its new values.
     */
    private fun buildList(node: UiNode, widget: Widget.ItemList) {
        node.managesChildren = true
        val empty = part(node, "empty", UiNode.Kind.EMPTY, StyledTextView(context), countsAsSibling = false)
        empty.rawText = widget.emptyText.orEmpty()
        empty.hiddenByBinding = true // the binding below decides on the first update
        val items = mutableListOf<UiNode>()
        node.bindings += { ctx ->
            // Items resolve against the current list item first, then the snapshot root,
            // so a nested list can iterate its parent item's own list (e.g. `$moves`).
            val snapshotRoot = if (ctx is ScopedValues) ctx.root else ctx
            val values = Binding.asList(Binding.resolve(widget.itemsPath, ctx))
            if (values.size != items.size) {
                for (item in items) remove(item)
                items.clear()
                repeat(values.size) { items += inflate(widget.template, node, node.view as ViewGroup) }
                markDirty(node)
            }
            val showEmpty = values.isEmpty() && widget.emptyText != null
            if (empty.hiddenByBinding == showEmpty) {
                empty.hiddenByBinding = !showEmpty
                applier.applyVisibility(empty)
            }
            items.forEachIndexed { index, item -> updateNode(item, ScopedValues(item = values.getOrNull(index), index = index, root = snapshotRoot)) }
        }
    }

    private fun remove(node: UiNode) {
        val parent = node.parent ?: return
        fun forget(n: UiNode) {
            dirty.remove(n)
            n.transitions.cancelAll()
            n.children.forEach { forget(it) }
        }
        forget(node)
        parent.children.remove(node)
        parent.style.remove(node.style)
        (parent.view as ViewGroup).removeView(node.view)
        node.parent = null
    }

    // ---- tabs -----------------------------------------------------------------------

    private fun buildTabs(node: UiNode, widget: Widget.Tabs) {
        val bar = part(node, "tab-bar", UiNode.Kind.TAB_BAR, BoxLayout(context))
        val content = part(node, "tab-content", UiNode.Kind.TAB_CONTENT, BoxLayout(context))
        val labels = widget.tabs.map { tab -> part(bar, "tab", UiNode.Kind.TAB, StyledTextView(context)).also { it.rawText = tab.title } }
        val panels = widget.tabs.map { inflate(it.child, content, content.view as ViewGroup) }
        fun select(index: Int) {
            labels.forEachIndexed { i, label ->
                label.style.states = if (i == index) label.style.states + PseudoState.ACTIVE else label.style.states - PseudoState.ACTIVE
            }
            panels.forEachIndexed { i, panel -> panel.hiddenByTabs = i != index }
            markDirty(node)
        }
        labels.forEachIndexed { i, label ->
            label.view.setOnClickListener {
                select(i)
                flush()
            }
        }
        select(0)
    }

    // ---- updating and styling -------------------------------------------------

    private fun updateNode(node: UiNode, context: Any?) {
        for (binding in node.bindings) binding(context)
        if (node.managesChildren) return
        for (child in node.children.toList()) updateNode(child, context)
    }

    private fun markDirty(node: UiNode) {
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

    private fun resolveSubtree(node: UiNode, parentStyle: com.khumam.gbaduo.companion.style.ComputedStyle?) {
        val style = resolver.resolve(node.style, parentStyle)
        val previous = node.computed
        node.computed = style
        if (style != previous) applier.apply(node, style, previous) else applier.applyVisibility(node)
        for (child in node.children.toList()) resolveSubtree(child, style)
    }

    companion object {
        private const val TAG = "WidgetRenderer"

        /** The original single-`child` box: a frame around its child that doesn't stretch in its parent. */
        private val LEGACY_BOX_DEFAULTS = listOf(
            Declaration("display", "stack"),
            Declaration("place-items", "stretch"),
            Declaration("align-self", "flex-start"),
        )

        fun logWarnings(source: String, warnings: Collection<Any>) {
            for (w in warnings) Log.w(TAG, "$source: ${if (w is CssWarning) w.message else w}")
        }
    }
}
