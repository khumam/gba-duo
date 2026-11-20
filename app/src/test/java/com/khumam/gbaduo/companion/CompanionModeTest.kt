package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionModeTest {
    @Test
    fun unsetOrUnknownIsActive() {
        assertEquals(CompanionMode.ACTIVE, CompanionMode.byId(null))
        assertEquals(CompanionMode.ACTIVE, CompanionMode.byId("nonsense"))
    }

    @Test
    fun idsRoundTrip() {
        for (mode in CompanionMode.entries) assertEquals(mode, CompanionMode.byId(mode.id))
    }
}
