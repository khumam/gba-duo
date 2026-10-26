package com.khumam.gbaduo.library.artwork

import android.content.Context
import com.khumam.gbaduo.library.GameTitle
import com.khumam.gbaduo.library.Platform
import com.khumam.gbaduo.library.RomEntry
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.storage.GameSettingsKeys
import com.khumam.gbaduo.storage.GameStorage
import java.io.File

/** Wires the pure artwork code to the app: the cache, the folder, the key, the network. */
object Artwork {
    private const val MAX_FILE = 16 * 1024 * 1024

    fun store(context: Context) = ArtworkStore(File(context.filesDir, "artwork"))

    fun target(library: RomLibrary, entry: RomEntry) =
        ScrapeTarget(entry.platform, entry.fileName, GameTitle.display(entry.fileName, entry.title), entry.gameCode) { library.crc32(entry) }

    /** Reads the player's lock for a game; the folder is only found, never created. */
    fun lock(context: Context, platform: Platform, crc: Long): String? =
        GameStorage.forGame(context.filesDir, platform, "", "", crc).settings.getString(GameSettingsKeys.ARTWORK)

    /**
     * A scraper over whichever sources are set up: the artwork folder when [useFolder] and one
     * is reachable, SteamGridDB when [useWeb] and a key is set. [respectLocks] false is for the
     * player's own per-game actions, which may override a lock.
     */
    fun scraper(context: Context, useFolder: Boolean, useWeb: Boolean, respectLocks: Boolean = true): ArtworkScraper {
        val folder = if (useFolder) folderSource(context) else null
        val web = if (useWeb) webSource(context) else null
        return ArtworkScraper(
            store(context), folder, web,
            lock = { platform, crc -> if (respectLocks) lock(context, platform, crc) else null },
            encode = ArtworkImages::encode,
        )
    }

    private fun folderSource(context: Context): ((ScrapeTarget, Long) -> Found?)? {
        val dir = ArtworkPrefs.openFolder(context) ?: return null
        val index by lazy { ArtworkFolderIndex.build(dir) }
        return { target, crc ->
            index.match(target.fileName, target.title, target.gameCode, crc, target.platform)?.let { image ->
                val bytes = image.open(dir).use { it.readBytes() }
                if (bytes.size > MAX_FILE) null else Found(bytes, "folder", image.path)
            }
        }
    }

    private fun webSource(context: Context): ((ScrapeTarget) -> Sgdb<Found>)? {
        val key = ArtworkPrefs.sgdbKey(context) ?: return null
        val api = SteamGridDb(key, UrlHttp())
        return { target ->
            when (val cover = api.cover(target.title)) {
                is Sgdb.Failed -> cover
                is Sgdb.Ok -> when (val image = api.download(cover.value.url)) {
                    is Sgdb.Failed -> image
                    is Sgdb.Ok -> Sgdb.Ok(Found(image.value, "steamgriddb", cover.value.id.toString()))
                }
            }
        }
    }
}
