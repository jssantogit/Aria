package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGenreFloatingBackClearanceTest {
    @Test fun pushedExploreGenreMatchesNativeLibraryCategoryHeadingBand() {
        assertEquals(100.dp, homeTitleTopInset(100.dp, reserveFloatingBackSpace = true))
        assertEquals(84.dp, homeTitleTopInset(84.dp, reserveFloatingBackSpace = true))
        val safe = 100.dp
        val genreTextTop = homeTitleTopInset(safe, reserveFloatingBackSpace = true) + 8.dp
        val libraryCategoryTextTop =
            com.music.bitchord.ui.components.NativePageHeaderMetrics.categoryTitleTopInset(safe)
        assertEquals(libraryCategoryTextTop, genreTextTop)
    }

    @Test fun rootHomeTitleSharesTheSameBaselineAsLibraryAndExplore() {
        assertEquals(40.dp, homeTitleTopInset(100.dp, reserveFloatingBackSpace = false))
        assertEquals(0.dp, homeTitleTopInset(20.dp, reserveFloatingBackSpace = false))
        val safeInset = 100.dp
        val home = homeTitleTopInset(safeInset, reserveFloatingBackSpace = false) + 8.dp
        val library = rootLandingTitleListTopInset(safeInset, titleInnerTop = 4.dp) + 4.dp
        val explore = rootLandingTitleListTopInset(safeInset, titleInnerTop = 8.dp) + 8.dp
        assertEquals(48.dp, home)
        assertEquals(home, library)
        assertEquals(home, explore)
    }
}
