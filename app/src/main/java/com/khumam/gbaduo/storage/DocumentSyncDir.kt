package com.khumam.gbaduo.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [SyncDir] over a folder picked with the system folder picker (a content:// tree).
 * Lists once and reuses the listing until something here changes, because every
 * DocumentFile lookup is a query to the provider.
 */
class DocumentSyncDir(private val context: Context, private val doc: DocumentFile) : SyncDir {

    private var cache: List<DocumentFile>? = null

    private fun children(): List<DocumentFile> = cache ?: doc.listFiles().toList().also { cache = it }
    private fun find(name: String): DocumentFile? = children().firstOrNull { it.name == name }
    private fun changed() { cache = null }

    override fun entries(): List<SyncEntry> =
        children().mapNotNull { f -> f.name?.let { SyncEntry(it, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified()) } }

    override fun entry(name: String): SyncEntry? =
        find(name)?.let { f -> SyncEntry(name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified()) }

    override fun subdir(name: String, create: Boolean): SyncDir? {
        find(name)?.takeIf { it.isDirectory }?.let { return DocumentSyncDir(context, it) }
        if (!create) return null
        val made = doc.createDirectory(name) ?: return null
        changed()
        return DocumentSyncDir(context, made)
    }

    override fun openRead(name: String): InputStream {
        val f = find(name) ?: throw FileNotFoundException(name)
        return context.contentResolver.openInputStream(f.uri) ?: throw FileNotFoundException(name)
    }

    override fun openWrite(name: String): OutputStream {
        // application/octet-stream: providers keep the name as given (no extension added).
        val f = find(name) ?: doc.createFile(MIME, name)?.also { changed() } ?: throw FileNotFoundException("can't create $name")
        if (f.name != name) {
            // A provider that renamed it anyway (e.g. "name (1)"): don't write somewhere unexpected.
            f.delete()
            changed()
            throw FileNotFoundException("provider renamed $name to ${f.name}")
        }
        return context.contentResolver.openOutputStream(f.uri, "wt") ?: throw FileNotFoundException(name)
    }

    override fun rename(from: String, to: String): Boolean {
        if (find(to) != null) return false
        val f = find(from) ?: return false
        val ok = f.renameTo(to)
        changed()
        return ok
    }

    override fun delete(name: String): Boolean {
        val f = find(name) ?: return true
        val ok = f.delete()
        changed()
        return ok
    }

    companion object {
        private const val MIME = "application/octet-stream"

        /** The picked tree, or null when it can't be reached (permission gone, SD card out). */
        fun open(context: Context, treeUri: Uri): DocumentSyncDir? {
            val root = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull() ?: return null
            if (!root.exists() || !root.isDirectory || !root.canWrite()) return null
            return DocumentSyncDir(context, root)
        }
    }
}
