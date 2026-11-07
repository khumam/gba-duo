package com.khumam.gbaduo.storage

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replaces a game's `game.sav` with a save file the player picked. Pure, no Android.
 *
 * The current save is copied to `game.sav.bak-<time>` first (in the game's private folder,
 * and in the save folder's copy of the game if one is set), so an import can be undone by
 * hand. The new bytes go in through a temp file, so a failure leaves the old save in place.
 * With a save folder, the usual sync then carries the new save there.
 */
object SaveImport {
    /** Real saves are 512 B to 128 KB; anything much bigger is not one. */
    const val MAX_SIZE = 1024 * 1024

    sealed interface Result {
        /** [backups]: names of the backup files made. [synced]: false if the save folder couldn't take the new save yet. */
        data class Done(val backups: List<String>, val synced: Boolean) : Result
        data object Invalid : Result
        data class Failed(val error: String) : Result
    }

    fun isValid(bytes: ByteArray) = bytes.isNotEmpty() && bytes.size <= MAX_SIZE

    /**
     * Up to [MAX_SIZE] + 1 bytes of [input]: enough for [isValid] to see a file that is too big without reading all of it.
     * (`InputStream.readNBytes` would do this, but it is API 33 and the app runs on 30.)
     */
    fun readCapped(input: InputStream): ByteArray {
        val limit = MAX_SIZE + 1
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * @param remote this game's folder inside the save folder, or null when no save folder is used.
     * @param rootId identifies the save folder, for [SaveSync].
     */
    fun import(
        gameDir: File, bytes: ByteArray, remote: SyncDir?, rootId: String, now: Long = System.currentTimeMillis(),
    ): Result {
        if (!isValid(bytes)) return Result.Invalid
        val target = File(gameDir, SaveSync.SAVE)
        val backupName = "${SaveSync.SAVE}.bak-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}"
        val backups = mutableListOf<String>()
        try {
            gameDir.mkdirs()
            if (target.exists()) {
                target.copyTo(File(gameDir, backupName), overwrite = true)
                backups += backupName
            }
            if (remote != null && remote.entry(SaveSync.SAVE)?.isDir == false) {
                remote.openRead(SaveSync.SAVE).use { i -> remote.openWrite(backupName).use { i.copyTo(it) } }
                backups += "$backupName (save folder)"
            }
            val tmp = File(gameDir, "${SaveSync.SAVE}.partial")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IOException("can't replace ${SaveSync.SAVE}")
                }
            }
        } catch (e: IOException) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {   // the save folder (SAF) throws SecurityException and the like, not IOException
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        val synced = remote == null || runCatching { SaveSync(FileSyncDir(gameDir), remote, rootId).sync(SaveSync.Direction.BOTH).ok }.getOrDefault(false)
        return Result.Done(backups, synced)
    }
}
