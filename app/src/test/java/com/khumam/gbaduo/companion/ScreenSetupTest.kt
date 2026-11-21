package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSetupTest {

    @Test
    fun aSecondScreenMakesItDual() {
        assertTrue(ScreenSetup.isDual(listOf(4)))
    }

    @Test
    fun onlyTheBuiltInScreenIsSingle() {
        assertFalse(ScreenSetup.isDual(emptyList()))
    }

    @Test
    fun theSecondaryDisplayIsTheFirstOneBesidesMain() {
        assertEquals(4, ScreenSetup.secondaryDisplayId(listOf(0, 4, 5)))
        assertNull(ScreenSetup.secondaryDisplayId(listOf(0)))
        assertNull(ScreenSetup.secondaryDisplayId(emptyList()))
    }
}
