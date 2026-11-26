package com.khumam.gbaduo.companion.tree

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.khumam.gbaduo.companion.Action
import com.khumam.gbaduo.companion.Actions
import com.khumam.gbaduo.companion.LayoutParser
import com.khumam.gbaduo.companion.ZoomSpec
import com.khumam.gbaduo.companion.style.CssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Zoomable boxes: the `zoom` layout key, the `zoom` action and the view's scale / pan. */
class ZoomTest {

    private val defaultCss = CssParser.parse(File("src/main/assets/companion/default.css").readText())

    private fun tree(layout: String) = CompanionTree(LayoutParser.parse(layout), listOf(defaultCss), emptyList(), { null })

    @Test
    fun parsesZoomSpecs() {
        val box = LayoutParser.parse("""{"type":"box","zoom":{"min":1,"max":3,"state":"mapZoom"}}""")
        assertEquals(ZoomSpec(1f, 3f, "mapZoom"), box.meta.zoom)
        assertEquals(ZoomSpec(), LayoutParser.parse("""{"type":"box","zoom":true}""").meta.zoom)
        val bad = LayoutParser.parseWithWarnings("""{"type":"box","zoom":{"min":3,"max":1}}""")
        assertNull(bad.root.meta.zoom)
        assertEquals(1, bad.warnings.size)
    }

    @Test
    fun parsesZoomActions() {
        val warnings = mutableListOf<String>()
        assertEquals(listOf(Action.Zoom("in", "mapZoom")), Actions.parse(mapOf("zoom" to "in", "state" to "mapZoom"), warnings))
        assertEquals(listOf(Action.Zoom("reset", "zoom")), Actions.parse(mapOf("zoom" to "reset"), warnings))
        assertEquals(emptyList<String>(), warnings)
        Actions.parse(mapOf("zoom" to "sideways"), warnings)
        assertEquals(1, warnings.size)
    }

    @Test
    fun buttonsStepTheScaleWithinTheLimitsAndTheViewFollows() {
        val t = tree(
            """{"type":"column","children":[
                 {"type":"box","zoom":{"min":1,"max":2,"state":"mapZoom"}},
                 {"type":"button","onClick":{"zoom":"in","state":"mapZoom"}},
                 {"type":"button","onClick":{"zoom":"out","state":"mapZoom"}}
               ]}""",
        )
        val zoom = assertNotNull(t.root.children[0].zoom).let { t.root.children[0].zoom!! }
        zoom.size = IntSize(200, 100)
        val zoomIn = t.root.children[1].onClick!!
        val zoomOut = t.root.children[2].onClick!!
        zoomIn()
        assertEquals(1.5f, t.ui["mapZoom"])
        assertEquals(1.5f, zoom.scale)
        // zoomed around the middle of the box
        assertEquals(Offset(-50f, -25f), zoom.offset)
        zoomIn()
        assertEquals(2f, t.ui["mapZoom"])   // capped at max
        zoomOut(); zoomOut(); zoomOut()
        assertEquals(1f, t.ui["mapZoom"])    // never below min
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test
    fun aPinchZoomsAroundTheFingersStaysInsideAndCommitsWhenTheyLift() {
        var committed = 0f
        val zoom = ZoomState(ZoomSpec(1f, 4f)) { committed = it }
        zoom.size = IntSize(200, 100)
        zoom.gesture(Offset(0f, 0f), Offset.Zero, 2f)
        assertEquals(2f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)          // the top left corner stayed under the fingers
        zoom.gesture(Offset(100f, 50f), Offset(500f, 500f), 1f)
        assertEquals(Offset.Zero, zoom.offset)          // can't drag the content past its edge
        zoom.gesture(Offset(100f, 50f), Offset(-1000f, -1000f), 1f)
        assertEquals(Offset(-200f, -100f), zoom.offset) // nor past the other edge
        zoom.setTarget(1f)                               // a refresh while the fingers are down changes nothing
        assertEquals(2f, zoom.scale)
        zoom.gestureEnd()
        assertEquals(2f, committed)
    }
}
