package com.khumam.gbaduo.companion

/** One card in the second-screen companion carousel. */
sealed interface CompanionCard {
    /** [overview] and [description]: from the catalog entry with this pack's id, when the catalog is reachable and has one. */
    data class Installed(
        val pack: PackManifest,
        val assigned: Boolean,
        val overview: String? = null,
        val description: String? = null,
        /** Mapped for another region of this game: assigning it asks first. */
        val otherRegion: Boolean = false,
    ) : CompanionCard

    /** A catalog pack to download; [update] when a (older) copy is already installed. */
    data class Web(val pack: CatalogPack, val update: Boolean, val otherRegion: Boolean = false) : CompanionCard {
        val overview: String? get() = pack.overview
    }

    /** The last card: pick a pack zip from storage. */
    data object Import : CompanionCard
}

object CompanionCards {

    /**
     * Installed packs, then web packs worth downloading, then Import. [installed] and [web] are
     * already filtered to the game. A web pack with a newer version replaces its installed card.
     */
    fun build(
        installed: List<PackManifest>,
        web: List<CatalogPack>,
        assignedId: String?,
        appVersion: String,
        /** The picture a pack carries itself (its `overview` folder), as a location; it wins over the catalog's. */
        localOverview: (PackManifest) -> String? = { null },
        /** Packs for another region of the same game, listed after the exact ones. */
        installedOtherRegion: List<PackManifest> = emptyList(),
        webOtherRegion: List<CatalogPack> = emptyList(),
    ): List<CompanionCard> {
        val allInstalled = installed + installedOtherRegion.filter { o -> installed.none { it.id == o.id } }
        val otherInstalledIds = installedOtherRegion.map { it.id }.toSet() - installed.map { it.id }.toSet()
        val allWeb = web + webOtherRegion.filter { o -> web.none { it.id == o.id } }
        val otherWebIds = webOtherRegion.map { it.id }.toSet() - web.map { it.id }.toSet()
        val installedVersions = allInstalled.associate { it.id to it.version }
        val downloads = allWeb.mapNotNull { pack ->
            val other = pack.id in otherWebIds
            when (PackCatalog.status(pack, installedVersions[pack.id], appVersion)) {
                CatalogStatus.NEW -> CompanionCard.Web(pack, update = false, otherRegion = other)
                CatalogStatus.UPDATE -> CompanionCard.Web(pack, update = true, otherRegion = other)
                CatalogStatus.INSTALLED, CatalogStatus.NEEDS_NEWER_APP -> null
            }
        }
        val replaced = downloads.filter { it.update }.map { it.pack.id }.toSet()
        val catalog = allWeb.associateBy { it.id }
        val cards = allInstalled.filter { it.id !in replaced }.map {
            CompanionCard.Installed(it, it.id == assignedId, localOverview(it) ?: catalog[it.id]?.overview, catalog[it.id]?.description?.ifBlank { null }, it.id in otherInstalledIds)
        }
        return cards + downloads + CompanionCard.Import
    }
}
