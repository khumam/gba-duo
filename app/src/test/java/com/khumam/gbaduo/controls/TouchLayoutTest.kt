package com.khumam.gbaduo.controls

import com.khumam.gbaduo.emu.GbaKeys
import com.khumam.gbaduo.emu.InputMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLayoutTest {
    private val l = TouchLayout(1920f, 1080f, 3f)

    @Test fun faceButtonsHitTheirCentres() {
        assertEquals(GbaKeys.A, l.hit(l.a.cx, l.a.cy))
        assertEquals(GbaKeys.B, l.hit(l.b.cx, l.b.cy))
        assertEquals(TouchLayout.MENU, l.hit(l.menu.cx, l.menu.cy))
    }

    @Test fun pillsHitTheirCentres() {
        assertEquals(GbaKeys.L, l.hit(l.l.cx, l.l.cy))
        assertEquals(GbaKeys.R, l.hit(l.rShoulder.cx, l.rShoulder.cy))
        assertEquals(GbaKeys.SELECT, l.hit(l.select.cx, l.select.cy))
        assertEquals(GbaKeys.START, l.hit(l.start.cx, l.start.cy))
    }

    @Test fun dpadIsEightWay() {
        val d = l.dpadR * 0.8f
        val diag = d * 0.7071f
        assertEquals(GbaKeys.RIGHT, l.hit(l.dpadX + d, l.dpadY))
        assertEquals(GbaKeys.LEFT, l.hit(l.dpadX - d, l.dpadY))
        assertEquals(GbaKeys.UP, l.hit(l.dpadX, l.dpadY - d))
        assertEquals(GbaKeys.DOWN, l.hit(l.dpadX, l.dpadY + d))
        assertEquals(GbaKeys.UP or GbaKeys.RIGHT, l.hit(l.dpadX + diag, l.dpadY - diag))
        assertEquals(GbaKeys.DOWN or GbaKeys.LEFT, l.hit(l.dpadX - diag, l.dpadY + diag))
    }

    @Test fun deadZonesPressNothing() {
        assertEquals(0, l.hit(l.dpadX, l.dpadY))              // dead centre of the D-pad
        assertEquals(0, l.hit(l.width / 2, l.height / 2))    // middle of the game picture
    }

    @Test fun everythingFitsOnScreenAndNothingOverlaps() {
        val all = l.rounds.map { Triple(it.cx - it.r, it.cy - it.r, it.cx + it.r to it.cy + it.r) } +
            l.pills.map { Triple(it.left, it.top, it.right to it.bottom) }
        for ((left, top, rb) in all) {
            assertTrue(left >= 0 && top >= 0 && rb.first <= l.width && rb.second <= l.height)
        }
        // Each button's own centre hits only that button.
        for (b in l.rounds) assertEquals(b.bits, l.hit(b.cx, b.cy))
    }

    @Test fun touchKeysAddToPhysicalKeys() {
        val mapper = InputMapper()
        mapper.touchKeys = GbaKeys.A or GbaKeys.UP
        assertEquals(GbaKeys.A or GbaKeys.UP, mapper.keys())
        mapper.touchKeys = 0
        assertEquals(0, mapper.keys())
    }
}
