package com.khumam.gbaduo.companion

import org.json.JSONObject
import java.io.File

/** Persists which pack id is assigned to each ROM file name, as a flat JSON object. */
class PackAssignments(private val file: File) {

    private fun load(): JSONObject =
        if (file.exists() && file.length() > 0) JSONObject(file.readText()) else JSONObject()

    fun get(romFileName: String): String? {
        val json = load()
        return if (json.has(romFileName)) json.getString(romFileName) else null
    }

    /** [packId] of null clears the assignment for [romFileName]. */
    fun set(romFileName: String, packId: String?) {
        val json = load()
        if (packId == null) {
            json.remove(romFileName)
        } else {
            json.put(romFileName, packId)
        }
        file.parentFile?.mkdirs()
        file.writeText(json.toString())
    }
}
