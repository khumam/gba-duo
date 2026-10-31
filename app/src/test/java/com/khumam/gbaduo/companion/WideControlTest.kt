package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoMemory : MemoryReader {
    override fun u8(addr: Long) = 0
    override fun u16(addr: Long) = 0
    override fun u32(addr: Long) = 0L
}

class WideControlTest {
    private val manifest = PackManifest.parse(
        """{"id":"demo","name":"Demo","version":"1.0.0","author":"a","formatVersion":1,
            "match":{"gameCode":["BPRE"],"crc32":[]},"tickHz":4,"layout":"layout.json","values":{}}""",
    )

    private fun snapshot(wide: WideState) = CompanionSnapshot.build(manifest, NoMemory, 7L, scriptLoaded = false, wide = wide)

    @Test
    fun snapshotCarriesTheWideViewState() {
        val on = snapshot(WideState(enabled = true, available = true))
        assertEquals(1L, on["_wide"])
        assertEquals("ON", on["_wideLabel"])
        val off = snapshot(WideState(enabled = false, available = true))
        assertEquals(0L, off["_wide"])
        assertEquals("OFF", off["_wideLabel"])
    }

    @Test
    fun aPackWithoutAWideViewReadsAsNotAvailable() {
        val none = snapshot(WideState(enabled = true, available = false))
        assertEquals(0L, none["_wide"])
        assertEquals("N/A", none["_wideLabel"])
    }

    @Test
    fun theWideCallNamesAreReserved() {
        assertEquals("wideView", WideControl.CALL)
        assertTrue(WideControl.handles("wideView"))
        assertFalse(WideControl.handles("equip"))
    }

    @Test
    fun toggleOnOffAndTheDefault() {
        assertTrue(WideControl.next(current = false, args = listOf("toggle")))
        assertFalse(WideControl.next(current = true, args = listOf("toggle")))
        assertTrue(WideControl.next(current = false, args = emptyList()))   // no argument: toggle
        assertTrue(WideControl.next(current = true, args = listOf("on")))
        assertFalse(WideControl.next(current = true, args = listOf("off")))
        assertTrue(WideControl.next(current = false, args = listOf("garbage")) == true) // unknown word: toggle
    }
}
