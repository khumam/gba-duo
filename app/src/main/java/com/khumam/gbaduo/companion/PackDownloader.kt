package com.khumam.gbaduo.companion

import com.khumam.gbaduo.library.artwork.Http
import com.khumam.gbaduo.library.artwork.SteamGridDb
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A web answer: a value, or why there isn't one. */
sealed interface Fetch<out T> {
    data class Ok<T>(val value: T) : Fetch<T>
    data class Failed(val reason: Reason, val detail: String? = null) : Fetch<Nothing>
    enum class Reason { OFFLINE, DOWNLOAD_FAILED, TOO_BIG, BAD_CHECKSUM, UNSUPPORTED_CATALOG, INSTALL_FAILED }
}

/** Fetches the catalog and installs a chosen pack through [PackInstaller]. Blocking; call off the main thread. */
class PackDownloader(
    private val http: Http,
    private val packsDir: File,
    private val tempDir: File,
) {

    fun catalog(url: String = PackCatalog.URL): Fetch<List<CatalogPack>> {
        val response = try {
            http.get(url, emptyMap())
        } catch (e: IOException) {
            return Fetch.Failed(Fetch.Reason.OFFLINE, e.message)
        }
        if (response.code != 200) return Fetch.Failed(Fetch.Reason.OFFLINE, "HTTP ${response.code}")
        return try {
            Fetch.Ok(PackCatalog.parse(response.body.toString(Charsets.UTF_8)))
        } catch (e: PackCatalog.UnsupportedCatalogException) {
            Fetch.Failed(Fetch.Reason.UNSUPPORTED_CATALOG, e.message)
        }
    }

    fun install(pack: CatalogPack): Fetch<PackManifest> {
        val response = try {
            http.get(pack.download, emptyMap())
        } catch (e: IOException) {
            return Fetch.Failed(Fetch.Reason.OFFLINE, e.message)
        }
        if (response.code != 200) return Fetch.Failed(Fetch.Reason.DOWNLOAD_FAILED, "HTTP ${response.code}")
        if (response.body.size > SteamGridDb.MAX_DOWNLOAD) return Fetch.Failed(Fetch.Reason.TOO_BIG)
        if (pack.sha256 != null && sha256(response.body) != pack.sha256) return Fetch.Failed(Fetch.Reason.BAD_CHECKSUM)

        tempDir.mkdirs()
        val zip = File(tempDir, "web-${pack.id}.zip")
        try {
            zip.writeBytes(response.body)
            return Fetch.Ok(PackInstaller.install(zip, packsDir))
        } catch (e: PackInstaller.InstallException) {
            return Fetch.Failed(Fetch.Reason.INSTALL_FAILED, e.message)
        } catch (e: IOException) {
            return Fetch.Failed(Fetch.Reason.INSTALL_FAILED, e.message)
        } finally {
            zip.delete()
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
