package com.khumam.gbaduo.library

import org.json.JSONArray

/** (De)serializes the library's folder URIs (as strings) for SharedPreferences. Pure, no Android deps. */
object FolderList {

    /**
     * Reads the stored [json] array. If there is none yet, falls back to the single
     * [legacyFolder] that older versions stored, so upgrading keeps the chosen folder.
     */
    fun decode(json: String?, legacyFolder: String?): List<String> {
        if (json != null) {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it, null)?.takeIf(String::isNotBlank) }.distinct()
        }
        return listOfNotNull(legacyFolder?.takeIf(String::isNotBlank))
    }

    fun encode(folders: List<String>): String = JSONArray(folders.distinct()).toString()

    fun add(folders: List<String>, folder: String): List<String> =
        if (folder in folders) folders else folders + folder

    fun remove(folders: List<String>, folder: String): List<String> = folders - folder
}
