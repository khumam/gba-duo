package com.khumam.gbaduo.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.khumam.gbaduo.emu.NativeCore
import java.io.File

/**
 * Builds an Android View tree from a [Widget] once, then updates only bound
 * properties on each [update] call — no view tree rebuilding per tick.
 *
 * [update]'s argument is the binding context: a `Map<String, Any?>` snapshot at
 * the top level, or a [ScopedValues] for bindings inside a `list` widget's template.
 */
class WidgetRenderer(private val context: Context, private val packDir: File) {

    private data class Bound(val view: View, val apply: (Any?) -> Unit)

    private val bindings = mutableListOf<Bound>()
    private val dp = context.resources.displayMetrics.density

    private fun px(dpValue: Int): Int = (dpValue * dp).toInt()

    fun build(root: Widget): View {
        bindings.clear()
        return inflate(root)
    }

    fun update(context: Any?) {
        for (bound in bindings) {
            bound.apply(context)
        }
    }

    private fun applyVisibility(view: View, visibleIf: String?) {
        if (visibleIf == null) return
        bindings += Bound(view) { ctx ->
            view.visibility = if (Binding.isTruthy(Binding.resolve(visibleIf, ctx))) View.VISIBLE else View.GONE
        }
    }

    private fun inflate(widget: Widget): View = when (widget) {
        is Widget.Column -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            widget.children.forEachIndexed { i, child ->
                addView(inflate(child))
                if (i < widget.children.size - 1 && widget.gap > 0) {
                    addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(widget.gap)))
                }
            }
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.Row -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            widget.children.forEachIndexed { i, child ->
                addView(inflate(child))
                if (i < widget.children.size - 1 && widget.gap > 0) {
                    addView(View(context), LinearLayout.LayoutParams(px(widget.gap), ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.Tabs -> buildTabs(widget)
        is Widget.Text -> TextView(context).apply {
            textSize = widget.size.toFloat()
            setTextColor(runCatching { Color.parseColor(widget.color) }.getOrDefault(Color.WHITE))
            widget.font?.let { fontPath ->
                val fontFile = File(packDir, "assets/$fontPath")
                if (fontFile.exists()) {
                    runCatching { Typeface.createFromFile(fontFile) }.getOrNull()?.let { typeface = it }
                }
            }
            if (widget.bold) setTypeface(typeface, Typeface.BOLD)
            text = widget.text
            bindings += Bound(this) { ctx -> text = Binding.interpolate(widget.text, ctx) }
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.Bar -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(widget.height))
            max = widget.max.toInt().coerceAtLeast(1)
            bindings += Bound(this) { ctx ->
                val value = Binding.asLong(Binding.resolve(widget.valueKey, ctx))
                val maxValue = widget.maxKey?.let { Binding.asLong(Binding.resolve(it, ctx)) }?.toInt() ?: widget.max.toInt()
                this.max = maxValue.coerceAtLeast(1)
                progress = value.toInt().coerceIn(0, this.max)
            }
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.Image -> ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(px(widget.size), px(widget.size))
            when {
                widget.src.startsWith("asset:") -> {
                    val file = File(packDir, "assets/${widget.src.removePrefix("asset:")}")
                    if (file.exists()) setImageURI(android.net.Uri.fromFile(file))
                }
                widget.src.startsWith("gfx:") -> setGfxImage(this, widget.src)
                widget.src.startsWith("$") -> bindings += Bound(this) { ctx ->
                    val key = Binding.resolve(widget.src.removePrefix("$"), ctx) as? String
                    if (key != null) setGfxImage(this, key)
                }
            }
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.Spacer -> View(context).apply {
            layoutParams = ViewGroup.LayoutParams(px(widget.size), px(widget.size))
            applyVisibility(this, widget.visibleIf)
        }
        is Widget.ItemList -> buildItemList(widget)
        is Widget.Box -> FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                widget.width?.let { px(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                widget.height?.let { px(it) } ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(px(widget.padding), px(widget.padding), px(widget.padding), px(widget.padding))
            background = GradientDrawable().apply {
                widget.background?.let { bg ->
                    runCatching { Color.parseColor(bg) }.getOrNull()?.let { setColor(it) }
                }
                if (widget.cornerRadius > 0) cornerRadius = px(widget.cornerRadius).toFloat()
                if (widget.borderColor != null && widget.borderWidth > 0) {
                    runCatching { Color.parseColor(widget.borderColor) }.getOrNull()
                        ?.let { setStroke(px(widget.borderWidth), it) }
                }
            }
            addView(inflate(widget.child))
            applyVisibility(this, widget.visibleIf)
        }
    }

    private fun setGfxImage(view: ImageView, key: String) {
        val data = NativeCore.nativeImage(key) ?: return
        val width = data[0]
        val height = data[1]
        val pixels = IntArray(width * height) { data[2 + it] }
        view.setImageBitmap(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
    }

    private fun buildTabs(widget: Widget.Tabs): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val tabBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val content = FrameLayout(context)
        val childViews = widget.tabs.map { inflate(it.child) }

        fun selectTab(index: Int) {
            content.removeAllViews()
            content.addView(childViews[index])
            for (i in widget.tabs.indices) {
                (tabBar.getChildAt(i) as TextView).setTextColor(if (i == index) Color.WHITE else Color.GRAY)
            }
        }

        widget.tabs.forEachIndexed { i, tab ->
            val label = TextView(context).apply {
                text = tab.title
                setPadding(px(12), px(8), px(12), px(8))
                setOnClickListener { selectTab(i) }
            }
            tabBar.addView(label)
        }
        container.addView(tabBar)
        container.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(0)
        applyVisibility(container, widget.visibleIf)
        return container
    }

    /**
     * Data-driven repeat: rebuilds item views only when the resolved list's length
     * changes (per the design spec); otherwise just re-binds each existing item view.
     */
    private fun buildItemList(widget: Widget.ItemList): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val emptyLabel = TextView(context).apply {
            text = widget.emptyText.orEmpty()
            setTextColor(Color.GRAY)
            visibility = View.GONE // the bound closure below sets the real visibility on first update()
        }
        container.addView(emptyLabel)

        var itemRenderers = listOf<WidgetRenderer>()

        bindings += Bound(container) { ctx ->
            // The items path always resolves against the top-level snapshot, even if
            // this list is itself nested inside another list's template.
            val snapshotRoot = if (ctx is ScopedValues) ctx.root else ctx
            val items = Binding.asList(Binding.resolve(widget.itemsPath, snapshotRoot))

            if (items.size != itemRenderers.size) {
                for (i in container.childCount - 1 downTo 1) container.removeViewAt(i)
                itemRenderers = items.indices.map {
                    val renderer = WidgetRenderer(context, packDir)
                    container.addView(renderer.build(widget.template))
                    renderer
                }
            }

            emptyLabel.visibility = if (items.isEmpty() && widget.emptyText != null) View.VISIBLE else View.GONE

            itemRenderers.forEachIndexed { index, renderer ->
                renderer.update(ScopedValues(item = items.getOrNull(index), index = index, root = snapshotRoot))
            }
        }
        applyVisibility(container, widget.visibleIf)
        return container
    }
}
