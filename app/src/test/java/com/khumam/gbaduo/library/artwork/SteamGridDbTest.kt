package com.khumam.gbaduo.library.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SteamGridDbTest {

    private class FakeHttp(val handler: (String) -> HttpResponse) : Http {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            urls += url
            this.headers += headers
            return handler(url)
        }
    }

    private fun json(s: String) = HttpResponse(200, s.toByteArray())

    private val search = """{"success":true,"data":[{"id":42,"name":"Wario Land 4","release_date":1000000000},{"id":43,"name":"Other"}]}"""
    private val grids = """{"success":true,"data":[{"id":7,"url":"https://cdn/a.png","thumb":"https://cdn/t.png","width":600,"height":900},{"id":8,"url":"https://cdn/b.png","thumb":"https://cdn/u.png","width":600,"height":900}]}"""

    private fun api(http: Http, sleeps: MutableList<Long> = mutableListOf()) =
        SteamGridDb("KEY", http) { sleeps += it }

    @Test
    fun searchSendsBearerKeyAndEncodedTitle() {
        val http = FakeHttp { json(search) }
        val result = api(http).search("Pokémon Gold & Silver") as Sgdb.Ok
        assertEquals("https://www.steamgriddb.com/api/v2/search/autocomplete/Pok%C3%A9mon%20Gold%20%26%20Silver", http.urls[0])
        assertEquals("Bearer KEY", http.headers[0]["Authorization"])
        assertEquals(listOf(42L, 43L), result.value.map { it.id })
        assertEquals("Wario Land 4", result.value[0].name)
        assertEquals(2001, result.value[0].year)
        assertEquals(null, result.value[1].year)
    }

    @Test
    fun gbaGridsAskForPortraitDimensionsAndSafeContent() {
        val http = FakeHttp { url -> json(if ("/grids/" in url) grids else search) }
        api(http).grids(42)
        val url = http.urls.single()
        assertTrue(url, url.startsWith("https://www.steamgriddb.com/api/v2/grids/game/42?"))
        assertTrue(url, "dimensions=600x900,342x482,660x930" in url)
        assertTrue(url, "types=static" in url && "nsfw=false" in url && "humor=false" in url && "epilepsy=false" in url)
    }

    @Test
    fun gameBoyGridsAskForPortraitDimensionsToo() {
        val http = FakeHttp { json(grids) }
        api(http).grids(42)
        assertTrue(http.urls.single(), "dimensions=600x900,342x482,660x930" in http.urls.single())
    }

    @Test
    fun emptyGridsRetryWithoutDimensions() {
        var calls = 0
        val http = FakeHttp { if (calls++ == 0) json("""{"success":true,"data":[]}""") else json(grids) }
        val result = api(http).grids(42) as Sgdb.Ok
        assertEquals(2, http.urls.size)
        assertTrue("dimensions" !in http.urls[1])
        assertEquals(listOf(7L, 8L), result.value.map { it.id })
        assertEquals("https://cdn/t.png", result.value[0].thumb)
    }

    @Test
    fun coverTakesTheFirstGridOfTheFirstGame() {
        val http = FakeHttp { url -> json(if ("/grids/" in url) grids else search) }
        val result = api(http).cover("Wario Land 4") as Sgdb.Ok
        assertEquals(7L, result.value.id)
        assertTrue("/grids/game/42" in http.urls.last())
    }

    @Test
    fun noGameRetriesWithShortTitleThenGivesUp() {
        val http = FakeHttp { json("""{"success":true,"data":[]}""") }
        val result = api(http).cover("Zelda: The Minish Cap")
        assertEquals(Sgdb.Failed(Sgdb.Reason.NOT_FOUND), result)
        assertEquals(2, http.urls.size)
        assertTrue(http.urls[1].endsWith("/autocomplete/Zelda"))
    }

    @Test
    fun rejectedKeyIsReported() {
        assertEquals(Sgdb.Failed(Sgdb.Reason.KEY_REJECTED), api(FakeHttp { HttpResponse(401, ByteArray(0)) }).search("x"))
    }

    @Test
    fun tooManyRequestsWaitsOnceThenRetries() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val http = FakeHttp { if (calls++ == 0) HttpResponse(429, ByteArray(0)) else json(search) }
        assertTrue(api(http, sleeps).search("x") is Sgdb.Ok)
        assertEquals(listOf(5000L), sleeps)
    }

    @Test
    fun secondTooManyRequestsStops() {
        val result = api(FakeHttp { HttpResponse(429, ByteArray(0)) }).search("x")
        assertEquals(Sgdb.Failed(Sgdb.Reason.SLOW_DOWN), result)
    }

    @Test
    fun networkErrorIsOffline() {
        val result = api(FakeHttp { throw IOException("no route") }).search("x")
        assertEquals(Sgdb.Failed(Sgdb.Reason.OFFLINE), result)
    }

    @Test
    fun downloadRefusesHugeFiles() {
        val http = FakeHttp { HttpResponse(200, ByteArray(SteamGridDb.MAX_DOWNLOAD + 1)) }
        assertEquals(Sgdb.Failed(Sgdb.Reason.NOT_FOUND), api(http).download("https://cdn/a.png"))
        val ok = api(FakeHttp { HttpResponse(200, byteArrayOf(1, 2)) }).download("https://cdn/a.png") as Sgdb.Ok
        assertEquals(2, ok.value.size)
    }

    @Test
    fun downloadSendsNoApiKeyToTheImageServer() {
        val http = FakeHttp { HttpResponse(200, byteArrayOf(1)) }
        api(http).download("https://cdn2.steamgriddb.com/grid/a.png")
        assertTrue(http.headers.single().isEmpty())
    }

    @Test
    fun imageServerRefusalIsNotABadKey() {
        val result = api(FakeHttp { HttpResponse(401, ByteArray(0)) }).download("https://cdn2.steamgriddb.com/grid/a.png")
        assertEquals(Sgdb.Failed(Sgdb.Reason.OFFLINE), result)
    }
}
