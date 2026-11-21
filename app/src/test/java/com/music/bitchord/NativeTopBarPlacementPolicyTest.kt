package com.music.bitchord

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HomeFloatingActionRestOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTopBarPlacementPolicyTest {
    @Test fun homeKeepsItsScrollSensitiveOffset() {
        assertEquals(
            15.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 15.dp,
                searchRootVisible = false,
            ),
        )
        assertEquals(
            0.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 0.dp,
                searchRootVisible = false,
            ),
        )
    }

    @Test fun allNonSearchPagesUseHomeRestingY() {
        assertEquals(34.dp, HomeFloatingActionRestOffset)
        assertEquals(
            HomeFloatingActionRestOffset,
            nativeTopBarActionsOffset(
                homeRootVisible = false,
                homeSettingsOffset = 20.dp,
                searchRootVisible = false,
            ),
        )
        assertEquals(HomeFloatingActionRestOffset, nativeTopBarBackOffset())
    }

    @Test fun searchRootKeepsInlineFieldAtTheUnshiftedLane() {
        assertEquals(
            0.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = false,
                homeSettingsOffset = 20.dp,
                searchRootVisible = true,
            ),
        )
    }

    @Test fun playlistHeadingReservesClearanceBelowItsFloatingBack() {
        assertEquals(42.dp, playlistLibraryHeadingExtraTopPadding())
    }
}
