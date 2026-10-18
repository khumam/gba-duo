package com.khumam.gbaduo.library

import android.content.Intent
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Extracts the ROM URI string and filename from an incoming launcher Intent.
 * Pure logic using standard Java, so it runs cleanly in plain unit tests.
 *
 * Supports:
 * - `intent.data` (`content://...` or `file://...`)
 * - String extras: `rom`, `ROM`, `path`, `PATH`, `file`, `FILE` (conventions used by frontends like Cocoon, Daijisho, ES-DE)
 */
object LaunchIntentResolver {

    /**
     * [path] is set when the ROM was given as a filesystem path (a bare path or a `file://` URI).
     * GBADuo has no storage permission, so such a path is only readable through a library
     * folder's SAF grant: see [documentIdForPath] and [coveringTree].
     */
    data class Target(val uriString: String, val fileName: String, val path: String? = null)

    /** Abstraction over Intent so unit tests can run without Android stub dependencies. */
    interface IntentAccessor {
        fun dataString(): String?
        fun extraString(key: String): String?
    }

    class AndroidIntentAccessor(private val intent: Intent) : IntentAccessor {
        override fun dataString(): String? = intent.dataString
        override fun extraString(key: String): String? = intent.getStringExtra(key)
    }

    private val EXTRA_KEYS = listOf("rom", "ROM", "path", "PATH", "file", "FILE")
    private const val FILE_SCHEME = "file://"

    fun resolve(intent: Intent?): Target? =
        if (intent == null) null else resolveAccessor(AndroidIntentAccessor(intent))

    fun resolveAccessor(accessor: IntentAccessor?): Target? {
        if (accessor == null) return null

        val dataString = accessor.dataString()?.trim()
        if (!dataString.isNullOrEmpty()) {
            val name = fileNameFromUriString(dataString)
            if (name != null && RomArchive.isLibraryFileName(name)) {
                return Target(dataString, name, filePathOf(dataString))
            }
        }

        for (key in EXTRA_KEYS) {
            val raw = accessor.extraString(key)?.trim()
            if (raw.isNullOrEmpty()) continue
            val target = if (raw.startsWith("content://") || raw.startsWith(FILE_SCHEME)) {
                fileNameFromUriString(raw)?.let { Target(raw, it, filePathOf(raw)) }
            } else {
                // A bare path: not URL-encoded, so '#', '?' and '%' are part of the name.
                val path = if (raw.startsWith("/")) raw else "/$raw"
                Target(FILE_SCHEME + path, path.substringAfterLast('/'), path)
            }
            if (target != null && RomArchive.isLibraryFileName(target.fileName)) return target
        }

        return null
    }

    /**
     * The externalstorage documents provider's document id for [path], e.g.
     * `/storage/emulated/0/ROMs/a.gba` -> `primary:ROMs/a.gba` and
     * `/storage/9C33-6BBD/ROMs/a.gba` -> `9C33-6BBD:ROMs/a.gba`. Null for paths outside shared storage.
     */
    fun documentIdForPath(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val (volume, rest) = when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "primary" to parts.drop(3)
            parts.size >= 2 && parts[0] == "storage" && parts[1] != "self" -> parts[1] to parts.drop(2)
            parts.size >= 2 && parts[0] == "sdcard" -> "primary" to parts.drop(1)
            parts.size >= 4 && parts[0] == "mnt" && parts[1] == "media_rw" -> parts[2] to parts.drop(3)
            else -> return null
        }
        if (rest.isEmpty()) return null
        return volume + ":" + rest.joinToString("/")
    }

    /** The first of [treeDocumentIds] (SAF tree document ids) that contains [documentId], or null. */
    fun coveringTree(documentId: String, treeDocumentIds: List<String>): String? =
        treeDocumentIds.firstOrNull { tree ->
            documentId.startsWith(if (tree.endsWith(":")) tree else "$tree/")
        }

    private fun filePathOf(uriString: String): String? =
        if (uriString.startsWith(FILE_SCHEME)) decode(uriString.removePrefix(FILE_SCHEME).substringBefore('?').substringBefore('#')) else null

    private fun fileNameFromUriString(uriString: String): String? {
        // Drop any query string / fragment before parsing the path
        val clean = uriString.substringBefore('?').substringBefore('#')
        val name = decode(clean).substringAfterLast('/')
        return name.ifBlank { null }
    }

    /** Percent-decoding only: URLDecoder alone would also turn a literal '+' into a space. */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(s)
}
