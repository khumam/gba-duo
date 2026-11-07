package com.khumam.gbaduo.companion

import org.junit.Assert.assertFalse
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
}
