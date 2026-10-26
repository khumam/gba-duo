package com.khumam.gbaduo.settings

import android.app.Activity
import android.content.Context
import com.khumam.gbaduo.R
import com.khumam.gbaduo.library.artwork.ArtworkPrefs

/**
 * Where covers can come from, in the order a scrape tries them. A new provider is one more
 * entry here plus its own settings page (see [ArtworkFolderActivity], [SteamGridDbActivity]);
 * the Providers list and the Scraping summary pick it up from this list.
 */
class Provider(
    val titleRes: Int,
    val icon: Int,
    val tile: Int,
    /** One line for the list: what is set up, or "Not set up". */
    val summary: (Context) -> String,
    val ready: (Context) -> Boolean,
    val settings: Class<out Activity>,
)

object Providers {
    val all = listOf(
        Provider(
            R.string.provider_folder, R.drawable.ic_folders, com.khumam.gbaduo.library.ui.Pixel.YELLOW,
            summary = { c ->
                if (ArtworkPrefs.folderUri(c) == null) c.getString(R.string.provider_not_set_up)
                else ArtworkPrefs.folderName(c) ?: c.getString(R.string.save_folder_unreachable)
            },
            ready = { ArtworkPrefs.folderUri(it) != null },
            settings = ArtworkFolderActivity::class.java,
        ),
        Provider(
            R.string.provider_sgdb, R.drawable.ic_settings, com.khumam.gbaduo.library.ui.Pixel.PINK,
            summary = { c -> c.getString(if (ArtworkPrefs.sgdbKey(c) != null) R.string.provider_key_saved else R.string.provider_not_set_up) },
            ready = { ArtworkPrefs.sgdbKey(it) != null },
            settings = SteamGridDbActivity::class.java,
        ),
    )

    fun anyReady(context: Context) = all.any { it.ready(context) }

    /** "Artwork folder, SteamGridDB", or that nothing is set up. */
    fun summary(context: Context): String {
        val names = all.filter { it.ready(context) }.map { context.getString(it.titleRes) }
        return if (names.isEmpty()) context.getString(R.string.provider_none_set_up) else names.joinToString(", ")
    }
}
