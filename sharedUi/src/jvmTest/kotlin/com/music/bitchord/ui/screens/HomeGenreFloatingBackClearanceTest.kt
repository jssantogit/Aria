package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGenreFloatingBackClearanceTest {
    @Test fun pushedExploreGenreStartsBelowFullFloatingTopChrome() {
        assertEquals(116.dp, homeTitleTopInset(116.dp, reserveFloatingBackSpace = true))
        assertEquals(92.dp, homeTitleTopInset(92.dp, reserveFloatingBackSpace = true))
    }

    @Test fun rootHomeTitleSharesTheSameBaselineAsLibraryAndExplore() {
        assertEquals(56.dp, homeTitleTopInset(116.dp, reserveFloatingBackSpace = false))
        assertEquals(0.dp, homeTitleTopInset(20.dp, reserveFloatingBackSpace = false))
        val safeInset = 116.dp
        val home = homeTitleTopInset(safeInset, reserveFloatingBackSpace = false) + 8.dp
        val library = rootLandingTitleListTopInset(safeInset, titleInnerTop = 4.dp) + 4.dp
        val explore = rootLandingTitleListTopInset(safeInset, titleInnerTop = 8.dp) + 8.dp
        assertEquals(64.dp, home)
        assertEquals(home, library)
        assertEquals(home, explore)
    }
}
