package com.khumam.gbaduo.companion

import org.json.JSONObject

data class TabEntry(val title: String, val child: Widget)

sealed class Widget {
    abstract val visibleIf: String?

    data class Column(val children: List<Widget>, val gap: Int, override val visibleIf: String?) : Widget()
    data class Row(val children: List<Widget>, val gap: Int, override val visibleIf: String?) : Widget()
    data class Tabs(val tabs: List<TabEntry>, override val visibleIf: String?) : Widget()
    data class Text(
        val text: String,
        val size: Int,
        val color: String,
        val bold: Boolean,
        override val visibleIf: String?,
    ) : Widget()
    data class Bar(
        val valueKey: String,
        val maxKey: String?,
        val max: Double,
        val height: Int,
        val label: String?,
        override val visibleIf: String?,
    ) : Widget()
    data class Image(val src: String, val size: Int, override val visibleIf: String?) : Widget()
    data class Spacer(val size: Int, override val visibleIf: String?) : Widget()
    data class ItemList(
        val itemsPath: String,
        val template: Widget,
        val emptyText: String?,
        override val visibleIf: String?,
    ) : Widget()
}

object LayoutParser {

    class InvalidLayoutException(message: String) : Exception(message)

    /** Strips a leading `$` used to mark a binding path (e.g. `"$hp"` -> `"hp"`). */
    private fun bindingKey(raw: String): String = raw.removePrefix("$")

    fun parse(json: String): Widget {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidLayoutException("Malformed JSON: ${e.message}")
        }
        return parseWidget(root)
    }

    private fun parseWidget(obj: JSONObject): Widget {
        val visibleIf = if (obj.has("visibleIf")) bindingKey(obj.getString("visibleIf")) else null
        return when (val type = obj.optString("type")) {
            "column" -> Widget.Column(parseChildren(obj), obj.optInt("gap", 0), visibleIf)
            "row" -> Widget.Row(parseChildren(obj), obj.optInt("gap", 0), visibleIf)
            "tabs" -> {
                val tabsArray = obj.optJSONArray("tabs") ?: throw InvalidLayoutException("tabs needs 'tabs' array")
                val tabs = (0 until tabsArray.length()).map { i ->
                    val tabObj = tabsArray.getJSONObject(i)
                    TabEntry(tabObj.getString("title"), parseWidget(tabObj.getJSONObject("child")))
                }
                Widget.Tabs(tabs, visibleIf)
            }
            "text" -> Widget.Text(
                text = obj.optString("text", ""),
                size = obj.optInt("size", 14),
                color = obj.optString("color", "#FFFFFF"),
                bold = obj.optBoolean("bold", false),
                visibleIf = visibleIf,
            )
            "bar" -> Widget.Bar(
                valueKey = bindingKey(obj.optString("value", "")),
                maxKey = if (obj.has("maxKey")) bindingKey(obj.getString("maxKey")) else null,
                max = obj.optDouble("max", 1.0),
                height = obj.optInt("height", 12),
                label = if (obj.has("label")) obj.getString("label") else null,
                visibleIf = visibleIf,
            )
            "image" -> Widget.Image(
                src = obj.optString("src", ""),
                size = obj.optInt("size", 32),
                visibleIf = visibleIf,
            )
            "spacer" -> Widget.Spacer(obj.optInt("size", 8), visibleIf)
            "list" -> {
                val templateObj = obj.optJSONObject("template")
                    ?: throw InvalidLayoutException("list needs a 'template'")
                Widget.ItemList(
                    itemsPath = bindingKey(obj.optString("items", "")),
                    template = parseWidget(templateObj),
                    emptyText = if (obj.has("empty")) obj.getString("empty") else null,
                    visibleIf = visibleIf,
                )
            }
            else -> throw InvalidLayoutException("Unknown widget type '$type'")
        }
    }

    private fun parseChildren(obj: JSONObject): List<Widget> {
        val array = obj.optJSONArray("children") ?: return emptyList()
        return (0 until array.length()).map { parseWidget(array.getJSONObject(it)) }
    }
}
