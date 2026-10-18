package com.khumam.gbaduo.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.player.PlayerActivity
import java.io.File

/**
 * Trampoline activity for frontends/launchers (Cocoon, iiSU, ES-DE, etc.) and
 * file managers ("Open with"). Receives an external Intent pointing to a ROM,
 * resolves the target, forwards it to [PlayerActivity], and finishes immediately
 * without leaving an activity in the back stack.
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val target = LaunchIntentResolver.resolve(intent)
        if (target == null) {
            val raw = intent?.dataString
                ?: intent?.getStringExtra("rom")
                ?: intent?.getStringExtra("ROM")
                ?: "ROM"
            Toast.makeText(this, getString(R.string.load_failed, raw), Toast.LENGTH_SHORT).show()
            return
        }

        val romUri = if (target.path != null) {
            // No storage permission: a path is read through the library folder that holds it.
            libraryDocumentFor(target.path)
                ?: File(target.path).takeIf(File::canRead)?.let(Uri::fromFile)
                ?: run {
                    Toast.makeText(this, getString(R.string.launch_needs_folder, target.fileName), Toast.LENGTH_LONG).show()
                    return
                }
        } else {
            Uri.parse(target.uriString)
        }

        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_ROM_URI, romUri.toString())
            putExtra(PlayerActivity.EXTRA_ROM_FILE_NAME, target.fileName)
            // Picking another game in the frontend replaces the running one instead of stacking on it.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // A caller's read grant only travels with the intent's data, not with an extra.
            if ((intent?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&romUri.scheme == "content") {
                data = romUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(playerIntent)
    }

    /** [path] as a document under one of the library's SAF folders, if one holds it. */
    private fun libraryDocumentFor(path: String): Uri? {
        val documentId = LaunchIntentResolver.documentIdForPath(path) ?: return null
        val trees = RomLibrary(this).folders
            .filter { it.authority == EXTERNAL_STORAGE_AUTHORITY }
            .associateBy { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        val treeId = LaunchIntentResolver.coveringTree(documentId, trees.keys.filterNotNull()) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(trees.getValue(treeId), documentId)
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
