package com.khumam.gbaduo.emu

import com.khumam.gbaduo.emu.EmuThread.WriteAccess

/**
 * When a pack's game.lua `frame()` may change the game. Same permission, same player answer
 * as the companion's actions ([WriteAccess]); only the player's "allowed" opens writes, and
 * the native side refuses every write outside a `frame()` call whatever this says.
 */
object GameScriptAccess {

    fun mayWrite(access: WriteAccess): Boolean = access == WriteAccess.ALLOWED

    /** The player is asked (once) only when a `frame()` hook is waiting on an undecided pack. */
    fun needsConsent(access: WriteAccess, hasFrameHook: Boolean, alreadyAsked: Boolean): Boolean =
        hasFrameHook && access == WriteAccess.ASK && !alreadyAsked
}
