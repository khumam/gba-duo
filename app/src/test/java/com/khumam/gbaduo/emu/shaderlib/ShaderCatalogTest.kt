package com.khumam.gbaduo.emu.shaderlib

import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ShaderCatalogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sample = javaClass.getResource("/slang_tree_sample.json")!!.readText()
    private val hour = 3_600_000L

    private class FakeHttp(var handler: (String) -> HttpResponse) : Http {
        var calls = 0
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            calls++
            return handler(url)
        }
    }

    /** The commit sha for the commits URL, the tree for any trees URL. */
    private fun serve(url: String) = if (url == ShaderCatalog.COMMIT_URL) HttpResponse(200, "abc123def".toByteArray()) else HttpResponse(200, sample.toByteArray())

    @Test
    fun parseKeepsOnlyPresetsAndTheCommitSha() {
        val listing = ShaderCatalog.parse(sample, sha = "abc123def", now = 42)
        assertEquals("abc123def", listing.sha)
        assertEquals(42L, listing.fetchedAtMillis)
        assertTrue("crt/crt-geom.slangp" in listing.presets)
        assertTrue("bilinear.slangp" in listing.presets)
        assertTrue(listing.presets.none { !it.endsWith(".slangp") })
        assertEquals(8, listing.presets.size)
    }

    @Test
    fun parseRefusesATruncatedOrMalformedAnswer() {
        for (bad in listOf("""{"tree":[],"truncated":true}""", "not json", """{"nothing":[]}""")) {
            try {
                ShaderCatalog.parse(bad, sha = "abc123def", now = 0)
                fail("expected IllegalArgumentException for $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun aFreshCacheMakesNoNetworkCall() {
        val file = tmp.newFile("catalog.json")
        val http = FakeHttp(::serve)
        var now = 1_000L
        val store = ShaderCatalogStore(http, file) { now }
        assertTrue(store.load(refresh = false) is Fetch.Ok)       // fetches and caches
        assertEquals(2, http.calls)                                // the commit, then its tree
        now += 23 * hour
        val again = store.load(refresh = false) as Fetch.Ok
        assertEquals(2, http.calls)                                // still fresh
        assertTrue("crt/crt-geom.slangp" in again.value.presets)
    }

    @Test
    fun aStaleCacheIsRefetchedButKeptWhenTheNetworkIsDown() {
        val file = tmp.newFile("catalog.json")
        val http = FakeHttp(::serve)
        var now = 1_000L
        val store = ShaderCatalogStore(http, file) { now }
        store.load(refresh = false)
        now += 25 * hour
        http.handler = { throw IOException("no route") }
        val stale = store.load(refresh = false) as Fetch.Ok
        assertEquals(3, http.calls)                                // a refetch that failed at the first request
        assertEquals("abc123def", stale.value.sha)
    }

    @Test
    fun noCacheAndNoNetworkIsOffline() {
        val http = FakeHttp { throw IOException("no route") }
        val result = ShaderCatalogStore(http, tmp.newFolder().resolve("catalog.json")) { 0 }.load(refresh = false)
        assertEquals(Fetch.Failed(Fetch.Reason.OFFLINE, "no route"), result)
    }

    @Test
    fun aRateLimitIsReportedAndFallsBackToTheCache() {
        val file = tmp.newFolder().resolve("catalog.json")
        val http = FakeHttp { HttpResponse(403, "rate limit".toByteArray()) }
        val failed = ShaderCatalogStore(http, file) { 0 }.load(refresh = false) as Fetch.Failed
        assertEquals(Fetch.Reason.DOWNLOAD_FAILED, failed.reason)
        assertTrue(failed.detail!!.contains("403"))
        assertFalse(file.exists())

        http.handler = ::serve
        val store = ShaderCatalogStore(http, file) { 0 }
        store.load(refresh = false)
        http.handler = { HttpResponse(403, ByteArray(0)) }
        assertTrue(store.load(refresh = true) is Fetch.Ok)         // refresh failed, the cache answers
    }

    @Test
    fun refreshFetchesAgainEvenWhenFresh() {
        val http = FakeHttp(::serve)
        val store = ShaderCatalogStore(http, tmp.newFolder().resolve("catalog.json")) { 5 }
        store.load(refresh = false)
        store.load(refresh = true)
        assertEquals(4, http.calls)
    }

    @Test
    fun theCacheSurvivesANewStoreObject() {
        val file = tmp.newFolder().resolve("catalog.json")
        ShaderCatalogStore(FakeHttp(::serve), file) { 10 }.load(refresh = false)
        val offline = FakeHttp { throw IOException("down") }
        val result = ShaderCatalogStore(offline, file) { 11 }.load(refresh = false) as Fetch.Ok
        assertEquals(0, offline.calls)
        assertEquals(8, result.value.presets.size)
    }
}
