package com.khumam.gbaduo.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.artwork.ArtworkPrefs
import com.khumam.gbaduo.library.ui.Pixel
import com.khumam.gbaduo.library.ui.PixelButton

/** Providers > Artwork folder: a folder another launcher filled with covers. GBADuo only reads it. */
class ArtworkFolderActivity : PixelPage() {

    private lateinit var current: TextView
    private lateinit var stop: PixelButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page(getString(R.string.provider_folder))
        note(getString(R.string.scraping_folder_note), top = 0)
        current = hud(emptyList())
        content.addView(current, spaced(16, ViewGroup.LayoutParams.WRAP_CONTENT))
        val choose = button(getString(R.string.scraping_folder_choose), Pixel.YELLOW) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK)
        }
        content.addView(choose, spaced(20, ViewGroup.LayoutParams.WRAP_CONTENT))
        stop = button(getString(R.string.scraping_folder_stop), Pixel.WHITE_SOFT) {
            ArtworkPrefs.clearFolder(this)
            render()
        }
        content.addView(stop, spaced(14, ViewGroup.LayoutParams.WRAP_CONTENT))
        render()
        focusFirst(choose)
    }

    private fun render() {
        val set = ArtworkPrefs.folderUri(this) != null
        current.text = hud(listOf(
            getString(R.string.scraping_folder_label) to
                if (set) ArtworkPrefs.folderName(this) ?: getString(R.string.save_folder_unreachable) else getString(R.string.scraping_folder_none),
        )).text
        stop.visibility = if (set) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { ArtworkPrefs.useFolder(this, uri) }
        render()
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
