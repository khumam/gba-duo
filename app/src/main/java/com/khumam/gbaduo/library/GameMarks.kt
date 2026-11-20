package com.khumam.gbaduo.library

import android.content.Context
import org.json.JSONObject

/**
 * What the player has marked on their games, by library file name: [favorites], and when each
 * game was last played ([played], epoch millis) for the Recent filter. Pure; [GameMarksStore] keeps it.
 */
data class GameMarks(
    val favorites: Set<String> = emptySet(),
    val played: Map<String, Long> = emptyMap(),
) {
    fun isFavorite(fileName: String) = fileName in favorites

    fun withFavorite(fileName: String, on: Boolean) = copy(favorites = if (on) favorites + fileName else favorites - fileName)

    /** Notes [fileName] played at [now]; only the [MAX_RECENT] newest are kept. */
    fun withPlayed(fileName: String, now: Long): GameMarks {
        val next = (played + (fileName to now)).entries.sortedByDescending { it.value }.take(MAX_RECENT)
        return copy(played = next.associate { it.key to it.value })
    }

    /** Games played before, newest first. */
    fun recent(entries: List<RomEntry>): List<RomEntry> =
        entries.filter { it.fileName in played }.sortedByDescending { played[it.fileName] }

    companion object {
        const val MAX_RECENT = 20
    }
}

/** Keeps [GameMarks] in the library's preferences. */
object GameMarksStore {
    private const val PREFS = "library"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_PLAYED = "played"

    fun load(context: Context): GameMarks {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet(KEY_FAVORITES, null)?.toSet().orEmpty()
        val played = runCatching {
            val json = JSONObject(prefs.getString(KEY_PLAYED, null) ?: "{}")
            json.keys().asSequence().associateWith { json.getLong(it) }
        }.getOrDefault(emptyMap())
        return GameMarks(favorites, played)
    }

    private fun save(context: Context, marks: GameMarks) {
        val played = JSONObject().also { json -> marks.played.forEach { (name, at) -> json.put(name, at) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_FAVORITES, marks.favorites)
            .putString(KEY_PLAYED, played.toString())
            .apply()
    }

    fun setFavorite(context: Context, fileName: String, on: Boolean): GameMarks =
        load(context).withFavorite(fileName, on).also { save(context, it) }

    /** Called when a game starts, from the shelf or from outside the app. */
    fun recordPlayed(context: Context, fileName: String, now: Long = System.currentTimeMillis()) {
        save(context, load(context).withPlayed(fileName, now))
    }
}
