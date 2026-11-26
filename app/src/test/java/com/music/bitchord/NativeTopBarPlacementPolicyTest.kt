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
        assertEquals(18.dp, HomeFloatingActionRestOffset)
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
        // 52dp toolbar, +18dp offset, 40dp circle = bottom at 64dp.
        // Its content starts at 76dp, or 100dp after a 24dp status bar.
        assertEquals(76.dp, floatingTopChromeContentPadding(0.dp))
        assertEquals(100.dp, floatingTopChromeContentPadding(24.dp))
    }

    @Test fun playlistHeadingDoesNotAddASecondFloatingOffset() {
        assertEquals(8.dp, playlistLibraryHeadingExtraTopPadding())
        assertEquals(76.dp, FloatingTopChromeHostHeight)
        assertEquals(6.dp, topChromeChildOffset(18.dp))
        assertEquals((-12).dp, topChromeChildOffset(0.dp))
    }
}
