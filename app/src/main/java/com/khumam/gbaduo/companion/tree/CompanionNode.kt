package com.khumam.gbaduo.companion.tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import com.khumam.gbaduo.companion.Widget
import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.StyleNode

/**
 * One element of the companion screen: a widget, or a part the tree generates
 * for one (a bar's fill, a tab, ...). Everything the UI reads is Compose state,
 * so a change only recomposes the nodes that read it.
 */
class CompanionNode internal constructor(
    val style: StyleNode,
    val kind: Kind,
    val widget: Widget?,
) {
    enum class Kind { WIDGET, FILL, LABEL, TAB_BAR, TAB, TAB_CONTENT, EMPTY }

    var parent: CompanionNode? = null
        internal set

    val children: SnapshotStateList<CompanionNode> = mutableStateListOf()

    /** Resolved CSS; null only before the first resolve. */
    var computed: ComputedStyle? by mutableStateOf(null)
        internal set

    /** Text before `text-transform`, for text-like nodes; null for everything else. */
    var text: String? by mutableStateOf(null)
        internal set

    var image: ImageBitmap? by mutableStateOf(null)
        internal set

    var hiddenByBinding: Boolean by mutableStateOf(false)
        internal set

    var hiddenByTabs: Boolean by mutableStateOf(false)
        internal set

    /** Set for nodes that react to taps (tabs). */
    var onClick: (() -> Unit)? = null
        internal set

    val hidden: Boolean get() = hiddenByBinding || hiddenByTabs
    val isImage: Boolean get() = widget is Widget.Image

    internal val bindings = mutableListOf<(Any?) -> Unit>()
    internal var managesChildren = false
    internal var imageKey: String? = null
}
