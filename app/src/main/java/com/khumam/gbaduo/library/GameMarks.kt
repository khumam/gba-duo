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

    private const val KEY_PLAYTIME = "playtime_ms"
    private const val KEY_LAST_TITLE = "last_title"
    private const val KEY_GAME_COUNT = "game_count"

    /** Adds a finished stretch of play to the running total shown on the second screen's clock. */
    fun addPlaytime(context: Context, millis: Long) {
        if (millis <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_PLAYTIME, prefs.getLong(KEY_PLAYTIME, 0L) + millis).apply()
    }

    fun setLastTitle(context: Context, title: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_TITLE, title).apply()
    }

    fun setGameCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GAME_COUNT, count).apply()
    }

    fun stats(context: Context): PlayStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PlayStats(
            games = prefs.getInt(KEY_GAME_COUNT, 0),
            playtimeMs = prefs.getLong(KEY_PLAYTIME, 0L),
            lastTitle = prefs.getString(KEY_LAST_TITLE, null),
        )
    }
}

/** What the second screen's clock shows about the library. */
data class PlayStats(val games: Int, val playtimeMs: Long, val lastTitle: String?) {
    companion object {
        /** "0m", "42m", "3h 05m", "120h": minutes under an hour, then hours and minutes, no minutes once past 100 hours. */
        fun formatPlaytime(millis: Long): String {
            val minutes = (millis.coerceAtLeast(0) / 60_000).toInt()
            val hours = minutes / 60
            return when {
                hours == 0 -> "${minutes}m"
                hours >= 100 -> "${hours}h"
                else -> "${hours}h ${"%02d".format(minutes % 60)}m"
            }
        }
    }
}
