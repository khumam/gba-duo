package com.khumam.gbaduo.player

import com.khumam.gbaduo.storage.GameStorage
import java.io.File

/** Save state slots, stored in the game's folder: `<game dir>/states/slot<N>.ss`. */
class SaveStates(private val storage: GameStorage) {

    fun slotPath(slot: Int): String = storage.ensureDirs().stateFile(slot).absolutePath

    fun slotExists(slot: Int): Boolean = storage.stateFile(slot).exists()

    fun slotModified(slot: Int): Long = storage.stateFile(slot).lastModified()

    /** The state made just before a companion pack first changed the game, if there is one. */
    val beforePackWrite: File? get() = storage.beforePackWriteState.takeIf { it.exists() }
}
