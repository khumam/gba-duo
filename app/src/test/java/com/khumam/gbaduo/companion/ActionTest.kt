package com.khumam.gbaduo.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTest {

    private fun parse(json: String): Pair<List<Action>, List<String>> {
        val warnings = mutableListOf<String>()
        val wrapped = JsonTree.parseObject("""{"v":$json}""")["v"]
        return Actions.parse(wrapped, warnings) to warnings
    }

    @Test
    fun anObjectRunsSetThenNavigationThenCall() {
        val (actions, warnings) = parse("""{"call":"open","args":["{@index}",2],"navigate":"detail","set":{"selected":"{@index}"}}""")
        assertTrue(warnings.isEmpty())
        assertEquals(
            listOf(
                Action.Set(mapOf("selected" to "{@index}")),
                Action.Navigate("detail", "page"),
                Action.Call("open", listOf("{@index}", 2L)),
            ),
            actions,
        )
    }

    @Test
    fun listsAndTheBackShorthand() {
        val (actions, warnings) = parse("""["back", {"back":"menu"}, {"navigate":"x","state":"menu"}, {"back":true}]""")
        assertTrue(warnings.isEmpty())
        assertEquals(
            listOf(Action.Back("page"), Action.Back("menu"), Action.Navigate("x", "menu"), Action.Back("page")),
            actions,
        )
    }

    @Test
    fun aSingleCallArgumentNeedsNoList() {
        assertEquals(listOf(Action.Call("heal", listOf("all"))), parse("""{"call":"heal","args":"all"}""").first)
    }

    @Test
    fun problemsAreSkippedWithWarnings() {
        val (actions, warnings) = parse(
            """[ "forward", 3, {"navigate":"a","back":true}, {"set":{"bad key":1,"ok":2}},
                 {"call":"no-dashes"}, {"state":"x y","navigate":"a"}, {"go":"a"} ]""",
        )
        assertEquals(listOf<Action>(Action.Set(mapOf("ok" to 2L))), actions)
        assertEquals(7, warnings.size)
    }

    @Test
    fun scriptResultsDropCallWithAWarning() {
        val warnings = mutableListOf<String>()
        val actions = Actions.parseScriptResult(mapOf("navigate" to "battle", "call" to "heal"), warnings)
        assertEquals(listOf<Action>(Action.Navigate("battle", "page")), actions)
        assertEquals(1, warnings.size)
    }
}
