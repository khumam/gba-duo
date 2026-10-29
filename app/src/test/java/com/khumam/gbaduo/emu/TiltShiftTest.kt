package com.khumam.gbaduo.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltShiftTest {

    @Test
    fun noChoiceOrUnknownChoiceIsOff() {
        assertEquals(TiltShift.OFF, TiltShift.byId(null))
        assertEquals(TiltShift.OFF, TiltShift.byId("removed_in_a_later_version"))
        assertEquals(TiltShift.STRONG, TiltShift.byId("strong"))
    }

    @Test
    fun idsAreUniqueAndOffDoesNothing() {
        assertEquals(TiltShift.entries.size, TiltShift.entries.map { it.id }.toSet().size)
        assertEquals(0f, TiltShift.OFF.maxRadius, 0f)
    }

    @Test
    fun strongerSettingsBlurMoreAndKeepLessSharp() {
        assertTrue(TiltShift.LIGHT.maxRadius > TiltShift.OFF.maxRadius)
        assertTrue(TiltShift.STRONG.maxRadius > TiltShift.LIGHT.maxRadius)
        assertTrue(TiltShift.STRONG.focus < TiltShift.LIGHT.focus)
        assertTrue(TiltShift.entries.all { it.focus in 0f..1f })
    }
}
