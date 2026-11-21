package com.khumam.gbaduo.companion.ui

import com.khumam.gbaduo.library.artwork.Http
import java.io.File
import java.security.MessageDigest

/** Overview images kept on disk, so the shelf downloads each one once rather than at every game it shows. Blocking. */
class OverviewCache(private val http: Http, private val dir: File) {

    /** The saved image, else a download (saved for next time). [force] downloads again; if that fails the saved one stays. */
    fun bytes(url: String, force: Boolean = false): ByteArray? {
        // A picture shipped inside an imported pack: already on disk, nothing to download or keep a second copy of.
        if (url.startsWith("file:")) return runCatching { File(java.net.URI(url)).readBytes() }.getOrNull()
        val file = File(dir, name(url))
        if (!force && file.isFile) runCatching { return file.readBytes() }
        val response = runCatching { http.get(url, emptyMap()) }.getOrNull()
        if (response == null || response.code != 200 || response.body.isEmpty()) {
            return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        }
        runCatching {
            dir.mkdirs()
            file.writeBytes(response.body)
        }
        return response.body
    }

    private fun name(url: String) =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
}
