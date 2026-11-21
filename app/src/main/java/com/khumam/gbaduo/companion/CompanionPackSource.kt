package com.khumam.gbaduo.companion

import java.io.File

/** When the web catalog was last fetched (null: never, nothing saved) and whether the latest fetch failed. */
data class CatalogInfo(val updatedAt: Long?, val offline: Boolean)

/**
 * What the second-screen carousel needs: the cards for a game (installed packs plus the web catalog, which is
 * cached so scrolling the shelf doesn't refetch it) and the actions behind them. Blocking; call off the main thread.
 */
class CompanionPackSource(
    private val store: PackStore,
    private val downloader: PackDownloader,
    private val packsDir: File,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the catalog is kept between runs; null keeps it in memory only. */
    private val cacheFile: File? = null,
) {
    private var cached: List<CatalogPack>? = null
    private var cachedAt = 0L
    private var forceFetch = false
    private var offline = false

    fun cards(gameCode: String, crc32: Long, assignedId: String?): List<CompanionCard> {
        val web = PackCatalog.forGame(catalog(), gameCode)
        return CompanionCards.build(store.matching(gameCode, crc32), web, assignedId, appVersion) { store.overviewImage(it)?.toURI()?.toString() }
    }

    /** The next [cards] fetches the catalog again. What was saved stays as the fallback if the fetch fails. */
    @Synchronized
    fun refresh() {
        forceFetch = true
    }

    /** How fresh the catalog behind the last [cards] is, for the status line. */
    @Synchronized
    fun info(): CatalogInfo = CatalogInfo(updatedAt = cachedAt.takeIf { cached != null }, offline = offline)

    /** The pack id to assign for an installed card. */
    fun resolve(card: CompanionCard.Installed): String = card.pack.id

    fun resolve(card: CompanionCard.Web): Fetch<PackManifest> = downloader.install(card.pack)

    /** Installs a user-picked zip. Throws [PackInstaller.InstallException]. */
    fun importZip(zip: File): PackManifest = PackInstaller.install(zip, packsDir)

    /** Fresh copy in memory, else fresh copy on disk, else the web; when the web fails, whatever old copy there is. */
    @Synchronized
    private fun catalog(): List<CatalogPack> {
        if (!forceFetch) {
            cached?.takeIf { now() - cachedAt < CACHE_MS }?.let { return it }
            readSaved()?.takeIf { now() - it.second < CACHE_MS }?.let { (list, at) ->
                cached = list
                cachedAt = at
                offline = false
                return list
            }
        }
        val fetched = downloader.catalogJson()
        if (fetched is Fetch.Ok) {
            val parsed = downloader.parseCatalog(fetched.value)
            if (parsed is Fetch.Ok) {
                forceFetch = false
                offline = false
                cached = parsed.value
                cachedAt = now()
                save(fetched.value)
                return parsed.value
            }
        }
        forceFetch = false
        offline = true
        if (cached == null) readSaved()?.let { (list, at) ->
            cached = list
            cachedAt = at
        }
        return cached ?: emptyList()
    }

    private fun save(json: String) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("${now()}\n$json")
        }
    }

    /** The saved catalog and when it was saved, or null if there is none or it is unreadable. */
    private fun readSaved(): Pair<List<CatalogPack>, Long>? {
        val file = cacheFile?.takeIf { it.isFile } ?: return null
        return runCatching {
            val text = file.readText()
            val at = text.substringBefore('\n').toLong()
            val parsed = downloader.parseCatalog(text.substringAfter('\n')) as Fetch.Ok
            parsed.value to at
        }.getOrNull()
    }

    private companion object {
        const val CACHE_MS = 6 * 60 * 60_000L
    }
}
