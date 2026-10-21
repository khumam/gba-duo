package com.khumam.gbaduo.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.khumam.gbaduo.library.RomLibrary

/**
 * The player's save folder setting: a folder picked with the system folder picker
 * (on the SD card, a synced folder...) that mirrors every game's saves. Unset, saves
 * live only in private storage as before. See [SaveLibrary] for what goes there.
 */
object SaveFolder {
    private const val PREFS = "save_folder"
    private const val KEY_TREE = "tree_uri"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen folder, or null when saves stay private only. */
    fun uri(context: Context): Uri? = prefs(context).getString(KEY_TREE, null)?.let(Uri::parse)

    /** The folder's name as the picker shows it, or null if unset or unreachable. */
    fun displayName(context: Context): String? {
        val uri = uri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
    }

    /** The chosen folder, ready to sync with; null when unset or unreachable right now. */
    fun open(context: Context): DocumentSyncDir? = uri(context)?.let { DocumentSyncDir.open(context, it) }

    /** Keeps read/write access to a freshly picked folder across restarts. Call before [SaveLibrary.moveAll]. */
    fun takePermission(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, FLAGS)
    }

    /** Switches to [uri], once its copy of the library checked out; lets go of the previous folder. */
    fun use(context: Context, uri: Uri) {
        val previous = uri(context)
        prefs(context).edit().putString(KEY_TREE, uri.toString()).apply()
        if (previous != null && previous != uri) release(context, previous)
    }

    /** Stops mirroring. The folder and the saves in it stay as they are; private copies are untouched. */
    fun clear(context: Context) {
        val previous = uri(context) ?: return
        prefs(context).edit().remove(KEY_TREE).apply()
        release(context, previous)
    }

    /** Drops access to a picked folder that wasn't (or is no longer) used. */
    fun release(context: Context, uri: Uri) {
        // Unless it's also a ROM folder, which shares the same grant.
        if (uri in RomLibrary(context).folders) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, FLAGS) }
    }
}
