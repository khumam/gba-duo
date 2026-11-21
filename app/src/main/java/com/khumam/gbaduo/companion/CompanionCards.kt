package com.khumam.gbaduo.companion

/** One card in the second-screen companion carousel. */
sealed interface CompanionCard {
    /** [overview] and [description]: from the catalog entry with this pack's id, when the catalog is reachable and has one. */
    data class Installed(
        val pack: PackManifest,
        val assigned: Boolean,
        val overview: String? = null,
        val description: String? = null,
    ) : CompanionCard

    /** A catalog pack to download; [update] when a (older) copy is already installed. */
    data class Web(val pack: CatalogPack, val update: Boolean) : CompanionCard {
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
    ): List<CompanionCard> {
        val installedVersions = installed.associate { it.id to it.version }
        val downloads = web.mapNotNull { pack ->
            when (PackCatalog.status(pack, installedVersions[pack.id], appVersion)) {
                CatalogStatus.NEW -> CompanionCard.Web(pack, update = false)
                CatalogStatus.UPDATE -> CompanionCard.Web(pack, update = true)
                CatalogStatus.INSTALLED, CatalogStatus.NEEDS_NEWER_APP -> null
            }
        }
        val replaced = downloads.filter { it.update }.map { it.pack.id }.toSet()
        val catalog = web.associateBy { it.id }
        val cards = installed.filter { it.id !in replaced }.map { CompanionCard.Installed(it, it.id == assignedId, localOverview(it) ?: catalog[it.id]?.overview, catalog[it.id]?.description?.ifBlank { null }) }
        return cards + downloads + CompanionCard.Import
    }
}
