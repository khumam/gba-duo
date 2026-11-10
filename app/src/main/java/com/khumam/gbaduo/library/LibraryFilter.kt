package com.khumam.gbaduo.library

/** What the home screen shelf shows: all games, games with a companion pack, or one platform. */
sealed interface LibraryFilter {
    val id: String?

    data object All : LibraryFilter { override val id: String? = null }
    data object Packs : LibraryFilter { override val id: String = "packs" }
    data class OfPlatform(val platform: Platform) : LibraryFilter { override val id: String = platform.id }

    companion object {
        fun byId(id: String?): LibraryFilter = when (id) {
            null -> All
            Packs.id -> Packs
            else -> Platform.byId(id)?.let(::OfPlatform) ?: All
        }
    }
}

/** The home screen's filter: all games, packs, GBA, GBC, GB, and round again. */
object ShelfFilter {
    private val ORDER = listOf(
        LibraryFilter.All,
        LibraryFilter.Packs,
        LibraryFilter.OfPlatform(Platform.GBA),
        LibraryFilter.OfPlatform(Platform.GBC),
        LibraryFilter.OfPlatform(Platform.GB),
    )

    /**
     * [packCodes] are the game codes that have a companion pack, installed or in the online
     * catalog (compared upper-case).
     */
    fun apply(entries: List<RomEntry>, filter: LibraryFilter, packCodes: Set<String>): List<RomEntry> = when (filter) {
        LibraryFilter.All -> entries
        LibraryFilter.Packs -> entries.filter { it.gameCode.uppercase() in packCodes }
        is LibraryFilter.OfPlatform -> entries.filter { it.platform == filter.platform }
    }

    /** The filter [direction] steps from [current], skipping filters with no games. */
    fun cycle(current: LibraryFilter, entries: List<RomEntry>, packCodes: Set<String>, direction: Int): LibraryFilter {
        val order = ORDER.filter { it == LibraryFilter.All || apply(entries, it, packCodes).isNotEmpty() }
        val from = order.indexOf(current).coerceAtLeast(0)
        return order[Math.floorMod(from + direction, order.size)]
    }

    /** [current] if it still has games, otherwise all games. */
    fun valid(current: LibraryFilter, entries: List<RomEntry>, packCodes: Set<String>): LibraryFilter =
        current.takeIf { it == LibraryFilter.All || apply(entries, it, packCodes).isNotEmpty() } ?: LibraryFilter.All
}
