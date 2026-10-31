package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCatalogTest {

    private fun entry(id: String, download: String? = "https://github.com/x/y/releases/download/v1/$id-1.0.0.zip", code: String = "BPRE", min: String = "0.1.3") =
        """{"id":"$id","title":"T $id","description":"d","version":"2.0.0","author":"khumam","official":true,
            ${if (download != null) "\"download\":\"$download\"," else ""}
            "games":[{"title":"G","region":"US","gameCode":"$code"}],
            "minAppVersion":"$min","canModifyGame":false,"extra":1}"""

    private fun catalog(vararg entries: String, format: Int = 1) =
        """{"formatVersion":$format,"companions":[${entries.joinToString(",")}]}"""

    private val fixture = catalog(
        entry("pokemon-firered-companion"),
        entry("zelda-oracle-of-ages-companion", code = "CGB-AZ8E"),
        entry("metroid-fusion-companion", code = "AMTE", min = "0.1.8"),
        entry("no-download", download = null),
    )

    @Test
    fun parseReadsRealCatalogFields() {
        val pack = PackCatalog.parse(fixture).first { it.id == "pokemon-firered-companion" }
        assertEquals("2.0.0", pack.version)
        assertEquals("BPRE", pack.games[0].gameCode)
        assertNull(pack.sha256)
        assertEquals(false, pack.canModifyGame)
        assertEquals("0.1.3", pack.minAppVersion)
    }

    @Test
    fun parseSkipsEntryMissingDownload() {
        assertEquals(3, PackCatalog.parse(fixture).size)
    }

    @Test
    fun parseSkipsNonHttpsDownload() {
        val json = catalog(entry("a", download = "http://example.com/a.zip"), entry("b"))
        assertEquals(listOf("b"), PackCatalog.parse(json).map { it.id })
    }

    @Test(expected = PackCatalog.UnsupportedCatalogException::class)
    fun parseRejectsFormatVersion2() {
        PackCatalog.parse(catalog(entry("a"), format = 2))
    }

    @Test(expected = PackCatalog.UnsupportedCatalogException::class)
    fun parseRejectsMalformedJson() {
        PackCatalog.parse("<html>")
    }

    @Test
    fun forGameMatchesExactCode() {
        val packs = PackCatalog.parse(fixture)
        assertEquals(listOf("pokemon-firered-companion"), PackCatalog.forGame(packs, "BPRE").map { it.id })
        assertEquals(listOf("zelda-oracle-of-ages-companion"), PackCatalog.forGame(packs, "CGB-AZ8E").map { it.id })
        assertTrue(PackCatalog.forGame(packs, "AZ8E").isEmpty())
    }

    @Test
    fun forGameIgnoresCaseAndPadding() {
        val packs = PackCatalog.parse(fixture)
        assertEquals(listOf("pokemon-firered-companion"), PackCatalog.forGame(packs, " bpre ").map { it.id })
        assertTrue(PackCatalog.forGame(packs, "").isEmpty())
    }

    @Test
    fun relatedToMatchesOtherRegionsOfSameGame() {
        val packs = PackCatalog.parse(fixture)
        assertEquals(listOf("pokemon-firered-companion"), PackCatalog.relatedTo(packs, "BPRJ").map { it.id })
        assertTrue(PackCatalog.relatedTo(packs, "BPRE").isEmpty())
        assertTrue(PackCatalog.relatedTo(packs, "CGB-AZ8E").isEmpty())
    }

    @Test
    fun compareVersions() {
        assertTrue(PackCatalog.compareVersions("0.1.10", "0.1.8") > 0)
        assertEquals(0, PackCatalog.compareVersions("1.0", "1.0.0"))
        assertTrue(PackCatalog.compareVersions("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun tooNewForApp() {
        val fusion = PackCatalog.parse(fixture).first { it.id == "metroid-fusion-companion" }
        assertEquals(CatalogStatus.NEEDS_NEWER_APP, PackCatalog.status(fusion, null, "0.1.7"))
        assertEquals(CatalogStatus.NEW, PackCatalog.status(fusion, null, "0.1.8"))
    }

    @Test
    fun statusInstalledUpdateNew() {
        val pack = PackCatalog.parse(fixture).first { it.id == "pokemon-firered-companion" }
        assertEquals(CatalogStatus.NEW, PackCatalog.status(pack, null, "0.1.8"))
        assertEquals(CatalogStatus.INSTALLED, PackCatalog.status(pack, "2.0.0", "0.1.8"))
        assertEquals(CatalogStatus.UPDATE, PackCatalog.status(pack, "1.1.0", "0.1.8"))
        assertEquals(CatalogStatus.INSTALLED, PackCatalog.status(pack, "2.1.0", "0.1.8"))
        assertEquals(CatalogStatus.NEEDS_NEWER_APP, PackCatalog.status(pack, "1.0.0", "0.1.2"))
    }
}
