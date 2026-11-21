package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGenreFloatingBackClearanceTest {
    @Test fun pushedExploreGenreStartsBelowFullFloatingTopChrome() {
        assertEquals(108.dp, homeTitleTopInset(108.dp, reserveFloatingBackSpace = true))
        assertEquals(92.dp, homeTitleTopInset(92.dp, reserveFloatingBackSpace = true))
    }

    @Test fun rootHomeTitleSharesTheSameBaselineAsLibraryAndExplore() {
        assertEquals(48.dp, homeTitleTopInset(108.dp, reserveFloatingBackSpace = false))
        assertEquals(0.dp, homeTitleTopInset(20.dp, reserveFloatingBackSpace = false))
        val safeInset = 108.dp
        val home = homeTitleTopInset(safeInset, reserveFloatingBackSpace = false) + 8.dp
        val library = rootLandingTitleListTopInset(safeInset, titleInnerTop = 4.dp) + 4.dp
        val explore = rootLandingTitleListTopInset(safeInset, titleInnerTop = 8.dp) + 8.dp
        assertEquals(56.dp, home)
        assertEquals(home, library)
        assertEquals(home, explore)
    }
}
