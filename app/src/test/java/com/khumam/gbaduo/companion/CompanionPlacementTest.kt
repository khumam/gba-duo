package com.khumam.gbaduo.companion

import com.khumam.gbaduo.companion.CompanionPlacement.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPlacementTest {

    @Test
    fun gameOnTheMainScreenPutsTheCompanionOnTheSecondAsAPresentation() {
        assertEquals(Target.Presentation(2), CompanionPlacement.choose(gameDisplayId = 0, presentationDisplayIds = listOf(2)))
    }

    @Test
    fun gameOnTheSecondScreenPutsTheCompanionOnTheMainAsAnActivity() {
        // Android lists the game's own (secondary) display as a presentation display too.
        assertEquals(Target.Activity(0), CompanionPlacement.choose(gameDisplayId = 2, presentationDisplayIds = listOf(2)))
    }

    @Test
    fun anotherSecondaryScreenIsPreferredOverTheMainOne() {
        assertEquals(Target.Presentation(3), CompanionPlacement.choose(gameDisplayId = 2, presentationDisplayIds = listOf(2, 3)))
    }

    @Test
    fun aSingleScreenHasNoCompanion() {
        assertEquals(Target.None, CompanionPlacement.choose(gameDisplayId = 0, presentationDisplayIds = emptyList()))
    }
}
