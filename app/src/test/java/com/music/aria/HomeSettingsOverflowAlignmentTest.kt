package com.music.aria

import androidx.compose.ui.unit.dp
import com.music.aria.ui.components.HomeFloatingActionRestOffset
import com.music.aria.ui.components.standaloneTopControlPadding
import com.music.aria.ui.components.FloatingTopChromeHostHeight
import com.music.aria.ui.components.topChromeChildOffset
import com.music.aria.ui.components.floatingTopChromeContentPadding
import com.music.aria.ui.components.TopBarContentHeight
import com.music.aria.ui.components.TopBarContentGap
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's floating ⋯ follows its large in-list heading, then returns to top chrome. */
class HomeSettingsOverflowAlignmentTest {
    @Test fun settingsLauncherAlignsWithTheUnscrolledHomeHeading() {
        assertEquals(18f, homeSettingsOverflowOffsetDp(true, 0, 0f))
    }

    @Test fun scrollingMovesLauncherSmoothlyBackIntoTheToolbar() {
        assertEquals(2f, homeSettingsOverflowOffsetDp(true, 0, 16f))
        assertEquals(0f, homeSettingsOverflowOffsetDp(true, 0, 40f))
        assertEquals(0f, homeSettingsOverflowOffsetDp(true, 1, 0f))
    }

    @Test fun standaloneHeadersPlaceControlsAtTheSameRestingYAsHome() {
        // A 52dp toolbar centering a 40dp circle at +18dp begins at +24dp.
        assertEquals(18.dp, HomeFloatingActionRestOffset)
        assertEquals(24.dp, standaloneTopControlPadding(40.dp)) // Saved filters
        assertEquals(16.dp, standaloneTopControlPadding(56.dp)) // Playlist wizard
        assertEquals(20.dp, standaloneTopControlPadding(48.dp)) // Login overlays
    }

    @Test fun artworkAndTitlesClearTheRestingControlsAtEveryStatusBarInset() {
        assertEquals(76.dp, FloatingTopChromeHostHeight)
        assertEquals(6.dp, topChromeChildOffset(HomeFloatingActionRestOffset))
        assertEquals((-12).dp, topChromeChildOffset(0.dp))
        val bottom = FloatingTopChromeHostHeight / 2 +
            topChromeChildOffset(HomeFloatingActionRestOffset) + 20.dp
        assertEquals(64.dp, bottom)
        assertEquals(TopBarContentGap, FloatingTopChromeHostHeight - bottom)
        for (inset in listOf(0.dp, 24.dp, 48.dp)) {
            assertEquals(inset + bottom + TopBarContentGap, floatingTopChromeContentPadding(inset))
        }
        assertEquals(TopBarContentHeight / 2, FloatingTopChromeHostHeight / 2 + topChromeChildOffset(0.dp))
    }

    @Test fun otherPagesAndNestedDetailsNeverReceiveHomeOffset() {
        assertEquals(0f, homeSettingsOverflowOffsetDp(false, 0, 0f))
    }
}
