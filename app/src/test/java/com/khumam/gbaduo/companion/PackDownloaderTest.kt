package com.khumam.gbaduo.companion

import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.HttpResponse
import com.khumam.gbaduo.library.artwork.SteamGridDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeHttp(val handler: (String) -> HttpResponse) : Http {
        override fun get(url: String, headers: Map<String, String>) = handler(url)
    }

    private fun manifest(version: String) = """
        {"id":"test-pack","name":"Test","version":"$version","author":"a",
         "formatVersion":1,"match":{"gameCode":["BPRE"],"crc32":[]},
         "tickHz":4,"layout":"layout.json","values":{}}
    """.trimIndent()

    private fun zipBytes(version: String = "1.0.0"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in mapOf("manifest.json" to manifest(version), "layout.json" to """{"type":"text","text":"hi"}""")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pack(sha256: String? = null) = CatalogPack(
        id = "test-pack", title = "Test", description = "", version = "1.0.0", author = "a", official = true,
        download = "https://example.com/test-pack-1.0.0.zip", sha256 = sha256,
        games = listOf(CatalogGame("G", "BPRE")), minAppVersion = null, canModifyGame = false,
    )

    private val packsDir get() = File(tmp.root, "packs").apply { mkdirs() }
    private val tempDir get() = File(tmp.root, "cache").apply { mkdirs() }

    private fun downloader(handler: (String) -> HttpResponse) = PackDownloader(FakeHttp(handler), packsDir, tempDir)

    private val catalogJson = """{"formatVersion":1,"companions":[{"id":"a","title":"A","version":"1.0.0","download":"https://x/a.zip","games":[{"gameCode":"BPRE"}]}]}"""

    @Test
    fun catalogParsesOkResponse() {
        val result = downloader { HttpResponse(200, catalogJson.toByteArray()) }.catalog() as Fetch.Ok
        assertEquals(1, result.value.size)
    }

    @Test
    fun catalogIoExceptionIsOffline() {
        val result = downloader { throw IOException("no route") }.catalog() as Fetch.Failed
        assertEquals(Fetch.Reason.OFFLINE, result.reason)
    }

    @Test
    fun catalogNon200IsOffline() {
        val result = downloader { HttpResponse(503, ByteArray(0)) }.catalog() as Fetch.Failed
        assertEquals(Fetch.Reason.OFFLINE, result.reason)
    }

    @Test
    fun catalogFormat2IsUnsupported() {
        val result = downloader { HttpResponse(200, """{"formatVersion":2,"companions":[]}""".toByteArray()) }.catalog() as Fetch.Failed
        assertEquals(Fetch.Reason.UNSUPPORTED_CATALOG, result.reason)
    }

    @Test
    fun installsAndReturnsManifest() {
        val result = downloader { HttpResponse(200, zipBytes()) }.install(pack()) as Fetch.Ok
        assertEquals("test-pack", result.value.id)
        assertTrue(File(packsDir, "test-pack/manifest.json").exists())
        assertFalse(File(tempDir, "web-test-pack.zip").exists())
    }

    @Test
    fun http404IsDownloadFailedAndInstallsNothing() {
        downloader { HttpResponse(200, zipBytes("1.0.0")) }.install(pack())
        val result = downloader { HttpResponse(404, ByteArray(0)) }.install(pack()) as Fetch.Failed
        assertEquals(Fetch.Reason.DOWNLOAD_FAILED, result.reason)
        assertTrue(File(packsDir, "test-pack/manifest.json").readText().contains("1.0.0"))
    }

    @Test
    fun garbageBodyIsInstallFailed() {
        val result = downloader { HttpResponse(200, "Not Found".toByteArray()) }.install(pack()) as Fetch.Failed
        assertEquals(Fetch.Reason.INSTALL_FAILED, result.reason)
        assertTrue(packsDir.listFiles()!!.none { !it.name.startsWith(".") })
    }

    @Test
    fun sha256MismatchRefused() {
        val bytes = zipBytes()
        val bad = downloader { HttpResponse(200, bytes) }.install(pack(sha256 = "0".repeat(64))) as Fetch.Failed
        assertEquals(Fetch.Reason.BAD_CHECKSUM, bad.reason)
        assertFalse(File(packsDir, "test-pack").exists())
        val good = downloader { HttpResponse(200, bytes) }.install(pack(sha256 = sha(bytes)))
        assertTrue(good is Fetch.Ok)
    }

    @Test
    fun oversizeRefused() {
        val result = downloader { HttpResponse(200, ByteArray(SteamGridDb.MAX_DOWNLOAD + 1)) }.install(pack()) as Fetch.Failed
        assertEquals(Fetch.Reason.TOO_BIG, result.reason)
    }

    @Test
    fun installIoExceptionIsOffline() {
        val result = downloader { throw IOException("reset") }.install(pack()) as Fetch.Failed
        assertEquals(Fetch.Reason.OFFLINE, result.reason)
    }
}
