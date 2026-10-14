package com.khumam.gbaduo.companion

/** Context for a `list` widget's template: a path resolves against [item] first, then [root]. */
data class ScopedValues(val item: Any?, val index: Int, val root: Any?)

/**
 * Resolves `"party[0].name"`-style paths over a value tree (`Map<String, Any?>` /
 * `List<Any?>` / scalars, as produced by [JsonTree] or [ValuesReader]). Inside a
 * `list` widget's template, [context] is a [ScopedValues] — `@index` and item keys
 * resolve first, falling back to the root snapshot for anything the item doesn't have.
 */
object Binding {

    private val TOKEN = Regex("\\{([^{}]+)\\}")
    private val SEGMENT = Regex("([a-zA-Z0-9_]+)|\\[(\\d+)\\]")

    fun resolve(path: String, context: Any?): Any? {
        if (context is ScopedValues) {
            if (path == "@index") return context.index.toLong()
            resolvePlain(path, context.item)?.let { return it }
            return resolvePlain(path, context.root)
        }
        return resolvePlain(path, context)
    }

    private fun resolvePlain(path: String, root: Any?): Any? {
        var current: Any? = root
        for (match in SEGMENT.findAll(path)) {
            val key = match.groupValues[1]
            current = if (key.isNotEmpty()) {
                (current as? Map<*, *>)?.get(key)
            } else {
                val index = match.groupValues[2].toInt()
                (current as? List<*>)?.getOrNull(index)
            }
            if (current == null) return null
        }
        return current
    }

    fun interpolate(template: String, context: Any?): String =
        TOKEN.replace(template) { match -> format(resolve(match.groupValues[1], context)) }

    private fun format(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    fun isTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Long -> value != 0L
        is Double -> value != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    fun asLong(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    fun asDouble(value: Any?): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    @Suppress("UNCHECKED_CAST")
    fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()
}
