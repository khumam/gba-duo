package com.khumam.gbaduo.library

/** The home screen's platform filter: all games, then GBA, GBC, GB, and round again. */
object PlatformFilter {
    /** `null` is "all games". */
    private val ORDER = listOf<Platform?>(null, Platform.GBA, Platform.GBC, Platform.GB)

    fun apply(entries: List<RomEntry>, filter: Platform?): List<RomEntry> =
        if (filter == null) entries else entries.filter { it.platform == filter }

    /** The filter [direction] steps from [current], skipping platforms with no games. */
    fun cycle(current: Platform?, available: Set<Platform>, direction: Int): Platform? {
        val order = ORDER.filter { it == null || it in available }
        val from = order.indexOf(current).coerceAtLeast(0)
        return order[Math.floorMod(from + direction, order.size)]
    }

    /** [current] if it still has games, otherwise all games. */
    fun valid(current: Platform?, available: Set<Platform>): Platform? =
        current?.takeIf { it in available }
}
