package com.khumam.gbaduo.achievements

import com.khumam.gbaduo.emu.NativeCore

/** JNI bindings to achievements.c (the rcheevos rc_client). Use through [RetroAchievements]. */
internal object RaNative {
    init {
        NativeCore // loads libgbaduo
    }

    external fun init(): Boolean
    external fun userAgentClause(): String
    external fun loginWithPassword(username: String, password: String)
    external fun loginWithToken(username: String, token: String)
    external fun logout()

    /** Emu thread, after NativeCore.load: hashes the ROM and starts a session. */
    external fun loadGame(): Boolean
    external fun unloadGame()

    /** Emu thread, after every emulated frame. */
    external fun doFrame()

    /** While paused: keeps the session alive and retries queued unlocks. */
    external fun idle()

    /** Answers a [RetroAchievements.serverCall]; [body] null = failed / timed out. */
    external fun serverResponse(handle: Long, body: ByteArray?, status: Int)
}
