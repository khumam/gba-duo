package com.khumam.gbaduo.companion

import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompanionPackSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeHttp(val handler: (String) -> HttpResponse) : Http {
        var requests = 0
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests++
            return handler(url)
        }
    }

    private val catalogJson = """{"formatVersion":1,"companions":[
        {"id":"test-pack","title":"Test","version":"1.0.0","download":"https://x/test-pack.zip","games":[{"gameCode":"BPRE"}]}]}"""

    private fun zipBytes(): ByteArray {
        val manifest = """{"id":"test-pack","name":"Test","version":"1.0.0","author":"a","formatVersion":1,
            "match":{"gameCode":["BPRE"],"crc32":[]},"tickHz":4,"layout":"layout.json","values":{}}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, text) in mapOf("manifest.json" to manifest, "layout.json" to """{"type":"text","text":"hi"}""")) {
                zos.putNextEntry(ZipEntry(name)); zos.write(text.toByteArray()); zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val packsDir get() = File(tmp.root, "packs").apply { mkdirs() }
    private var clock = 0L

    private val cacheFile get() = File(tmp.root, "cache/catalog.cache")

    private fun source(http: FakeHttp, withCacheFile: Boolean = false) = CompanionPackSource(
        store = PackStore(packsDir),
        downloader = PackDownloader(http, packsDir, File(tmp.root, "cache")),
        packsDir = packsDir,
        appVersion = "0.1.15",
        now = { clock },
        cacheFile = if (withCacheFile) cacheFile else null,
    )

    private fun okHttp() = FakeHttp { url ->
        if (url.endsWith("companions.json") || url == PackCatalog.URL) HttpResponse(200, catalogJson.toByteArray())
        else HttpResponse(200, zipBytes())
    }

    @Test fun `catalog is fetched once within six hours and again after`() {
        val http = okHttp()
        val source = source(http)
        source.cards("BPRE", 0L, null)
        clock = 5 * 60 * 60_000L
        source.cards("BPRE", 0L, null)
        assertEquals(1, http.requests)
        clock = 7 * 60 * 60_000L
        source.cards("BPRE", 0L, null)
        assertEquals(2, http.requests)
    }

    @Test fun `offline catalog still gives import and a web card is absent`() {
        val http = FakeHttp { throw IOException("offline") }
        val cards = source(http).cards("BPRE", 0L, null)
        assertEquals(listOf(CompanionCard.Import), cards)
    }

    @Test fun `a failed catalog is not cached`() {
        var fail = true
        val http = FakeHttp { if (fail) throw IOException("offline") else HttpResponse(200, catalogJson.toByteArray()) }
        val source = source(http)
        source.cards("BPRE", 0L, null)
        fail = false
        clock = 61_000L
        val cards = source.cards("BPRE", 0L, null)
        assertTrue(cards.any { it is CompanionCard.Web })
    }

    @Test fun `after a failed fetch the next minute of cards does not hit the web again`() {
        val http = FakeHttp { throw IOException("offline") }
        val source = source(http)
        source.cards("BPRE", 0L, null)
        clock = 30_000L
        source.cards("AXVE", 0L, null)
        assertEquals(1, http.requests)
        clock = 61_000L
        source.cards("BPRE", 0L, null)
        assertEquals(2, http.requests)
    }

    @Test fun `a game with no code gets only import`() {
        assertEquals(listOf(CompanionCard.Import), source(okHttp()).cards("", 0L, null))
    }

    @Test fun `resolving a web pack with a bad checksum installs nothing`() {
        val http = okHttp()
        val source = source(http)
        val card = (source.cards("BPRE", 0L, null).first { it is CompanionCard.Web } as CompanionCard.Web)
        val bad = card.copy(pack = card.pack.copy(sha256 = "0".repeat(64)))
        val result = source.resolve(bad)
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.BAD_CHECKSUM)
        assertNull(PackStore(packsDir).byId("test-pack"))
    }

    @Test fun `resolving a web pack installs it`() {
        val source = source(okHttp())
        val card = source.cards("BPRE", 0L, null).first { it is CompanionCard.Web } as CompanionCard.Web
        val result = source.resolve(card)
        assertTrue(result is Fetch.Ok)
        assertNotNull(PackStore(packsDir).byId("test-pack"))
    }

    private val hour = 60 * 60_000L

    @Test fun `a new source reads the catalog saved on disk instead of fetching`() {
        source(okHttp(), withCacheFile = true).cards("BPRE", 0L, null)
        clock = hour
        val http = okHttp()
        val cards = source(http, withCacheFile = true).cards("BPRE", 0L, null)
        assertEquals(0, http.requests)
        assertTrue(cards.any { it is CompanionCard.Web })
    }

    @Test fun `the saved catalog is fetched again once it is six hours old`() {
        source(okHttp(), withCacheFile = true).cards("BPRE", 0L, null)
        clock = 7 * hour
        val http = okHttp()
        source(http, withCacheFile = true).cards("BPRE", 0L, null)
        assertEquals(1, http.requests)
    }

    @Test fun `offline, an old saved catalog still shows its web packs`() {
        source(okHttp(), withCacheFile = true).cards("BPRE", 0L, null)
        clock = 7 * hour
        val offline = FakeHttp { throw IOException("offline") }
        val cards = source(offline, withCacheFile = true).cards("BPRE", 0L, null)
        assertTrue(cards.any { it is CompanionCard.Web })
    }

    @Test fun `refresh fetches the catalog again even when it is fresh`() {
        val http = okHttp()
        val source = source(http, withCacheFile = true)
        source.cards("BPRE", 0L, null)
        source.refresh()
        source.cards("BPRE", 0L, null)
        source.cards("BPRE", 0L, null)
        assertEquals(2, http.requests)
    }

    @Test fun `refresh while offline keeps showing the catalog it had`() {
        var offline = false
        val http = FakeHttp { if (offline) throw IOException("offline") else HttpResponse(200, catalogJson.toByteArray()) }
        val source = source(http, withCacheFile = true)
        source.cards("BPRE", 0L, null)
        offline = true
        source.refresh()
        assertTrue(source.cards("BPRE", 0L, null).any { it is CompanionCard.Web })
    }

    @Test fun `info reports when a fresh catalog was fetched`() {
        clock = 5_000L
        val source = source(okHttp(), withCacheFile = true)
        source.cards("BPRE", 0L, null)
        assertEquals(CatalogInfo(updatedAt = 5_000L, offline = false), source.info())
    }

    @Test fun `info says offline and keeps the saved time when the fetch fails`() {
        clock = 1_000L
        source(okHttp(), withCacheFile = true).cards("BPRE", 0L, null)
        clock = 7 * hour
        val source = source(FakeHttp { throw IOException("offline") }, withCacheFile = true)
        source.cards("BPRE", 0L, null)
        assertEquals(CatalogInfo(updatedAt = 1_000L, offline = true), source.info())
    }

    @Test fun `info has no time when offline with nothing saved`() {
        val source = source(FakeHttp { throw IOException("offline") })
        source.cards("BPRE", 0L, null)
        assertEquals(CatalogInfo(updatedAt = null, offline = true), source.info())
    }

    @Test fun `info is back to online after a successful refresh`() {
        var offline = true
        val http = FakeHttp { if (offline) throw IOException("offline") else HttpResponse(200, catalogJson.toByteArray()) }
        val source = source(http)
        source.cards("BPRE", 0L, null)
        offline = false
        clock = 9_000L
        source.refresh()
        source.cards("BPRE", 0L, null)
        assertEquals(CatalogInfo(updatedAt = 9_000L, offline = false), source.info())
    }
}
