package com.khumam.gbaduo.controls

import android.view.KeyEvent
import com.khumam.gbaduo.emu.GbaKeys
import org.json.JSONObject

/**
 * The scheme as JSON, under a `builtin` profile so external controllers can add profiles
 * later. Pure. [decode] never throws: whatever is missing or invalid falls back to the
 * defaults, field by field.
 */
object ControlSchemeJson {
    private const val VERSION = 1
    private const val PROFILE = "builtin"

    private val BUTTON_NAMES = linkedMapOf(
        GbaKeys.A to "A", GbaKeys.B to "B", GbaKeys.L to "L", GbaKeys.R to "R",
        GbaKeys.START to "START", GbaKeys.SELECT to "SELECT",
        GbaKeys.UP to "UP", GbaKeys.DOWN to "DOWN", GbaKeys.LEFT to "LEFT", GbaKeys.RIGHT to "RIGHT",
    )

    fun encode(scheme: ControlScheme): String {
        val buttons = JSONObject()
        for ((bit, name) in BUTTON_NAMES) buttons.put(name, scheme.buttons.getValue(bit))
        val actions = JSONObject()
        for ((action, binding) in scheme.actions) {
            actions.put(action.name, JSONObject().put("key", binding.keyCode).put("hotkey", binding is Binding.WithHotkey))
        }
        val profile = JSONObject()
            .put("buttons", buttons)
            .put("hotkey", scheme.hotkeyButton ?: JSONObject.NULL)
            .put("actions", actions)
            .put("ffMode", scheme.ffMode.name)
        return JSONObject().put("version", VERSION).put("profiles", JSONObject().put(PROFILE, profile)).toString()
    }

    fun decode(json: String?): ControlScheme {
        val profile = runCatching { JSONObject(json!!).getJSONObject("profiles").getJSONObject(PROFILE) }.getOrNull()
            ?: return ControlScheme.DEFAULT
        val default = ControlScheme.DEFAULT

        val buttons = decodeButtons(profile.optJSONObject("buttons")) ?: default.buttons
        val hotkey = if (profile.has("hotkey") && !profile.isNull("hotkey")) {
            profile.optInt("hotkey", 0).takeIf { it > 0 && it != KeyEvent.KEYCODE_BACK }
        } else {
            null
        }
        val ffMode = FastForwardMode.values().firstOrNull { it.name == profile.optString("ffMode") } ?: default.ffMode
        val actions = if (profile.has("actions")) decodeActions(profile.optJSONObject("actions"), buttons, hotkey) else default.actions
        return ControlScheme(buttons, hotkey, actions, ffMode)
    }

    private fun decodeButtons(json: JSONObject?): Map<Int, Int>? {
        json ?: return null
        val out = linkedMapOf<Int, Int>()
        for ((bit, name) in BUTTON_NAMES) {
            val key = json.optInt(name, 0)
            if (key <= 0 || key == KeyEvent.KEYCODE_BACK) return null
            out[bit] = key
        }
        return if (out.values.toSet().size == out.size) out else null
    }

    /** Keeps only actions that obey the scheme's rules, so a hand-edited file can't make a key do two jobs. */
    private fun decodeActions(json: JSONObject?, buttons: Map<Int, Int>, hotkey: Int?): Map<HotkeyAction, Binding> {
        json ?: return emptyMap()
        val out = linkedMapOf<HotkeyAction, Binding>()
        val singles = mutableSetOf<Int>()
        val combos = mutableSetOf<Int>()
        for (action in HotkeyAction.values()) {
            val item = json.optJSONObject(action.name) ?: continue
            val key = item.optInt("key", 0)
            if (key <= 0 || key == KeyEvent.KEYCODE_BACK || key == hotkey) continue
            if (item.optBoolean("hotkey", false)) {
                if (hotkey != null && combos.add(key)) out[action] = Binding.WithHotkey(key)
            } else if (key !in buttons.values && singles.add(key)) {
                out[action] = Binding.Single(key)
            }
        }
        return out
    }
}
