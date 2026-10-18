package com.khumam.gbaduo.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.LibraryFolder
import com.khumam.gbaduo.library.RomLibrary

/**
 * Settings > ROM folders: the folders the library scans, with add and remove.
 * The folder picker and the removal confirm moved here from the library screen.
 */
class RomFoldersActivity : Activity() {

    private lateinit var library: RomLibrary
    private lateinit var folders: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rom_folders)
        library = RomLibrary(this)
        folders = findViewById(R.id.folders)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = library.describeFolders()
        folders.removeAllViews()
        for (folder in list) folders.addView(folderRow(folder))
        folders.addView(addRow())
        if (list.isEmpty()) folders.addView(emptyHint())
    }

    private fun folderRow(folder: LibraryFolder): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(12))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_folder_open)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(TextView(this).apply {
            text = folder.name
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_delete)
            contentDescription = getString(R.string.remove)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { confirmRemoveFolder(folder) }
        })
        return row
    }

    private fun addRow(): View = TextView(this).apply {
        text = getString(R.string.add_folder)
        setTextColor(getColor(R.color.accent))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(20), dp(16), dp(20), dp(8))
        setOnClickListener { pickFolder() }
    }

    private fun emptyHint(): View = TextView(this).apply {
        text = getText(R.string.no_folders_yet)
        setTextColor(getColor(R.color.text_secondary))
        textSize = 14f
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER)
    }

    private fun confirmRemoveFolder(folder: LibraryFolder) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_folder_question, folder.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                library.removeFolder(folder.uri)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        library.addFolder(uri)
        refresh()
        Toast.makeText(this, R.string.folder_added, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PICK_FOLDER = 1
    }
}
