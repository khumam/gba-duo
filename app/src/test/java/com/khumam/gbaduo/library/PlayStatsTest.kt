package com.khumam.gbaduo.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStatsTest {
    @Test fun underAnHourIsMinutes() {
        assertEquals("0m", PlayStats.formatPlaytime(0))
        assertEquals("42m", PlayStats.formatPlaytime(42 * 60_000L))
    }

    @Test fun overAnHourHasHoursAndPaddedMinutes() {
        assertEquals("3h 05m", PlayStats.formatPlaytime((3 * 60 + 5) * 60_000L))
    }

    @Test fun pastHundredHoursDropsMinutes() {
        assertEquals("120h", PlayStats.formatPlaytime((120 * 60 + 30) * 60_000L))
    }

    @Test fun negativeIsZero() {
        assertEquals("0m", PlayStats.formatPlaytime(-5))
    }
}
