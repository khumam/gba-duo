package com.khumam.gbaduo.emu.shaderlib

/** A row of the library browser: a folder to open or a preset to apply. [path] is the repo path (a folder has no extension). */
sealed interface ShaderNode {
    val path: String
    val name: String

    data class Folder(override val path: String) : ShaderNode {
        override val name get() = path.substringAfterLast('/')
    }

    data class Preset(override val path: String) : ShaderNode {
        override val name get() = path.substringAfterLast('/').removeSuffix(".slangp")
    }
}

/**
 * The libretro repo's folders as the browser shows them, built from the paths of its `.slangp` presets: a folder appears
 * only when a preset lives somewhere below it, so support folders (`include`, `shaders`, `spec`, ...) stay out of sight.
 */
class ShaderTree(presets: List<String>) {
    private val folders = HashMap<String, MutableSet<String>>()     // folder path -> its subfolder paths
    private val files = HashMap<String, MutableList<String>>()      // folder path -> the presets directly in it

    init {
        for (preset in presets) {
            val folder = preset.substringBeforeLast('/', "")
            files.getOrPut(folder) { mutableListOf() }.add(preset)
            var child = folder
            while (child.isNotEmpty()) {
                val parent = child.substringBeforeLast('/', "")
                folders.getOrPut(parent) { sortedSetOf() }.add(child)
                child = parent
            }
        }
    }

    /** What is in [folder] ("" is the root): subfolders first, then presets, each sorted ignoring case. */
    fun children(folder: String): List<ShaderNode> {
        val subfolders = folders[folder].orEmpty().map { ShaderNode.Folder(it) }.sortedBy { it.name.lowercase() }
        val presets = files[folder].orEmpty().map { ShaderNode.Preset(it) }.sortedBy { it.name.lowercase() }
        return subfolders + presets
    }
}
