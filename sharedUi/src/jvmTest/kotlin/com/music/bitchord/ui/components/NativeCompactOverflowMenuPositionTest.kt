package com.music.bitchord.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCompactOverflowMenuPositionTest {
    @Test fun neutralPopupOnlySamplesRealHazeWhenAvailableAndPermitted() {
        org.junit.Assert.assertTrue(nativeNeutralOverlayCanBlur(
            hazeAvailable = true, reduceBlur = false,
        ))
        org.junit.Assert.assertFalse(nativeNeutralOverlayCanBlur(
            hazeAvailable = false, reduceBlur = false,
        ))
        org.junit.Assert.assertFalse(nativeNeutralOverlayCanBlur(
            hazeAvailable = true, reduceBlur = true,
        ))
        // Standalone previews and JVM tests without an Android page host keep a
        // fully readable solid fallback instead of claiming simulated blur.
    }

    private val window = IntSize(400, 800)
    private val popup = IntSize(220, 200)

    @Test fun popupStartsAfterLauncherWithAVisibleGap() {
        val launcher = IntRect(300, 40, 340, 88)
        assertEquals(
            IntOffset(120, 100),
            compactOverflowPopupPosition(launcher, popup, window, LayoutDirection.Ltr, 12),
        )
    }

    @Test fun popupOpensAboveLauncherWhenBelowWouldClipIt() {
        val launcher = IntRect(300, 650, 340, 698)
        assertEquals(
            IntOffset(120, 438),
            compactOverflowPopupPosition(launcher, popup, window, LayoutDirection.Ltr, 12),
        )
    }

    @Test fun popupRespectsRightToLeftAnchoring() {
        val launcher = IntRect(60, 40, 100, 88)
        assertEquals(
            IntOffset(60, 100),
            compactOverflowPopupPosition(launcher, popup, window, LayoutDirection.Rtl, 12),
        )
    }

    @Test fun popupStaysWithinWindowWhenNeitherSideCanFit() {
        val launcher = IntRect(310, 280, 350, 328)
        val tall = IntSize(470, 740)
        assertEquals(
            IntOffset(0, 0),
            compactOverflowPopupPosition(launcher, tall, window, LayoutDirection.Ltr, 12),
        )
    }
}
