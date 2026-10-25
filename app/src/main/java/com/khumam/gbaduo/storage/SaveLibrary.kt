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

    /**
     * One game's sync, private copy <-> save folder. With [romFileName], a game that has no
     * save anywhere yet first takes in an old `<ROM name>.sav` the player put in the folder
     * (see [importLooseSave]).
     */
    fun syncGame(
        storage: GameStorage, root: SyncDir, rootId: String, direction: SaveSync.Direction, romFileName: String? = null,
    ): SaveSync.Result? {
        val remote = gameDir(root, storage.platformId, storage.folderName, create = true) ?: return null
        if (romFileName != null && direction == SaveSync.Direction.BOTH && !storage.saveFile.exists()) {
            runCatching { importLooseSave(root, storage.platformId, romFileName, remote) }
        }
        return SaveSync(FileSyncDir(storage.dir), remote, rootId).sync(direction)
    }

    /**
     * A save made by another emulator, named `<ROM name>.sav`, that the player moved into
     * the save folder: looked for in its root, then in a folder named after the platform
     * (`gba`, `GBA`...). It's copied into the game's own folder as `game.sav`, so the usual
     * sync brings it in. Only when the game has no `game.sav` there yet, so real progress
     * is never overwritten, and the loose file itself is left as it is.
     * True when one was copied.
     */
    fun importLooseSave(root: SyncDir, platformId: String, romFileName: String, gameDir: SyncDir): Boolean {
        if (gameDir.entry(SaveSync.SAVE) != null) return false
        val wanted = LegacyMigration.legacySaveName(romFileName)
        fun find(dir: SyncDir) = dir.entries().firstOrNull { !it.isDir && it.name.equals(wanted, ignoreCase = true) }?.let { dir to it.name }
        val rootEntries = root.entries()
        val platformDir = rootEntries.firstOrNull { it.isDir && it.name.equals(platformId, ignoreCase = true) }
            ?.let { root.subdir(it.name, create = false) }
        val (dir, name) = find(root) ?: platformDir?.let(::find) ?: return false
        dir.openRead(name).use { input -> gameDir.openWrite(SaveSync.SAVE).use { input.copyTo(it) } }
        return true
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
