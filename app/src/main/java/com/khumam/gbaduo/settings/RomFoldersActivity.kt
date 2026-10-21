package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.LibraryFolder
import com.khumam.gbaduo.library.RomLibrary
import com.khumam.gbaduo.library.ui.Fonts
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelBoxDrawable

/**
 * Settings > ROM folders: the folders the library scans, each with its own Remove,
 * and Add folder. Removing takes two presses (the button asks first), no dialog.
 */
class RomFoldersActivity : PixelPage() {

    private lateinit var library: RomLibrary
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = RomLibrary(this)
        page(getString(R.string.manage_folders))
        note(getString(R.string.rom_folders_summary), top = 0)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(list, spaced(8))
        val add = button(getString(R.string.add_rom_folder), Pixel.YELLOW) { pickFolder() }
        content.addView(add, spaced(22, ViewGroup.LayoutParams.WRAP_CONTENT))
        focusFirst(add)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val folders = library.describeFolders()
        if (folders.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.no_folders_yet)
                typeface = Fonts.display(this@RomFoldersActivity)
                textSize = 13f
                letterSpacing = 0.06f
                setTextColor(Pixel.ON_INK_SOFT)
            }, spaced(16))
        }
        for (folder in folders) list.addView(folderCard(folder), spaced(14))
    }

    private fun folderCard(folder: LibraryFolder): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val box = PixelBoxDrawable(this@RomFoldersActivity, Pixel.LAVENDER, shadowDp = 6f, shadowColor = SHADE)
            background = box
            setPadding(dp(14), dp(12), dp(14) + box.shadow.toInt(), dp(12) + box.shadow.toInt())
        }
        card.addView(FrameLayout(this).apply {
            background = PixelBoxDrawable(this@RomFoldersActivity, Pixel.YELLOW, shadowDp = 0f, borderDp = 3f)
            addView(ImageView(this@RomFoldersActivity).apply {
                setImageResource(R.drawable.ic_folder_open)
                setColorFilter(Pixel.INK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(TextView(this).apply {
            text = folder.name
            typeface = Fonts.body(this@RomFoldersActivity, 600)
            textSize = 16f
            setTextColor(Pixel.INK)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(12) })

        // Two presses: the first one asks, the second removes; leaving the button cancels.
        var armed = false
        val remove = button(getString(R.string.remove_short), Pixel.WHITE_SOFT) {}.apply {
            textSize = 13f
            minHeight = dp(40)
            contentDescription = getString(R.string.remove_folder_question, folder.name)
        }
        fun disarm() {
            armed = false
            remove.text = getString(R.string.remove_short)
            remove.style(Pixel.WHITE_SOFT)
        }
        remove.setOnClickListener {
            if (!armed) {
                armed = true
                remove.text = getString(R.string.remove_confirm)
                remove.style(Pixel.CORAL)
            } else {
                library.removeFolder(folder.uri)
                refresh()
            }
        }
        remove.setOnFocusChangeListener { _, has -> if (!has) disarm() }
        card.addView(remove)
        return card
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER)
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

    private companion object {
        const val REQUEST_PICK_FOLDER = 1
    }
}
