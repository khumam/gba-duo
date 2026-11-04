package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientControlTest {
    @Test
    fun theCallNameIsReservedAndSeparate() {
        assertEquals("ambientView", AmbientControl.CALL)
        assertTrue(AmbientControl.handles("ambientView"))
        assertFalse(AmbientControl.handles("wideView"))
        assertFalse(WideControl.handles("ambientView"))
    }

    @Test
    fun namedStatesAndTheToggle() {
        assertTrue(AmbientControl.next(false, listOf("on")))
        assertFalse(AmbientControl.next(true, listOf("off")))
        assertTrue(AmbientControl.next(false, listOf("toggle")))
        assertFalse(AmbientControl.next(true, emptyList()))
        assertFalse(AmbientControl.next(true, listOf("garbage")))
    }

    @Test
    fun snapshotValuesAndLabels() {
        val on = mutableMapOf<String, Any?>().also { AmbientControl.put(it, true) }
        assertEquals(1L, on["_ambient"])
        assertEquals("ON", on["_ambientLabel"])
        val off = mutableMapOf<String, Any?>().also { AmbientControl.put(it, false) }
        assertEquals(0L, off["_ambient"])
        assertEquals("OFF", off["_ambientLabel"])
    }
}
