package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A lifted menu must never disappear behind the Android navigation capsule. */
class HeldContextMenuGeometryTest {
    @Test fun menuNeverMovesSourceCardEvenWhenActionsDoNotFitBelow() {
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = 420f, sourceHeight = 110f, viewportTop = 40, viewportBottom = 780,
            gap = 10, requestedMenuHeight = 540,
        )
        assertEquals(420f, placement.previewTop)
        assertFalse(placement.menuBelow)
        assertTrue(placement.menuTop + placement.menuMaxHeight <= 780)
        assertTrue(placement.menuMaxHeight > 0)
    }

    @Test fun bottomCardKeepsOriginalPositionAndScrollsActionsAbove() {
        val placement = heldMenuAnchoredVerticalPlacement(
            sourceTop = 670f, sourceHeight = 90f, viewportTop = 40, viewportBottom = 780,
            gap = 10, requestedMenuHeight = 500,
        )
        assertEquals(670f, placement.previewTop)
        assertFalse(placement.menuBelow)
        assertTrue(placement.menuMaxHeight <= 620)
    }

    @Test fun floatingBarsAreExcludedFromTheHeldMenuViewport() {
        assertEquals(770, heldMenuVisibleBottom(
            viewportHeight = 1000, systemBottomInset = 30, chromeReserve = 200,
        ))
    }

    @Test fun manyActionsKeepEnoughRoomForReadingAndACompactPreview() {
        val menuHeight = heldMenuMaxHeight(availableHeight = 600, minPreview = 100, gap = 12)
        assertTrue(menuHeight >= 400)
        assertTrue(menuHeight + 100 + 12 <= 600)
        val scale = heldMenuPreviewScale(
            plateWidth = 400, plateHeight = 420, viewportWidth = 430,
            margin = 20, availableHeight = 600, menuHeight = menuHeight, gap = 12,
        )
        assertTrue(scale < 0.6f)
        assertTrue(420 * scale + menuHeight + 12 <= 600)
    }

    @Test fun ordinaryShortMenusDoNotUnnecessarilyShrinkSmallRows() {
        assertEquals(1f, heldMenuPreviewScale(
            plateWidth = 220, plateHeight = 100, viewportWidth = 430,
            margin = 20, availableHeight = 600, menuHeight = 240, gap = 12,
        ))
    }

    @Test fun blurIsEnabledForHeldItemsExceptWhenAccessibilityDisablesIt() {
        assertTrue(shouldBlurHeldContextBackdrop(heldVisible = true, reduceDynamicBlur = false))
        assertFalse(shouldBlurHeldContextBackdrop(heldVisible = false, reduceDynamicBlur = false))
        assertFalse(shouldBlurHeldContextBackdrop(heldVisible = true, reduceDynamicBlur = true))
    }

    @Test fun parentBlurringAndHazeSamplingAreMutuallyExclusive() {
        assertFalse(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = true, reduceDynamicBlur = false,
        ))
        assertTrue(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = false, reduceDynamicBlur = false,
        ))
        assertFalse(heldMenuNeedsOwnHazeSource(
            hostProvidesBlur = false, reduceDynamicBlur = true,
        ))
    }
}
