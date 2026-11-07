package com.khumam.gbaduo.emu

import android.content.Context
import java.io.File

/**
 * The libretro slang shaders downloaded to this device, laid out exactly like the repo
 * (`root/crt/crt-geom.slangp`) so the relative paths inside presets keep working.
 */
class ShaderLibrary(val root: File) {

    /** The file for a repo path; throws if the path would leave [root]. */
    fun file(path: String): File {
        require(isSafePath(path)) { "shader path outside the library: $path" }
        val file = File(root, path)
        require(file.canonicalFile.startsWith(root.canonicalFile)) { "shader path outside the library: $path" }
        return file
    }

    fun isDownloaded(path: String): Boolean =
        isSafePath(path) && runCatching { file(path).isFile }.getOrDefault(false)

    /** Removes the preset file (and its folders when they end up empty); the support files it shared stay. */
    fun delete(path: String): Boolean {
        val file = runCatching { file(path) }.getOrNull() ?: return false
        if (!file.delete()) return false
        var dir = file.parentFile
        while (dir != null && dir != root && dir.isDirectory && dir.list().isNullOrEmpty()) {
            dir.delete()
            dir = dir.parentFile
        }
        return true
    }

    companion object {
        fun of(context: Context) = ShaderLibrary(File(context.filesDir, "shaders/slang"))

        /** A relative path with no `..`, no leading `/`, and something in it. */
        fun isSafePath(path: String): Boolean =
            path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') && path.split('/').none { it == ".." || it.isEmpty() }
    }
}
