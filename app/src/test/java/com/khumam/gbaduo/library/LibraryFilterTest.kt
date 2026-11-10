package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {
    private fun rom(name: String, code: String, p: Platform) = RomEntry(null, name, name, code, p)

    private val gba = rom("a", "BPRE", Platform.GBA)
    private val gbc = rom("b", "AAAA", Platform.GBC)
    private val gb = rom("c", "CCCC", Platform.GB)
    private val entries = listOf(gba, gbc, gb)
    private val packs = setOf("BPRE", "CCCC")
    private val of = LibraryFilter::OfPlatform

    @Test fun forwardCyclesAllPacksGbaGbcGb() {
        var f: LibraryFilter = LibraryFilter.All
        val seen = mutableListOf<LibraryFilter>()
        repeat(6) { f = ShelfFilter.cycle(f, entries, packs, 1); seen += f }
        assertEquals(
            listOf(LibraryFilter.Packs, of(Platform.GBA), of(Platform.GBC), of(Platform.GB), LibraryFilter.All, LibraryFilter.Packs),
            seen,
        )
    }

    @Test fun backwardWrapsFromAllToGb() {
        assertEquals(of(Platform.GB), ShelfFilter.cycle(LibraryFilter.All, entries, packs, -1))
    }

    @Test fun skipsPacksWhenNoGameHasOne() {
        assertEquals(of(Platform.GBA), ShelfFilter.cycle(LibraryFilter.All, entries, emptySet(), 1))
    }

    @Test fun skipsPlatformsWithoutGames() {
        val gbaGb = listOf(gba, gb)
        assertEquals(of(Platform.GB), ShelfFilter.cycle(of(Platform.GBA), gbaGb, emptySet(), 1))
    }

    @Test fun packsShowsGamesWithPackAnyPlatform() {
        assertEquals(listOf(gba, gb), ShelfFilter.apply(entries, LibraryFilter.Packs, packs))
    }

    @Test fun packCodeMatchIgnoresCase() {
        assertEquals(listOf(gba), ShelfFilter.apply(listOf(rom("x", "bpre", Platform.GBA)).let { entries }, LibraryFilter.Packs, setOf("BPRE")))
    }

    @Test fun staleFilterFallsBackToAll() {
        assertEquals(LibraryFilter.All, ShelfFilter.valid(of(Platform.GBC), listOf(gba), packs))
        assertEquals(LibraryFilter.All, ShelfFilter.valid(LibraryFilter.Packs, listOf(gbc), packs))
        assertEquals(LibraryFilter.Packs, ShelfFilter.valid(LibraryFilter.Packs, entries, packs))
    }

    @Test fun idsRoundTrip() {
        for (f in listOf(LibraryFilter.All, LibraryFilter.Packs, of(Platform.GBC))) assertEquals(f, LibraryFilter.byId(f.id))
        assertEquals(LibraryFilter.All, LibraryFilter.byId("junk"))
    }
}
