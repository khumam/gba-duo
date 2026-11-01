package com.khumam.gbaduo.update

import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class AppUpdaterTest {

    private fun release(tag: String = "v0.1.12", prerelease: Boolean = false, asset: String = "gbaduo-0.1.12.apk") = """
        {"tag_name":"$tag","draft":false,"prerelease":$prerelease,"body":"Fixes",
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":1},
                   {"name":"$asset","browser_download_url":"https://x/$asset","size":42}]}
    """.trimIndent()

    private fun updater(code: Int = 200, body: String = "", fail: Boolean = false) = AppUpdater(object : Http {
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            if (fail) throw IOException("offline")
            return HttpResponse(code, body.toByteArray())
        }
    }, File("unused"))

    @Test fun parsesApkAssetAndStripsTagPrefix() {
        val r = AppRelease.parse(release())!!
        assertEquals("0.1.12", r.version)
        assertEquals("https://x/gbaduo-0.1.12.apk", r.apkUrl)
        assertEquals(42L, r.size)
    }

    @Test fun ignoresPrereleaseAndReleaseWithoutApk() {
        assertNull(AppRelease.parse(release(prerelease = true)))
        assertNull(AppRelease.parse(release(asset = "gbaduo.zip")))
        assertNull(AppRelease.parse("not json"))
    }

    @Test fun reportsNewerRelease() {
        assertTrue(updater(body = release()).check("0.1.11") is UpdateCheck.Available)
    }

    @Test fun reportsUpToDateWhenSameOrOlder() {
        assertEquals(UpdateCheck.UpToDate, updater(body = release()).check("0.1.12"))
        assertEquals(UpdateCheck.UpToDate, updater(body = release()).check("0.2.0"))
    }

    @Test fun reportsFailureOfflineAndOnHttpError() {
        assertTrue(updater(fail = true).check("0.1.11") is UpdateCheck.Failed)
        assertTrue(updater(code = 403).check("0.1.11") is UpdateCheck.Failed)
    }
}
