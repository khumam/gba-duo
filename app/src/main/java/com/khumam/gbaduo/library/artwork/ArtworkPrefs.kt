package com.khumam.gbaduo.library.artwork

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.storage.DocumentSyncDir
import com.khumam.gbaduo.storage.SaveFolder
import com.khumam.gbaduo.storage.SyncDir

/** The Scraping settings: the artwork folder (read only) and the SteamGridDB key. */
object ArtworkPrefs {
    private const val PREFS = "artwork"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_SGDB = "sgdb_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folderUri(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun folderName(context: Context): String? {
        val uri = folderUri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    /** The artwork folder ready to read, or null when unset or unreachable right now. */
    fun openFolder(context: Context): SyncDir? = folderUri(context)?.let { DocumentSyncDir.openReadOnly(context, it) }

    /** Keeps read access to a freshly picked folder across restarts, and remembers it. */
    fun useFolder(context: Context, uri: Uri) {
        val previous = folderUri(context)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit().putString(KEY_FOLDER, uri.toString()).apply()
        if (previous != null && previous != uri) release(context, previous)
    }

    fun clearFolder(context: Context) {
        val previous = folderUri(context) ?: return
        prefs(context).edit().remove(KEY_FOLDER).apply()
        release(context, previous)
    }

    private fun release(context: Context, uri: Uri) {
        // A ROM or save folder shares the same grant.
        if (uri in RomLibrary(context).folders || uri == SaveFolder.uri(context)) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun sgdbKey(context: Context): String? = prefs(context).getString(KEY_SGDB, null)?.takeIf { it.isNotBlank() }

    fun setSgdbKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_SGDB, key.trim()).apply()
    }
}
