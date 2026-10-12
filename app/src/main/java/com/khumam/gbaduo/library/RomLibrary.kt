package com.khumam.gbaduo.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class RomEntry(
    val uri: Uri?,
    val fileName: String,
    val title: String,
    val gameCode: String,
) {
    fun saveFileName(): String = fileName.substringBeforeLast('.', fileName) + ".sav"

    companion object {
        /** [header] must be at least 0xC0 bytes of the ROM's start. */
        fun fromHeader(fileName: String, header: ByteArray, uri: Uri? = null): RomEntry {
            val title = String(header, 0xA0, 12).trim('\u0000', ' ')
            val code = String(header, 0xAC, 4).trim('\u0000', ' ')
            return RomEntry(uri, fileName, title.ifBlank { fileName }, code)
        }
    }
}

/** Lists `.gba` files in a user-picked folder (one level deep) via Storage Access Framework. */
class RomLibrary(private val context: Context) {

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var folderUri: Uri?
        get() = prefs.getString("folder_uri", null)?.let(Uri::parse)
        set(value) = prefs.edit().putString("folder_uri", value?.toString()).apply()

    fun list(): List<RomEntry> {
        val root = folderUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return emptyList()
        val resolver = context.contentResolver
        return collectGbaFiles(root).mapNotNull { file -> readEntry(resolver, file) }
            .sortedBy { it.title.lowercase() }
    }

    private fun collectGbaFiles(dir: DocumentFile, depth: Int = 0): List<DocumentFile> {
        val children = dir.listFiles()
        val files = children.filter { it.isFile && it.name?.endsWith(".gba", ignoreCase = true) == true }
        if (depth >= 1) return files
        val subfolders = children.filter { it.isDirectory }
        return files + subfolders.flatMap { collectGbaFiles(it, depth + 1) }
    }

    private fun readEntry(resolver: ContentResolver, file: DocumentFile): RomEntry? {
        val uri = file.uri
        val header = ByteArray(0xC0)
        resolver.openInputStream(uri)?.use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
        } ?: return null
        return RomEntry.fromHeader(file.name ?: return null, header, uri)
    }
}
