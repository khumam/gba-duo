package com.khumam.gbaduo.settings

import com.khumam.gbaduo.emu.InUse
import com.khumam.gbaduo.emu.ShaderChoice
import com.khumam.gbaduo.emu.ShaderLibrary
import com.khumam.gbaduo.emu.ShaderPreset
import com.khumam.gbaduo.emu.shaderlib.ShaderNode
import com.khumam.gbaduo.emu.shaderlib.ShaderTree

/** One row of the Shader page. */
sealed interface ShaderRow {
    /** What shader is in use now, and why; always the first row. */
    data class InUseCard(val inUse: InUse) : ShaderRow
    data class Header(val kind: ShaderPageModel.HeaderKind) : ShaderRow
    data class Builtin(val preset: ShaderPreset, val active: Boolean) : ShaderRow
    /** Opens the libretro library browser. */
    object LibraryEntry : ShaderRow
    data class Folder(val node: ShaderNode.Folder) : ShaderRow
    data class Preset(val node: ShaderNode.Preset, val downloaded: Boolean, val active: Boolean) : ShaderRow
    data class Message(val kind: ShaderPageModel.MessageKind) : ShaderRow
}

/** What the Shader page shows, as plain data (the activity turns rows into views and strings). */
object ShaderPageModel {
    enum class ListState { LOADING, READY, UNAVAILABLE }
    enum class HeaderKind { BUILTIN, LIBRARY }
    enum class MessageKind { LOADING, NO_LIST }

    /**
     * The rows for one page. [folder] null is the page's home (the card, the built-in shaders, the Library entry); "" is the
     * root of the library and anything else a folder of it. Browsing needs [tree]; without it ([listState]) the page says why
     * and the built-ins stay one tap away on the home page.
     */
    fun rows(folder: String?, tree: ShaderTree?, listState: ListState, library: ShaderLibrary, inUse: InUse): List<ShaderRow> {
        val rows = mutableListOf<ShaderRow>(ShaderRow.InUseCard(inUse))
        if (folder == null) {
            rows += ShaderRow.Header(HeaderKind.BUILTIN)
            for (preset in ShaderPreset.entries) rows += ShaderRow.Builtin(preset, inUse.choice == ShaderChoice.Builtin(preset))
            rows += ShaderRow.Header(HeaderKind.LIBRARY)
            rows += ShaderRow.LibraryEntry
            return rows
        }
        if (tree == null || listState != ListState.READY) {
            rows += ShaderRow.Message(if (listState == ListState.LOADING) MessageKind.LOADING else MessageKind.NO_LIST)
            return rows
        }
        for (node in tree.children(folder)) {
            rows += when (node) {
                is ShaderNode.Folder -> ShaderRow.Folder(node)
                is ShaderNode.Preset -> ShaderRow.Preset(node, library.isDownloaded(node.path), inUse.choice == ShaderChoice.Library(node.path))
            }
        }
        return rows
    }

    /** The page title for a folder: its own name, "" for the library root, null for the home page. */
    fun folderName(folder: String?): String? = folder?.substringAfterLast('/')
}
