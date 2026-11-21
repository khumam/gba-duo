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
 * folder (each scanned through its subfolders, up to [MAX_DEPTH] deep) via the Storage Access Framework. [list]
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

    /** The CRC32 if already known; never reads the ROM. Cheap enough for the library shelf. */
    fun cachedCrc32(entry: RomEntry): Long? =
        entry.uri?.let { index.crc32(it.toString(), entry.size, entry.lastModified) }

    /**
     * Writes the ROM [entry] with [patch] applied as a new file beside it, which the library then lists as its own
     * game (own saves, since its CRC32 differs). The original isn't touched. Blocking: call off the main thread.
     * Throws [RomPatch.PatchException] for a patch that doesn't fit, [java.io.IOException] for anything else.
     */
    fun createPatchedCopy(entry: RomEntry, patchName: String, patch: ByteArray): String {
        val uri = entry.uri ?: throw java.io.IOException("No file for ${entry.fileName}")
        val rom = context.contentResolver.openInputStream(uri)?.use { RomArchive.readRom(entry.fileName, it) }
            ?: throw java.io.IOException("Could not read ${entry.fileName}")
        val patched = RomPatch.apply(rom.bytes, patch, patchName)
        val folder = parentFolder(uri) ?: throw java.io.IOException("Could not find the folder of ${entry.fileName}")
        val name = patchedName(rom.fileName, patchName)
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/octet-stream", name) ?: throw java.io.IOException("Could not create $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(patched) }
            ?: throw java.io.IOException("Could not write $name")
        return file.name ?: name
    }

    /** The library folder (or subfolder) that holds [uri]. */
    private fun parentFolder(uri: Uri, ): DocumentFile? {
        fun search(dir: DocumentFile, depth: Int): DocumentFile? {
            val children = dir.listFiles()
            if (children.any { it.uri == uri }) return dir
            if (depth >= MAX_DEPTH) return null
            return children.filter { it.isDirectory && it.name?.startsWith(".") == false }.firstNotNullOfOrNull { search(it, depth + 1) }
        }
        return folders.firstNotNullOfOrNull { tree ->
            runCatching { DocumentFile.fromTreeUri(context, tree)?.let { search(it, 0) } }.getOrNull()
        }
    }

    private fun collectRomFiles(dir: DocumentFile, depth: Int = 0): List<DocumentFile> {
        val children = dir.listFiles()
        val files = children.filter { it.isFile && it.name?.let(RomArchive::isLibraryFileName) == true }
        if (depth >= MAX_DEPTH) return files
        // Hidden folders (.Trash, .thumbnails...) never hold the player's ROMs.
        val subfolders = children.filter { it.isDirectory && it.name?.startsWith(".") == false }
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
        /** "Game.gba" patched with "Hack.ips" is "Game [Hack].gba". */
        fun patchedName(romFileName: String, patchName: String): String {
            val ext = romFileName.substringAfterLast('.', "gba")
            val game = romFileName.substringBeforeLast('.')
            val patch = patchName.substringBeforeLast('.')
            return "$game [$patch].$ext".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        /** How many folders deep a library folder is scanned; a guard against runaway trees. */
        private const val MAX_DEPTH = 8
        private const val KEY_FOLDERS = "folder_uris"
        private const val KEY_LEGACY_FOLDER = "folder_uri"
    }
}
