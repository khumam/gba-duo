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
    fun extraWideShowsAsExtraAndKeepsWideOn() {
        val extra = snapshot(WideState(WideMode.EXTRA, available = true))
        assertEquals(1L, extra["_wide"])
        assertEquals(2L, extra["_wideMode"])
        assertEquals("XL", extra["_wideLabel"])
        assertEquals(1L, snapshot(WideState(WideMode.WIDE, available = true))["_wideMode"])
        assertEquals(0L, snapshot(WideState(WideMode.EXTRA, available = false))["_wideMode"])
    }

    @Test
    fun namedModesAndTheToggleCycle() {
        assertEquals(WideMode.WIDE, WideControl.next(WideMode.OFF, listOf("on")))
        assertEquals(WideMode.OFF, WideControl.next(WideMode.EXTRA, listOf("off")))
        assertEquals(WideMode.EXTRA, WideMode.OFF.let { WideControl.next(it, listOf("extra")) })
        assertEquals(WideMode.WIDE, WideControl.next(WideMode.OFF, listOf("toggle")))
        assertEquals(WideMode.EXTRA, WideControl.next(WideMode.WIDE, listOf("toggle")))
        assertEquals(WideMode.OFF, WideControl.next(WideMode.EXTRA, listOf("toggle")))
        assertEquals(WideMode.WIDE, WideControl.next(WideMode.OFF, emptyList()))      // no argument: toggle
        assertEquals(WideMode.WIDE, WideControl.next(WideMode.OFF, listOf("garbage"))) // unknown word: toggle
    }
}
