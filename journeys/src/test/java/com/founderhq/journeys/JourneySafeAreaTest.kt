package com.founderhq.journeys

import org.junit.Assert.assertEquals
import org.junit.Test

class JourneySafeAreaTest {
    @Test fun fullScreenConvertsSystemBarsToCssPixels() {
        assertEquals(
            JourneySafeArea(24f, 0f, 32f, 0f),
            JourneySafeArea.overlapping(72, 0, 96, 0, 0, 0, 1080, 2400, 1080, 2400, 3f),
        )
    }

    @Test fun insetNativeParentDoesNotDoublePad() {
        assertEquals(
            JourneySafeArea(0f, 0f, 0f, 0f),
            JourneySafeArea.overlapping(72, 0, 96, 0, 0, 72, 1080, 2232, 1080, 2400, 3f),
        )
    }

    @Test fun partialOverlapAndLandscapeCutoutsUseViewBounds() {
        assertEquals(
            JourneySafeArea(0f, 10f, 0f, 20f),
            JourneySafeArea.overlapping(0, 60, 0, 90, 30, 100, 2340, 800, 2400, 1080, 3f),
        )
    }
}
