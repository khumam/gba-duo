package com.khumam.gbaduo.storage

import java.io.File

/**
 * The save folder as a whole: it holds the same `<platform>/<game folder>` tree as
 * private storage's `files/games` (game.sav, states/ and pack.json only; packs and the
 * ROM index stay private). Pure, no Android: the picked folder comes in as a [SyncDir].
 */
object SaveLibrary {

    /**
     * The game's folder inside the save folder: found by ROM CRC like [GameStorage.forGame]
     * (the title part may differ between devices), else named like the private one.
     * Null when it doesn't exist and [create] is false, or can't be created.
     */
    fun gameDir(root: SyncDir, platformId: String, folderName: String, create: Boolean): SyncDir? {
        val platform = root.subdir(platformId, create) ?: return null
        val existing = platform.entries().firstOrNull { it.isDir && GameStorage.sameGame(folderName, it.name) }
        return platform.subdir(existing?.name ?: folderName, create)
    }

    /** One game's sync, private copy <-> save folder. */
    fun syncGame(storage: GameStorage, root: SyncDir, rootId: String, direction: SaveSync.Direction): SaveSync.Result? {
        val remote = gameDir(root, storage.platformId, storage.folderName, create = true) ?: return null
        return SaveSync(FileSyncDir(storage.dir), remote, rootId).sync(direction)
    }

    data class MoveResult(val games: Int, val failed: List<String>) {
        val ok get() = failed.isEmpty()
    }

    /**
     * Copies every game folder in [gamesDir] (private `files/games`) into the save folder,
     * then checks each one reads back the same. Only when this returns [MoveResult.ok]
     * should the app switch to the folder; the private copies are never touched apart
     * from taking in newer saves already in the folder (from another device).
     */
    fun moveAll(gamesDir: File, root: SyncDir, rootId: String): MoveResult {
        var games = 0
        val failed = mutableListOf<String>()
        for (platform in gamesDir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            for (game in platform.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                games++
                val label = "${platform.name}/${game.name}"
                val ok = runCatching {
                    val remote = gameDir(root, platform.name, game.name, create = true) ?: return@runCatching false
                    val local = FileSyncDir(game)
                    val result = SaveSync(local, remote, rootId).sync(SaveSync.Direction.BOTH)
                    result.ok && SaveSync.sameContents(local, remote)
                }.getOrDefault(false)
                if (!ok) failed += label
            }
        }
        return MoveResult(games, failed)
    }
}
