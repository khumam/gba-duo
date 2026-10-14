package com.khumam.gbaduo.storage

import com.khumam.gbaduo.companion.PackAssignments
import java.io.File

/**
 * Moves one ROM's data from the pre-[GameStorage] layout into its game folder:
 * - `files/saves/<rom name minus extension>.sav`  -> `game.sav`
 * - `files/states/<crc32 lowercase hex>/slotN.ss` -> `states/slotN.ss`
 * - its entry in `files/pack_assignments.json`    -> `pack.json`
 *
 * Idempotent (safe to run on every launch) and never destructive: an old file is
 * only removed after it was moved successfully, and never overwrites a file that
 * already exists in the new layout. Pure, no Android deps.
 */
object LegacyMigration {

    data class Result(val movedSave: Boolean, val movedStates: Int, val movedPack: Boolean)

    fun legacySaveName(romFileName: String): String = romFileName.substringBeforeLast('.', romFileName) + ".sav"

    fun migrate(filesDir: File, romFileName: String, crc32: Long, storage: GameStorage): Result {
        val movedSave = migrateSave(filesDir, romFileName, storage)
        val movedStates = migrateStates(filesDir, crc32, storage)
        val movedPack = migratePack(filesDir, romFileName, storage)
        return Result(movedSave, movedStates, movedPack)
    }

    private fun migrateSave(filesDir: File, romFileName: String, storage: GameStorage): Boolean {
        val legacy = File(File(filesDir, "saves"), legacySaveName(romFileName))
        if (!legacy.isFile || storage.saveFile.exists()) return false
        storage.ensureDirs()
        return moveFile(legacy, storage.saveFile)
    }

    private fun migrateStates(filesDir: File, crc32: Long, storage: GameStorage): Int {
        val legacyDir = File(File(filesDir, "states"), "%08x".format(crc32 and 0xFFFFFFFFL))
        val files = legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".ss") } ?: return 0
        var moved = 0
        for (file in files) {
            val target = File(storage.statesDir, file.name)
            if (target.exists()) continue
            storage.ensureDirs()
            if (moveFile(file, target)) moved++
        }
        if (legacyDir.listFiles()?.isEmpty() == true) legacyDir.delete()
        return moved
    }

    private fun migratePack(filesDir: File, romFileName: String, storage: GameStorage): Boolean {
        val legacyFile = File(filesDir, "pack_assignments.json")
        if (!legacyFile.exists()) return false
        val legacy = runCatching { PackAssignments(legacyFile) }.getOrNull() ?: return false
        val packId = runCatching { legacy.get(romFileName) }.getOrNull() ?: return false
        if (storage.packId == null) storage.packId = packId
        legacy.set(romFileName, null)
        return true
    }

    /** Rename when possible; otherwise copy, verify the size, then delete the source. */
    internal fun moveFile(source: File, target: File): Boolean {
        if (target.exists()) return false
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return try {
            source.copyTo(target, overwrite = false)
            if (target.length() != source.length()) {
                target.delete()
                false
            } else {
                source.delete()
                true
            }
        } catch (e: Exception) {
            target.delete()
            false
        }
    }
}
