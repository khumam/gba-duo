package com.khumam.gbaduo.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeLabelTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test fun `under a minute is just now`() {
        assertEquals(AgeLabel.JustNow, AgeLabel.of(0))
        assertEquals(AgeLabel.JustNow, AgeLabel.of(59_999))
    }

    @Test fun `minutes up to an hour`() {
        assertEquals(AgeLabel.Minutes(1), AgeLabel.of(minute))
        assertEquals(AgeLabel.Minutes(59), AgeLabel.of(59 * minute + 59_000))
    }

    @Test fun `hours up to a day`() {
        assertEquals(AgeLabel.Hours(1), AgeLabel.of(hour))
        assertEquals(AgeLabel.Hours(23), AgeLabel.of(23 * hour + 59 * minute))
    }

    @Test fun `days after that`() {
        assertEquals(AgeLabel.Days(1), AgeLabel.of(day))
        assertEquals(AgeLabel.Days(9), AgeLabel.of(9 * day + 5 * hour))
    }

    @Test fun `a time in the future counts as just now`() {
        assertEquals(AgeLabel.JustNow, AgeLabel.of(-5_000))
    }
}
