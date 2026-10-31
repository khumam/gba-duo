package com.khumam.gbaduo.storage

import org.json.JSONObject
import java.io.File

/**
 * Every per-game setting in one JSON object, `<game dir>/settings.json`: the current save
 * slot (`"slot"`), the shader (`"shader"`), the color correction (`"color"`), the wide view (`"wide"`) and whatever comes next. Pure, no Android.
 *
 * A missing or broken file reads as empty. [put] changes one key and keeps all the others;
 * it writes a temp file and renames it, so a crash never leaves half a file.
 */
class GameSettings(private val file: File) {

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()

    fun getString(key: String): String? = read().opt(key) as? String

    fun getInt(key: String): Int? = (read().opt(key) as? Number)?.toInt()

    /** Sets [key] to [value] (a String, Int...); null removes it. */
    fun put(key: String, value: Any?) {
        val json = read()
        if (value == null) json.remove(key) else json.put(key, value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** The keys in a game's `settings.json`. */
object GameSettingsKeys {
    const val SLOT = "slot"
    const val SHADER = "shader"
    const val COLOR = "color"

    /** Wide view (a wider picture painted from the pack's game.lua): `on`, or absent for off. */
    const val WIDE = "wide"
    const val WIDE_ON = "on"

    /** Artwork lock: absent = scraping may change it, `none` = player removed it, `picked` = player chose it. */
    const val ARTWORK = "artwork"
    const val ARTWORK_NONE = "none"
    const val ARTWORK_PICKED = "picked"
}
