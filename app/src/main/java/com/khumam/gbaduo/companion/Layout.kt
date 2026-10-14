package com.khumam.gbaduo.companion

import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.CssValues
import com.khumam.gbaduo.companion.style.CssWarning
import com.khumam.gbaduo.companion.style.Declaration
import org.json.JSONObject

data class TabEntry(val title: String, val child: Widget)

/**
 * Styling hooks every widget accepts: `id`, `class` (space-separated), `style`
 * (inline CSS; also holds the legacy widget keys converted to CSS), `classIf`
 * (class -> binding path) and `bindStyle` (property -> `{binding}` template).
 */
data class WidgetMeta(
    val id: String? = null,
    val classes: Set<String> = emptySet(),
    val inline: List<Declaration> = emptyList(),
    val classIf: Map<String, String> = emptyMap(),
    val bindStyle: Map<String, String> = emptyMap(),
) {
    companion object {
        val NONE = WidgetMeta()
    }
}

sealed class Widget {
    abstract val visibleIf: String?
    abstract val meta: WidgetMeta

    data class Column(val children: List<Widget>, val gap: Int, override val visibleIf: String?, override val meta: WidgetMeta = WidgetMeta.NONE) : Widget()
    data class Row(val children: List<Widget>, val gap: Int, override val visibleIf: String?, override val meta: WidgetMeta = WidgetMeta.NONE) : Widget()
    data class Tabs(val tabs: List<TabEntry>, override val visibleIf: String?, override val meta: WidgetMeta = WidgetMeta.NONE) : Widget()
    data class Text(
        val text: String,
        val size: Int,
        val color: String,
        val bold: Boolean,
        val font: String?,
        override val visibleIf: String?,
        override val meta: WidgetMeta = WidgetMeta.NONE,
    ) : Widget()
    data class Bar(
        val valueKey: String,
        val maxKey: String?,
        val max: Double,
        val height: Int,
        /** Shown over the bar; supports `{binding}` placeholders. */
        val label: String?,
        override val visibleIf: String?,
        override val meta: WidgetMeta = WidgetMeta.NONE,
    ) : Widget()
    data class Image(val src: String, val size: Int, override val visibleIf: String?, override val meta: WidgetMeta = WidgetMeta.NONE) : Widget()
    data class Spacer(val size: Int, override val visibleIf: String?, override val meta: WidgetMeta = WidgetMeta.NONE) : Widget()
    data class ItemList(
        val itemsPath: String,
        val template: Widget,
        val emptyText: String?,
        override val visibleIf: String?,
        override val meta: WidgetMeta = WidgetMeta.NONE,
    ) : Widget()

    /**
     * A styled container. The original form wraps one `child` and lays it out
     * like a frame (`display: stack`); the `children` form is a flex column by
     * default. Either may be empty (e.g. a bar's fill drawn purely by CSS).
     */
    data class Box(
        val children: List<Widget>,
        val width: Int?,
        val height: Int?,
        val background: String?,
        val cornerRadius: Int,
        val borderColor: String?,
        val borderWidth: Int,
        val padding: Int,
        override val visibleIf: String?,
        /** True for the original single-`child` form. */
        val legacyChild: Boolean = false,
        override val meta: WidgetMeta = WidgetMeta.NONE,
    ) : Widget() {
        val child: Widget? get() = children.singleOrNull()
    }
}

object LayoutParser {

    class InvalidLayoutException(message: String) : Exception(message)

    /** A parsed layout plus CSS problems found in its `style`/`bindStyle` objects (skipped, not fatal). */
    data class Parsed(val root: Widget, val warnings: List<CssWarning>)

    /** Strips a leading `$` used to mark a binding path (e.g. `"$hp"` -> `"hp"`). */
    private fun bindingKey(raw: String): String = raw.removePrefix("$")

    /** Prefix for `font-family` names that point at a pack file (`text.font`, relative to `assets/`). */
    const val ASSET_FONT_PREFIX = "asset-font:"

    fun parse(json: String): Widget = parseWithWarnings(json).root

    fun parseWithWarnings(json: String): Parsed {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidLayoutException("Malformed JSON: ${e.message}")
        }
        val warnings = mutableListOf<CssWarning>()
        return Parsed(parseWidget(root, warnings), warnings)
    }

    private fun parseWidget(obj: JSONObject, warnings: MutableList<CssWarning>): Widget {
        val visibleIf = if (obj.has("visibleIf")) bindingKey(obj.getString("visibleIf")) else null
        val type = obj.optString("type")
        val legacy = mutableListOf<Pair<String, String>>()
        fun legacyPx(key: String, vararg properties: String) {
            if (obj.has(key)) for (p in properties) legacy += p to "${obj.getInt(key)}px"
        }

        val widget: Widget = when (type) {
            "column", "row" -> {
                legacyPx("gap", "gap")
                val children = parseChildren(obj, warnings)
                if (type == "column") Widget.Column(children, obj.optInt("gap", 0), visibleIf) else Widget.Row(children, obj.optInt("gap", 0), visibleIf)
            }
            "tabs" -> {
                val tabsArray = obj.optJSONArray("tabs") ?: throw InvalidLayoutException("tabs needs 'tabs' array")
                if (tabsArray.length() == 0) throw InvalidLayoutException("tabs needs at least one tab")
                val tabs = (0 until tabsArray.length()).map { i ->
                    val tabObj = tabsArray.getJSONObject(i)
                    TabEntry(tabObj.getString("title"), parseWidget(tabObj.getJSONObject("child"), warnings))
                }
                Widget.Tabs(tabs, visibleIf)
            }
            "text" -> {
                legacyPx("size", "font-size")
                if (obj.has("color")) legacy += "color" to CssValues.androidColorToCss(obj.getString("color"))
                if (obj.has("bold")) legacy += "font-weight" to (if (obj.getBoolean("bold")) "700" else "normal")
                if (obj.has("font")) legacy += "font-family" to "\"$ASSET_FONT_PREFIX${obj.getString("font")}\""
                Widget.Text(
                    text = obj.optString("text", ""),
                    size = obj.optInt("size", 14),
                    color = obj.optString("color", "#FFFFFF"),
                    bold = obj.optBoolean("bold", false),
                    font = if (obj.has("font")) obj.getString("font") else null,
                    visibleIf = visibleIf,
                )
            }
            "bar" -> {
                legacyPx("height", "height")
                Widget.Bar(
                    valueKey = bindingKey(obj.optString("value", "")),
                    maxKey = if (obj.has("maxKey")) bindingKey(obj.getString("maxKey")) else null,
                    max = obj.optDouble("max", 1.0),
                    height = obj.optInt("height", 12),
                    label = if (obj.has("label")) obj.getString("label") else null,
                    visibleIf = visibleIf,
                )
            }
            "image" -> {
                legacyPx("size", "width", "height")
                Widget.Image(src = obj.optString("src", ""), size = obj.optInt("size", 32), visibleIf = visibleIf)
            }
            "spacer" -> {
                legacyPx("size", "width", "height")
                Widget.Spacer(obj.optInt("size", 8), visibleIf)
            }
            "list" -> {
                val templateObj = obj.optJSONObject("template")
                    ?: throw InvalidLayoutException("list needs a 'template'")
                Widget.ItemList(
                    itemsPath = bindingKey(obj.optString("items", "")),
                    template = parseWidget(templateObj, warnings),
                    emptyText = if (obj.has("empty")) obj.getString("empty") else null,
                    visibleIf = visibleIf,
                )
            }
            "box" -> {
                val childObj = obj.optJSONObject("child")
                val children = when {
                    childObj != null -> listOf(parseWidget(childObj, warnings))
                    else -> parseChildren(obj, warnings)
                }
                legacyPx("width", "width")
                legacyPx("height", "height")
                legacyPx("padding", "padding")
                legacyPx("cornerRadius", "border-radius")
                if (obj.has("background")) legacy += "background-color" to CssValues.androidColorToCss(obj.getString("background"))
                val borderWidth = obj.optInt("borderWidth", 0)
                if (obj.has("borderColor") && borderWidth > 0) {
                    legacy += "border" to "${borderWidth}px solid ${CssValues.androidColorToCss(obj.getString("borderColor"))}"
                }
                Widget.Box(
                    children = children,
                    width = if (obj.has("width")) obj.getInt("width") else null,
                    height = if (obj.has("height")) obj.getInt("height") else null,
                    background = if (obj.has("background")) obj.getString("background") else null,
                    cornerRadius = obj.optInt("cornerRadius", 0),
                    borderColor = if (obj.has("borderColor")) obj.getString("borderColor") else null,
                    borderWidth = borderWidth,
                    padding = obj.optInt("padding", 0),
                    visibleIf = visibleIf,
                    legacyChild = childObj != null,
                )
            }
            else -> throw InvalidLayoutException("Unknown widget type '$type'")
        }
        return widget.withMeta(parseMeta(obj, type, legacy, warnings))
    }

    private fun parseMeta(obj: JSONObject, type: String, legacy: List<Pair<String, String>>, warnings: MutableList<CssWarning>): WidgetMeta {
        val source = "layout.json ($type)"
        val inline = mutableListOf<Declaration>()
        for ((property, value) in legacy) {
            CssParser.declaration(property, value, null, source, warnings)?.let { inline += it }
        }
        when (val style = obj.opt("style")) {
            is JSONObject -> for (key in style.keys()) {
                val property = if (key.startsWith("--")) key else key.lowercase()
                CssParser.declaration(property, style.get(key).toString(), null, source, warnings)?.let { inline += it }
            }
            is String -> inline += CssParser.parseDeclarations(style, null, source, warnings)
        }
        val classes = obj.optString("class", "").split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        val classIf = stringMap(obj.optJSONObject("classIf")).mapValues { bindingKey(it.value) }
        val bindStyle = stringMap(obj.optJSONObject("bindStyle")).mapKeys { if (it.key.startsWith("--")) it.key else it.key.lowercase() }
        for (property in bindStyle.keys) {
            if (!com.khumam.gbaduo.companion.style.Properties.isKnown(property)) warnings += CssWarning("$source: unsupported property '$property' in bindStyle")
        }
        val id = if (obj.has("id")) obj.getString("id") else null
        if (id == null && classes.isEmpty() && inline.isEmpty() && classIf.isEmpty() && bindStyle.isEmpty()) return WidgetMeta.NONE
        return WidgetMeta(id, classes, inline, classIf, bindStyle)
    }

    private fun stringMap(obj: JSONObject?): Map<String, String> {
        obj ?: return emptyMap()
        return obj.keys().asSequence().associateWith { obj.get(it).toString() }
    }

    private fun Widget.withMeta(meta: WidgetMeta): Widget = when (this) {
        is Widget.Column -> copy(meta = meta)
        is Widget.Row -> copy(meta = meta)
        is Widget.Tabs -> copy(meta = meta)
        is Widget.Text -> copy(meta = meta)
        is Widget.Bar -> copy(meta = meta)
        is Widget.Image -> copy(meta = meta)
        is Widget.Spacer -> copy(meta = meta)
        is Widget.ItemList -> copy(meta = meta)
        is Widget.Box -> copy(meta = meta)
    }

    private fun parseChildren(obj: JSONObject, warnings: MutableList<CssWarning>): List<Widget> {
        val array = obj.optJSONArray("children") ?: return emptyList()
        return (0 until array.length()).map { parseWidget(array.getJSONObject(it), warnings) }
    }
}
