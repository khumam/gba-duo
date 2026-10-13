package com.khumam.gbaduo.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts `org.json` structures into a plain Kotlin value tree: `Map<String, Any?>`,
 * `List<Any?>`, `Long`, `Double`, `String`, `Boolean`, or `null`. Used both for the
 * `values` snapshot and for parsing a Lua `update()`'s JSON-serialized return value.
 */
object JsonTree {

    fun parseObject(json: String): Map<String, Any?> = convertObject(JSONObject(json))

    private fun convertObject(obj: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = convertValue(obj.get(key))
        }
        return map
    }

    private fun convertArray(arr: JSONArray): List<Any?> =
        (0 until arr.length()).map { convertValue(arr.get(it)) }

    private fun convertValue(value: Any?): Any? = when (value) {
        is JSONObject -> convertObject(value)
        is JSONArray -> convertArray(value)
        JSONObject.NULL -> null
        is Int -> value.toLong()
        is java.math.BigInteger -> value.toLong()
        is java.math.BigDecimal -> value.toDouble()
        else -> value
    }
}
