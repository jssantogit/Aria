package com.music.bitchord

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HomeFloatingActionRestOffset
import com.music.bitchord.ui.components.FloatingTopChromeHostHeight
import com.music.bitchord.ui.components.topChromeChildOffset
import com.music.bitchord.ui.components.floatingTopChromeContentPadding
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTopBarPlacementPolicyTest {
    @Test fun homeKeepsItsScrollSensitiveOffset() {
        assertEquals(
            15.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 15.dp,
            ),
        )
        assertEquals(
            0.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 0.dp,
            ),
        )
    }

    @Test fun allOtherRoutesAndSearchFieldRestAtHomeY() {
        assertEquals(26.dp, HomeFloatingActionRestOffset)
        assertEquals(
            HomeFloatingActionRestOffset,
            nativeTopBarActionsOffset(
                homeRootVisible = false,
                homeSettingsOffset = 20.dp,
            ),
        )
        assertEquals(HomeFloatingActionRestOffset, nativeTopBarBackOffset())
    }

    @Test fun contentClearsEntireFloatingPuckAndHas12DpGap() {
        // 52dp toolbar, +26dp offset, 40dp circle = bottom at 72dp.
        // Its content starts at 84dp, or 108dp after a 24dp status bar.
        assertEquals(84.dp, floatingTopChromeContentPadding(0.dp))
        assertEquals(108.dp, floatingTopChromeContentPadding(24.dp))
    }

    @Test fun playlistHeadingDoesNotAddASecondFloatingOffset() {
        assertEquals(8.dp, playlistLibraryHeadingExtraTopPadding())
        assertEquals(84.dp, FloatingTopChromeHostHeight)
        assertEquals(10.dp, topChromeChildOffset(26.dp))
        assertEquals((-16).dp, topChromeChildOffset(0.dp))
    }
}
