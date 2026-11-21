package com.khumam.gbaduo.companion.ui

import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OverviewCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeHttp(var handler: (String) -> HttpResponse) : Http {
        var requests = 0
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests++
            return handler(url)
        }
    }

    private val dir get() = File(tmp.root, "overview")
    private val png = byteArrayOf(1, 2, 3)

    @Test fun `an image is downloaded once and then read from disk, even by a new cache`() {
        val http = FakeHttp { HttpResponse(200, png) }
        assertArrayEquals(png, OverviewCache(http, dir).bytes("https://x/a.png"))
        assertArrayEquals(png, OverviewCache(http, dir).bytes("https://x/a.png"))
        assertEquals(1, http.requests)
    }

    @Test fun `force downloads again and replaces the saved image`() {
        val http = FakeHttp { HttpResponse(200, png) }
        val cache = OverviewCache(http, dir)
        cache.bytes("https://x/a.png")
        http.handler = { HttpResponse(200, byteArrayOf(9)) }
        assertArrayEquals(byteArrayOf(9), cache.bytes("https://x/a.png", force = true))
        assertArrayEquals(byteArrayOf(9), cache.bytes("https://x/a.png"))
    }

    @Test fun `a failed forced download keeps the saved image`() {
        val http = FakeHttp { HttpResponse(200, png) }
        val cache = OverviewCache(http, dir)
        cache.bytes("https://x/a.png")
        http.handler = { throw IOException("offline") }
        assertArrayEquals(png, cache.bytes("https://x/a.png", force = true))
    }

    @Test fun `an image that cannot be fetched is null and not saved`() {
        val http = FakeHttp { HttpResponse(404, ByteArray(0)) }
        assertNull(OverviewCache(http, dir).bytes("https://x/a.png"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test fun `a file location is read from disk without the network`() {
        val file = File(tmp.root, "pack/overview/a.png").apply { parentFile!!.mkdirs(); writeBytes(png) }
        val http = FakeHttp { HttpResponse(200, byteArrayOf(9)) }
        assertArrayEquals(png, OverviewCache(http, dir).bytes(file.toURI().toString()))
        assertArrayEquals(png, OverviewCache(http, dir).bytes(file.toURI().toString(), force = true))
        assertEquals(0, http.requests)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test fun `a file location that is gone is null and not fetched`() {
        val http = FakeHttp { HttpResponse(200, png) }
        assertNull(OverviewCache(http, dir).bytes(File(tmp.root, "missing.png").toURI().toString()))
        assertEquals(0, http.requests)
    }
}
