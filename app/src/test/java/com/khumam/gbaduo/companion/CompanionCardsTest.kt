package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionCardsTest {

    private fun installed(id: String, version: String = "1.0.0") = PackManifest.parse(
        """{"id":"$id","name":"$id","version":"$version","author":"a","formatVersion":1,
            "match":{"gameCode":["BPRE"],"crc32":[]},"tickHz":4,"layout":"layout.json","values":{}}""",
    )

    private fun web(id: String, version: String = "1.0.0", minApp: String? = null, overview: String? = null, description: String = "") = CatalogPack(
        id = id, title = id, description = description, version = version, author = "a", official = true,
        download = "https://x/$id.zip", sha256 = null, games = listOf(CatalogGame("G", "BPRE")),
        minAppVersion = minApp, canModifyGame = false, overview = overview,
    )

    private fun build(
        installed: List<PackManifest> = emptyList(),
        web: List<CatalogPack> = emptyList(),
        assigned: String? = null,
    ) = CompanionCards.build(installed, web, assigned, appVersion = "0.1.15")

    @Test fun `no web packs gives installed then import with only the assigned one marked`() {
        val cards = build(installed = listOf(installed("a"), installed("b")), assigned = "b")
        assertEquals(
            listOf(
                CompanionCard.Installed(installed("a"), assigned = false),
                CompanionCard.Installed(installed("b"), assigned = true),
                CompanionCard.Import,
            ),
            cards,
        )
    }

    @Test fun `a new web pack sits between installed and import`() {
        val cards = build(installed = listOf(installed("a")), web = listOf(web("w")))
        assertEquals(
            listOf(CompanionCard.Installed(installed("a"), false), CompanionCard.Web(web("w"), update = false), CompanionCard.Import),
            cards,
        )
    }

    @Test fun `a web pack already installed at the same version is not duplicated`() {
        val cards = build(installed = listOf(installed("a")), web = listOf(web("a")))
        assertEquals(listOf(CompanionCard.Installed(installed("a"), false), CompanionCard.Import), cards)
    }

    @Test fun `a newer web version replaces the installed card with an update card`() {
        val cards = build(installed = listOf(installed("a", "1.0.0")), web = listOf(web("a", "1.1.0")))
        assertEquals(listOf(CompanionCard.Web(web("a", "1.1.0"), update = true), CompanionCard.Import), cards)
    }

    @Test fun `a web pack that needs a newer app is left out`() {
        val cards = build(web = listOf(web("w", minApp = "9.0.0")))
        assertEquals(listOf(CompanionCard.Import), cards)
    }

    @Test fun `nothing installed and nothing on the web gives only import`() {
        assertEquals(listOf(CompanionCard.Import), build())
    }

    @Test fun `an installed card takes the overview image from the catalog entry with its id`() {
        val cards = build(installed = listOf(installed("a")), web = listOf(web("a", overview = "https://x/a.png")))
        assertEquals("https://x/a.png", (cards[0] as CompanionCard.Installed).overview)
    }

    @Test fun `a web card carries its overview image`() {
        val cards = build(web = listOf(web("w", overview = "https://x/w.png")))
        assertEquals("https://x/w.png", (cards[0] as CompanionCard.Web).overview)
    }

    @Test fun `an installed card takes the description from the catalog entry with its id`() {
        val cards = build(installed = listOf(installed("a")), web = listOf(web("a", description = "Tracks your party.")))
        assertEquals("Tracks your party.", (cards[0] as CompanionCard.Installed).description)
    }

    @Test fun `an installed card has no description when the catalog does not know the pack`() {
        val cards = build(installed = listOf(installed("a")))
        assertEquals(null, (cards[0] as CompanionCard.Installed).description)
    }

    @Test fun `import is always last and there is no reload card`() {
        val cards = build(installed = listOf(installed("a")), web = listOf(web("w")))
        assertEquals(CompanionCard.Import, cards.last())
        assertEquals(1, cards.count { it === CompanionCard.Import })
        assertEquals(2, cards.size - 1)
    }

    @Test fun `an installed pack's own overview picture wins over the catalog's`() {
        val cards = CompanionCards.build(
            installed = listOf(installed("a")), web = listOf(web("a", overview = "https://x/a.png")),
            assignedId = null, appVersion = "0.1.15", localOverview = { "file:/packs/a/overview/a.png" },
        )
        assertEquals("file:/packs/a/overview/a.png", (cards[0] as CompanionCard.Installed).overview)
    }

    @Test fun `an imported pack with an overview folder has a picture even without the catalog`() {
        val cards = CompanionCards.build(
            installed = listOf(installed("a")), web = emptyList(),
            assignedId = null, appVersion = "0.1.15", localOverview = { "file:/packs/a/overview/a.png" },
        )
        assertEquals("file:/packs/a/overview/a.png", (cards[0] as CompanionCard.Installed).overview)
    }

    @Test fun `a pack with no overview folder falls back to the catalog's picture`() {
        val cards = CompanionCards.build(
            installed = listOf(installed("a")), web = listOf(web("a", overview = "https://x/a.png")),
            assignedId = null, appVersion = "0.1.15", localOverview = { null },
        )
        assertEquals("https://x/a.png", (cards[0] as CompanionCard.Installed).overview)
    }
}
