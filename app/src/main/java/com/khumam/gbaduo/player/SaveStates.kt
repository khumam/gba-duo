package com.khumam.gbaduo.player

import android.content.Context
import java.io.File

/** Save state files: `files/states/<rom-crc32-hex>/slot<N>.ss`. */
class SaveStates(private val context: Context, private val crc32: Long) {

    private val dir: File by lazy {
        File(context.filesDir, "states/${"%08x".format(crc32)}").apply { mkdirs() }
    }

    fun slotPath(slot: Int): String = File(dir, "slot$slot.ss").absolutePath

    fun slotExists(slot: Int): Boolean = File(slotPath(slot)).exists()

    fun slotModified(slot: Int): Long = File(slotPath(slot)).lastModified()
}
