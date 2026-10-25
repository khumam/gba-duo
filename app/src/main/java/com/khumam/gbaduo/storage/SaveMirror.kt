package com.khumam.gbaduo.storage

import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One play session's link between the game's private folder and the save folder.
 * The game always runs on the private copy; this copies it out as it changes.
 *
 * - [syncIn] before the core loads: takes in newer saves from the folder (another device).
 * - [requestOut] after the game saves, after a save state, on pause: copies out, on one
 *   background thread, merging requests that pile up.
 * - [finish] on quit: one last copy out.
 *
 * Every step is skipped quietly when the folder can't be reached (SD card out); the
 * game plays on from the private copy and [onUnavailable] fires once.
 */
class SaveMirror private constructor(
    private val context: Context,
    private val storage: GameStorage,
    private val rootId: String,
    private val romFileName: String,
) {
    /** Called once per session, on a background thread, if the folder can't be reached. */
    @Volatile var onUnavailable: (() -> Unit)? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "gbaduo-save-mirror") }
    private val outQueued = AtomicBoolean(false)
    private val warned = AtomicBoolean(false)

    /** Blocking. False when the folder couldn't be reached (the private copy is used as is). */
    fun syncIn(): Boolean = run(SaveSync.Direction.BOTH)

    /** Copies changes out soon, off the caller's thread. */
    fun requestOut() {
        if (!outQueued.compareAndSet(false, true)) return
        runCatching {
            executor.execute {
                outQueued.set(false)
                run(SaveSync.Direction.OUT)
            }
        }.onFailure { outQueued.set(false) }
    }

    /** One last copy out, then the thread stops. Waits up to [waitMs] for it. */
    fun finish(waitMs: Long = 3000) {
        runCatching { executor.execute { run(SaveSync.Direction.OUT) } }
        executor.shutdown()
        runCatching { executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS) }
    }

    private fun run(direction: SaveSync.Direction): Boolean {
        val root = SaveFolder.open(context)
        if (root == null) {
            if (warned.compareAndSet(false, true)) onUnavailable?.invoke()
            return false
        }
        val result = runCatching { SaveLibrary.syncGame(storage, root, rootId, direction, romFileName) }
            .onFailure { Log.w(TAG, "sync failed", it) }.getOrNull()
        if (result == null) {
            if (warned.compareAndSet(false, true)) onUnavailable?.invoke()
            return false
        }
        if (!result.ok) Log.w(TAG, "not synced: ${result.failed}")
        if (result.conflicts.isNotEmpty()) Log.i(TAG, "conflicts kept as copies: ${result.conflicts}")
        return result.ok
    }

    companion object {
        private const val TAG = "SaveMirror"

        /** A mirror for this game, or null when no save folder is set. */
        fun forGame(context: Context, storage: GameStorage, romFileName: String): SaveMirror? {
            val uri = SaveFolder.uri(context) ?: return null
            return SaveMirror(context.applicationContext, storage, uri.toString(), romFileName)
        }
    }
}
