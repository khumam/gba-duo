package com.khumam.gbaduo.companion.render

import android.view.View
import com.khumam.gbaduo.companion.Widget
import com.khumam.gbaduo.companion.style.ComputedStyle
import com.khumam.gbaduo.companion.style.StyleNode
import com.khumam.gbaduo.companion.style.Transform

/** One rendered element: its style node, its view, and the bindings that update it each tick. */
internal class UiNode(val style: StyleNode, val view: View, val kind: Kind, val widget: Widget?) {

    /** Widgets, and the parts the renderer generates for them. */
    enum class Kind { WIDGET, FILL, LABEL, TAB_BAR, TAB, TAB_CONTENT, EMPTY }

    var parent: UiNode? = null
    val children = mutableListOf<UiNode>()

    /** Run on every update with the binding context (snapshot or list-item scope). */
    val bindings = mutableListOf<(Any?) -> Unit>()

    /** True when this node updates its own children (lists, with a per-item scope). */
    var managesChildren = false

    var computed: ComputedStyle? = null
    var hiddenByBinding = false
    var hiddenByTabs = false

    /** Text before `text-transform`. */
    var rawText: String? = null

    /** Last image source shown, so bound images are only replaced when the key changes. */
    var imageKey: String? = null
    var imageLoaded = false

    val transitions = Transitions()
    var shownTransform: Transform = Transform.NONE
    var transformOrigin: Pair<Float, Float> = 0.5f to 0.5f
    var relativeOffset: Pair<Float, Float> = 0f to 0f
    var transformListenerInstalled = false
}
