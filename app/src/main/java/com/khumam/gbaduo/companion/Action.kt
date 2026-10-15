package com.khumam.gbaduo.companion

/**
 * What tapping a widget does (its `onClick`), or what a script's `actions.*`
 * function asks for by returning the same shape. Strings in [Set.values],
 * [Navigate.page] and [Call.args] may hold `{binding}` placeholders, resolved
 * against the tapped widget's data (a list item, or the snapshot).
 */
sealed class Action {
    /** Sets UI state keys, readable in bindings as `$ui.<key>` and in Lua as `ui.<key>`. */
    data class Set(val values: Map<String, Any?>) : Action()

    /** Shows [page] of the `pages` widgets that follow UI key [state], remembering the current one for [Back]. */
    data class Navigate(val page: String, val state: String) : Action()

    /** Returns to the page shown before the last [Navigate] on [state] (or its initial page). */
    data class Back(val state: String) : Action()

    /** Calls the pack script's `actions.<name>(args...)` on the emulator thread. */
    data class Call(val name: String, val args: List<Any?>) : Action()
}

object Actions {
    /** The UI key `pages`, `navigate` and `back` use unless told otherwise. */
    const val DEFAULT_STATE = "page"

    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun isValidKey(key: String): Boolean = KEY.matches(key)

    /**
     * Parses an `onClick` value (already converted by [JsonTree]): one action object,
     * a list of them, or the shorthand `"back"`. An object may combine `set`,
     * `navigate`/`back` and `call`; they run in that order. Problems are skipped
     * and described in [warnings].
     */
    fun parse(value: Any?, warnings: MutableList<String>): List<Action> = when (value) {
        null -> emptyList()
        is List<*> -> value.flatMap { parse(it, warnings) }
        is String -> if (value == "back") listOf(Action.Back(DEFAULT_STATE)) else {
            warnings += "unknown action '$value' (use an object like { \"navigate\": \"$value\" })"
            emptyList()
        }
        is Map<*, *> -> parseObject(value, warnings)
        else -> {
            warnings += "an action must be an object, a list of objects, or \"back\""
            emptyList()
        }
    }

    private fun parseObject(obj: Map<*, *>, warnings: MutableList<String>): List<Action> {
        val actions = mutableListOf<Action>()
        for (key in obj.keys) {
            if (key !in KNOWN) warnings += "unknown action key '$key'"
        }
        val state = obj["state"]?.toString() ?: DEFAULT_STATE
        if (!isValidKey(state)) {
            warnings += "invalid state name '$state'"
            return emptyList()
        }

        when (val set = obj["set"]) {
            null -> Unit
            is Map<*, *> -> {
                val values = LinkedHashMap<String, Any?>()
                for ((k, v) in set) {
                    val name = k.toString()
                    if (isValidKey(name)) values[name] = v else warnings += "invalid UI key '$name' in set"
                }
                if (values.isNotEmpty()) actions += Action.Set(values)
            }
            else -> warnings += "'set' must be an object of UI keys to values"
        }

        val navigate = obj["navigate"]
        val back = obj["back"]
        when {
            navigate != null && back != null && back != false -> warnings += "an action can't both navigate and go back"
            navigate is String -> actions += Action.Navigate(navigate, state)
            navigate != null -> warnings += "'navigate' must be a page name"
            back == true -> actions += Action.Back(state)
            back is String -> if (isValidKey(back)) actions += Action.Back(back) else warnings += "invalid state name '$back'"
            back != null && back != false -> warnings += "'back' must be true or a state name"
        }

        when (val call = obj["call"]) {
            null -> Unit
            is String -> {
                val args = when (val raw = obj["args"]) {
                    null -> emptyList()
                    is List<*> -> raw
                    else -> listOf(raw)
                }
                if (isValidKey(call)) actions += Action.Call(call, args) else warnings += "invalid action name '$call'"
            }
            else -> warnings += "'call' must be the name of a function in the script's actions table"
        }
        return actions
    }

    /**
     * Parses UI actions the pack's script returned — from `actions.<name>()`, or as the `ui`
     * entry of `update()`. Same shape as an `onClick`, minus `call` (a script calls its own
     * functions directly), which is dropped with a warning.
     */
    fun parseScriptResult(value: Any?, warnings: MutableList<String>): List<Action> =
        parse(value, warnings).filter { action ->
            (action !is Action.Call).also { if (!it) warnings += "a script can't return 'call'; call the Lua function directly" }
        }

    private val KNOWN = setOf("set", "navigate", "back", "state", "call", "args")
}
