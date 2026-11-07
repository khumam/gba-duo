package com.khumam.gbaduo.emu.shaderlib

import com.khumam.gbaduo.companion.Fetch
import com.khumam.gbaduo.emu.ShaderLibrary
import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import com.khumam.gbaduo.library.artwork.SteamGridDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ShaderDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sha = "abc123def"

    private val repo = mutableMapOf(
        "crt/p.slangp" to """
            #reference "../base/base.slangp"
            shaders = 2
            shader0 = shaders/a.slang
            shader1 = "shaders/b.slang"
            textures = "lut"
            lut = luts/l.png
        """.trimIndent(),
        "base/base.slangp" to "shaders = 1\nshader0 = s/c.slang\n",
        "crt/shaders/a.slang" to "#version 450\n#include \"inc/common.inc\"\n",
        "crt/shaders/b.slang" to "#version 450\n",
        "crt/shaders/inc/common.inc" to "#include \"deeper.inc\"\n",
        "crt/shaders/inc/deeper.inc" to "const float X = 1.0;\n",
        "crt/luts/l.png" to "PNG",
        "base/s/c.slang" to "#version 450\n",
    )

    private class FakeHttp(val handler: (String) -> HttpResponse) : Http {
        val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())  // fetched from several threads
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requested += url
            return handler(url)
        }
    }

    private fun serving(files: Map<String, String>) = FakeHttp { url ->
        val path = url.removePrefix("https://raw.githubusercontent.com/khumam/gba-duo-slang-shaders/$sha/")
        files[path]?.let { HttpResponse(200, it.toByteArray()) } ?: HttpResponse(404, ByteArray(0))
    }

    private fun library() = ShaderLibrary(tmp.newFolder("library"))
    private fun downloader(http: Http, library: ShaderLibrary, maxFileBytes: Int = 16 * 1024 * 1024) =
        ShaderDownloader(http, library, tmp.newFolder("temp"), maxFileBytes)

    private fun filesUnder(root: File) = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()

    @Test
    fun dependenciesFollowReferencesShadersIncludesAndTextures() {
        val deps = ShaderDownloader.dependencies("crt/p.slangp") { repo[it] }
        assertEquals(
            setOf(
                "crt/p.slangp", "base/base.slangp", "crt/shaders/a.slang", "crt/shaders/b.slang", "crt/shaders/inc/common.inc",
                "crt/shaders/inc/deeper.inc", "crt/luts/l.png", "base/s/c.slang",
            ),
            deps,
        )
    }

    @Test
    fun aReferenceCycleAndAnIncludeCycleTerminate() {
        val loops = mapOf(
            "a.slangp" to "#reference \"b.slangp\"\nshaders = 1\nshader0 = s.slang\n",
            "b.slangp" to "#reference \"a.slangp\"\n",
            "s.slang" to "#include \"t.inc\"\n", "t.inc" to "#include \"s.slang\"\n",
        )
        assertEquals(setOf("a.slangp", "b.slangp", "s.slang", "t.inc"), ShaderDownloader.dependencies("a.slangp") { loops[it] })
    }

    @Test
    fun aPathClimbingOutOfTheRootIsNeverAdopted() {
        val files = mapOf("p.slangp" to "shaders = 1\nshader0 = ../../evil.slang\n")
        assertEquals(setOf("p.slangp"), ShaderDownloader.dependencies("p.slangp") { files[it] })
    }

    @Test
    fun installWritesEveryFileUnderTheLibraryAndReportsProgress() {
        val lib = library()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = downloader(serving(repo), lib).install("crt/p.slangp", sha) { done, total -> progress += done to total }
        assertEquals(Fetch.Ok(Unit), result)
        assertTrue(lib.isDownloaded("crt/p.slangp"))
        assertEquals(repo.keys.sorted(), filesUnder(lib.root))
        assertEquals("const float X = 1.0;\n", lib.file("crt/shaders/inc/deeper.inc").readText())
        assertEquals(8 to 8, progress.last())
        assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun aDependencyThatAnswers404LeavesNothingInstalled() {
        val lib = library()
        val files = repo.toMutableMap().also { it.remove("crt/luts/l.png") }
        val result = downloader(serving(files), lib).install("crt/p.slangp", sha) { _, _ -> }
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.DOWNLOAD_FAILED)
        assertTrue((result as Fetch.Failed).detail!!.contains("l.png"))
        assertEquals(emptyList<String>(), filesUnder(lib.root))
        assertFalse(lib.isDownloaded("crt/p.slangp"))
    }

    @Test
    fun noNetworkIsOfflineAndLeavesNothing() {
        val lib = library()
        val http = FakeHttp { throw IOException("no route") }
        val result = downloader(http, lib).install("crt/p.slangp", sha) { _, _ -> }
        assertEquals(Fetch.Failed(Fetch.Reason.OFFLINE, "no route"), result)
        assertEquals(emptyList<String>(), filesUnder(lib.root))
    }

    @Test
    fun aFileOverTheSizeCapIsRefused() {
        val lib = library()
        val result = downloader(serving(repo), lib, maxFileBytes = 20).install("crt/p.slangp", sha) { _, _ -> }
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.TOO_BIG)
        assertEquals(emptyList<String>(), filesUnder(lib.root))
    }

    @Test
    fun aPresetThatNamesAPathOutsideTheRootFailsWithoutFetchingIt() {
        val lib = library()
        val files = mapOf("p.slangp" to "shaders = 1\nshader0 = ../../evil.slang\n", "evil.slang" to "x")
        val http = serving(files)
        val result = downloader(http, lib).install("p.slangp", sha) { _, _ -> }
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.INSTALL_FAILED)
        assertTrue(http.requested.none { it.contains("evil") })
        assertEquals(emptyList<String>(), filesUnder(lib.root))
    }

    @Test
    fun anUnsafePresetPathIsRefusedBeforeAnyRequest() {
        val http = serving(repo)
        val result = downloader(http, library()).install("../x.slangp", sha) { _, _ -> }
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.INSTALL_FAILED)
        assertTrue(http.requested.isEmpty())
    }

    @Test
    fun theDefaultCapRefusesWhatTheHttpLayerHadToCutShort() {
        // UrlHttp reads at most MAX_DOWNLOAD + 1 bytes of a bigger file; that must never be installed as if it were whole.
        val lib = library()
        val http = FakeHttp { HttpResponse(200, ByteArray(SteamGridDb.MAX_DOWNLOAD + 1)) }
        val result = ShaderDownloader(http, lib, tmp.newFolder("temp2")).install("crt/p.slangp", sha) { _, _ -> }
        assertTrue(result is Fetch.Failed && result.reason == Fetch.Reason.TOO_BIG)
        assertEquals(emptyList<String>(), filesUnder(lib.root))
    }
}
