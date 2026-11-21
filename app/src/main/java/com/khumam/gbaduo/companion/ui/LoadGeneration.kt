package com.khumam.gbaduo.companion.ui

import java.util.concurrent.atomic.AtomicInteger

/** Tokens for async loads: only the latest one may touch the screen, so a slow answer for an earlier game is dropped. */
class LoadGeneration {
    private val current = AtomicInteger()

    fun next(): Int = current.incrementAndGet()

    fun isCurrent(token: Int): Boolean = current.get() == token
}
