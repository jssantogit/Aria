package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The in-list genre heading must start BELOW the independent global Back puck. */
class HomeGenreFloatingBackClearanceTest {
    @Test fun pushedExploreGenresClearHomeAlignedBackButton() {
        assertEquals(128.dp, homeTitleTopInset(116.dp, reserveFloatingBackSpace = true))
        assertEquals(104.dp, homeTitleTopInset(92.dp, reserveFloatingBackSpace = true))
    }

    @Test fun regularHomeTitleKeepsItsApprovedRaisedPosition() {
        assertEquals(88.dp, homeTitleTopInset(116.dp, reserveFloatingBackSpace = false))
        assertEquals(0.dp, homeTitleTopInset(20.dp, reserveFloatingBackSpace = false))
    }
}
