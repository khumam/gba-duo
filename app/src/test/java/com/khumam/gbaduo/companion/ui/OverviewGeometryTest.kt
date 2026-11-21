package com.khumam.gbaduo.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewGeometryTest {

    @Test fun `decoding halves the picture while it stays at least as wide as the card`() {
        assertEquals(2, OverviewGeometry.sampleSize(sourceWidth = 1292, targetWidth = 520))
        assertEquals(1, OverviewGeometry.sampleSize(sourceWidth = 1292, targetWidth = 700))
        assertEquals(4, OverviewGeometry.sampleSize(sourceWidth = 4000, targetWidth = 900))
    }

    @Test fun `a picture smaller than the card is not shrunk`() {
        assertEquals(1, OverviewGeometry.sampleSize(sourceWidth = 300, targetWidth = 520))
    }

    @Test fun `a tall picture is scaled to the card's width`() {
        assertEquals(520 to 1000, OverviewGeometry.coverSize(sourceWidth = 1040, sourceHeight = 2000, width = 520, height = 260))
    }

    @Test fun `a wide picture is scaled to the card's height`() {
        assertEquals(1040 to 260, OverviewGeometry.coverSize(sourceWidth = 4000, sourceHeight = 1000, width = 520, height = 260))
    }

    @Test fun `the crop starts at the top and is centred sideways`() {
        assertEquals(0, OverviewGeometry.cropLeft(scaledWidth = 520, width = 520))
        assertEquals(130, OverviewGeometry.cropLeft(scaledWidth = 780, width = 520))
    }
}
