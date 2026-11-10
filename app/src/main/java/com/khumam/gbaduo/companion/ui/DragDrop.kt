package com.khumam.gbaduo.companion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import com.khumam.gbaduo.companion.tree.CompanionNode
import com.khumam.gbaduo.companion.tree.CompanionTree

/** The widgets that take drops (`onDrop`) and where they are, so a drag can find what is under the finger. */
internal class DropRegistry {
    val targets = LinkedHashMap<CompanionNode, LayoutCoordinates>()
    private var over: CompanionNode? = null

    /** The widget being dragged. It and its ancestors are drawn above everything else, so it can cross the whole screen. */
    var dragging: CompanionNode? by mutableStateOf(null)

    fun isLifted(node: CompanionNode): Boolean {
        var n = dragging
        while (n != null) {
            if (n === node) return true
            n = n.parent
        }
        return false
    }

    /** The innermost drop target under [point] (root coordinates), never [exclude] itself. */
    fun hit(point: Offset, exclude: CompanionNode): CompanionNode? =
        targets.entries
            .filter { (node, coords) -> node !== exclude && coords.isAttached && coords.boundsInRoot().contains(point) }
            .minByOrNull { (_, coords) -> coords.boundsInRoot().let { it.width * it.height } }
            ?.key

    /** Marks [target] as the widget the drag is over (`:drop-over`), clearing the one before. */
    fun setOver(tree: CompanionTree, target: CompanionNode?) {
        if (target === over) return
        over?.let { tree.setDropOver(it, false) }
        over = target
        target?.let { tree.setDropOver(it, true) }
    }

    /** The drop target the drag ends on, then no target. */
    fun takeOver(tree: CompanionTree): CompanionNode? {
        val target = over
        setOver(tree, null)
        return target
    }
}

internal val LocalDropRegistry = staticCompositionLocalOf { DropRegistry() }

/** Draws a dragged widget above its siblings. */
internal const val DRAG_Z = 10_000
