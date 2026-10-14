package com.khumam.gbaduo.companion

import android.content.Context
import android.util.Log
import com.khumam.gbaduo.companion.style.CssParser
import com.khumam.gbaduo.companion.style.Stylesheet
import java.io.File

/** Loads the stylesheets a pack is rendered with: the app's built-in one, then the pack's own. */
object PackStyles {

    const val BUILT_IN_ASSET = "companion/default.css"
    private const val TAG = "PackStyles"

    fun builtIn(context: Context): List<Stylesheet> {
        val css = context.assets.open(BUILT_IN_ASSET).bufferedReader().use { it.readText() }
        return listOf(CssParser.parse(css, sourceName = BUILT_IN_ASSET))
    }

    /** The manifest's `styles`, in order; a missing file or one outside the pack is skipped with a warning. */
    fun forPack(manifest: PackManifest, packDir: File): List<Stylesheet> = manifest.styleFiles.mapNotNull { path ->
        val file = CssParser.resolveUrl(path, packDir, packDir)?.takeIf { it.isFile }
        if (file == null) {
            Log.w(TAG, "Pack '${manifest.id}': stylesheet '$path' not found")
            return@mapNotNull null
        }
        CssParser.parse(file.readText(), baseDir = file.parentFile, packDir = packDir, sourceName = path).also {
            CompanionPresentation.logWarnings(path, it.warnings)
        }
    }
}
