package com.khumam.gbaduo.emu

import com.khumam.gbaduo.emu.EmuThread.WriteAccess
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScriptAccessTest {

    @Test
    fun consent_gates_frame_writes() {
        assertTrue(GameScriptAccess.mayWrite(WriteAccess.ALLOWED))
        assertFalse(GameScriptAccess.mayWrite(WriteAccess.ASK))
        assertFalse(GameScriptAccess.mayWrite(WriteAccess.NONE))
    }

    @Test
    fun asksOnceWhenAFrameHookWaitsOnAnUndecidedPack() {
        assertTrue(GameScriptAccess.needsConsent(WriteAccess.ASK, hasFrameHook = true, alreadyAsked = false))
        assertFalse(GameScriptAccess.needsConsent(WriteAccess.ASK, hasFrameHook = true, alreadyAsked = true))
    }

    @Test
    fun neverAsksWhenNothingCouldWrite() {
        assertFalse(GameScriptAccess.needsConsent(WriteAccess.ASK, hasFrameHook = false, alreadyAsked = false))
        assertFalse(GameScriptAccess.needsConsent(WriteAccess.NONE, hasFrameHook = true, alreadyAsked = false))
        assertFalse(GameScriptAccess.needsConsent(WriteAccess.ALLOWED, hasFrameHook = true, alreadyAsked = false))
    }
}
