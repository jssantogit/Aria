package com.music.bitchord

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HomeFloatingActionRestOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTopBarPlacementPolicyTest {
    @Test fun largeTitleRootPagesKeepTheirSpecialActionRestingLane() {
        assertEquals(
            15.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 15.dp,
                libraryRootVisible = false,
                exploreRootVisible = false,
            ),
        )
        for (root in listOf("library", "explore")) {
            assertEquals(
                HomeFloatingActionRestOffset,
                nativeTopBarActionsOffset(
                    homeRootVisible = false,
                    homeSettingsOffset = 15.dp,
                    libraryRootVisible = root == "library",
                    exploreRootVisible = root == "explore",
                ),
            )
        }
    }

    @Test fun playlistHeadingDoesNotAddHomeFloatingActionDisplacement() {
        assertEquals(8.dp, playlistLibraryHeadingExtraTopPadding())
    }

    @Test fun pushedContentAndSearchUseTheUnshiftedGlobalBar() {
        assertEquals(
            0.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = false,
                homeSettingsOffset = 20.dp,
                libraryRootVisible = false,
                exploreRootVisible = false,
            ),
        )
        assertEquals(0.dp, nativeTopBarBackOffset())
    }

    @Test fun aVisibleHomeTakesPrecedenceOverAnyStaleRootFlags() {
        assertEquals(
            8.dp,
            nativeTopBarActionsOffset(
                homeRootVisible = true,
                homeSettingsOffset = 8.dp,
                libraryRootVisible = true,
                exploreRootVisible = true,
            ),
        )
    }
}
