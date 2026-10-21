package com.khumam.gbaduo.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.khumam.gbaduo.storage.LegacyMigration
import com.khumam.gbaduo.storage.SaveFolder
import java.io.File

data class RomEntry(
    val uri: Uri?,
    val fileName: String,
    val title: String,
    val gameCode: String,
    val platform: Platform = Platform.GBA,
    val size: Long = 0,
    val lastModified: Long = 0,
) {
    /** Pre-GameStorage save name; still used to find old saves to migrate. */
    fun saveFileName(): String = LegacyMigration.legacySaveName(fileName)

    companion object {
        /** [header] should be [RomHeader.SIZE] bytes of the ROM's start (shorter still parses GBA). */
        fun fromHeader(fileName: String, header: ByteArray, uri: Uri? = null): RomEntry {
            val parsed = RomHeader.parse(fileName, header)
            return RomEntry(uri, fileName, parsed.title, parsed.gameCode, parsed.platform)
        }
    }
}

/** A library folder as shown in "Manage folders". */
data class LibraryFolder(val uri: Uri, val name: String)

/**
 * Lists `.gba`/`.gb`/`.gbc` files, and `.zip`s holding one, in every user-picked
 * folder (each scanned one level deep) via the Storage Access Framework. [list]
 * does blocking I/O: call it off the main thread.
 */
class RomLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val index = RomIndex(File(context.filesDir, "rom_index.json"))

    var folders: List<Uri>
        get() {
            val stored = prefs.getString(KEY_FOLDERS, null)
            val folders = FolderList.decode(stored, prefs.getString(KEY_LEGACY_FOLDER, null))
            if (stored == null && folders.isNotEmpty()) {
                // One-time move of the single folder older versions stored.
                prefs.edit().putString(KEY_FOLDERS, FolderList.encode(folders)).remove(KEY_LEGACY_FOLDER).apply()
            }
            return folders.map(Uri::parse)
        }
        private set(value) = prefs.edit().putString(KEY_FOLDERS, FolderList.encode(value.map(Uri::toString))).apply()

    fun addFolder(uri: Uri) {
        folders = FolderList.add(folders.map(Uri::toString), uri.toString()).map(Uri::parse)
    }

    fun removeFolder(uri: Uri) {
        folders = FolderList.remove(folders.map(Uri::toString), uri.toString()).map(Uri::parse)
        // The same folder may also be the save folder, which needs the grant to stay.
        if (uri == SaveFolder.uri(context)) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun describeFolders(): List<LibraryFolder> = folders.map { uri ->
        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        LibraryFolder(uri, name ?: uri.lastPathSegment ?: uri.toString())
    }

    fun list(): List<RomEntry> {
        val resolver = context.contentResolver
        return folders
            .mapNotNull { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
            .flatMap { root -> runCatching { collectRomFiles(root) }.getOrDefault(emptyList()) }
            .distinctBy { it.uri.toString() }
            .mapNotNull { file -> readEntry(resolver, file) }
            .sortedBy { it.title.lowercase() }
    }

    /** Blocking (reads the whole ROM on a cache miss): call off the main thread. */
    fun crc32(entry: RomEntry): Long? {
        val uri = entry.uri ?: return null
        val key = uri.toString()
        index.crc32(key, entry.size, entry.lastModified)?.let { return it }
        val crc = context.contentResolver.openInputStream(uri)?.use { RomArchive.crc32(entry.fileName, it) } ?: return null
        index.put(key, entry.size, entry.lastModified, crc)
        return crc
    }

    private fun collectRomFiles(dir: DocumentFile, depth: Int = 0): List<DocumentFile> {
        val children = dir.listFiles()
        val files = children.filter { it.isFile && it.name?.let(RomArchive::isLibraryFileName) == true }
        if (depth >= 1) return files
        val subfolders = children.filter { it.isDirectory }
        return files + subfolders.flatMap { collectRomFiles(it, depth + 1) }
    }

    private fun readEntry(resolver: ContentResolver, file: DocumentFile): RomEntry? {
        val uri = file.uri
        val name = file.name ?: return null
        // For a zip, the header (and the platform fallback) come from the ROM inside;
        // a zip without a ROM isn't listed.
        val rom = runCatching {
            resolver.openInputStream(uri)?.use { RomArchive.readHeader(name, it) }
        }.getOrNull() ?: return null
        return RomEntry.fromHeader(rom.fileName, rom.bytes, uri)
            .copy(fileName = name, size = file.length(), lastModified = file.lastModified())
    }

    companion object {
        private const val KEY_FOLDERS = "folder_uris"
        private const val KEY_LEGACY_FOLDER = "folder_uri"
    }
}
