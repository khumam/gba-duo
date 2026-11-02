package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformFilterTest {
    private val all = setOf(Platform.GBA, Platform.GBC, Platform.GB)

    @Test fun forwardCyclesAllGbaGbcGb() {
        var f: Platform? = null
        val seen = mutableListOf<Platform?>()
        repeat(5) { f = PlatformFilter.cycle(f, all, 1); seen += f }
        assertEquals(listOf(Platform.GBA, Platform.GBC, Platform.GB, null, Platform.GBA), seen)
    }

    @Test fun backwardWrapsFromAllToGb() {
        assertEquals(Platform.GB, PlatformFilter.cycle(null, all, -1))
    }

    @Test fun skipsPlatformsWithoutGames() {
        val gbaGb = setOf(Platform.GBA, Platform.GB)
        assertEquals(Platform.GB, PlatformFilter.cycle(Platform.GBA, gbaGb, 1))
        assertEquals(Platform.GBA, PlatformFilter.cycle(null, gbaGb, 1))
    }

    @Test fun onlyOnePlatformToggles() {
        val gba = setOf(Platform.GBA)
        assertEquals(Platform.GBA, PlatformFilter.cycle(null, gba, 1))
        assertEquals(null, PlatformFilter.cycle(Platform.GBA, gba, 1))
    }

    @Test fun staleFilterFallsBackToAll() {
        assertEquals(null, PlatformFilter.valid(Platform.GBC, setOf(Platform.GBA)))
        assertEquals(Platform.GBA, PlatformFilter.valid(Platform.GBA, setOf(Platform.GBA)))
    }
}
