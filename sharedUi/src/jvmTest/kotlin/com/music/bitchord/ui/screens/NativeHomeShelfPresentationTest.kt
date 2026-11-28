package com.music.bitchord.ui.screens

import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.HERO_CARD_RATIO
import com.music.bitchord.ui.components.NativeMediaCardMetrics
import com.music.bitchord.ui.components.heroCardWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeHomeShelfPresentationTest {
    @Test fun recentCoversAreSquareWithCaptionsOutsideEvenWhenFirstOrAfterEditorial() {
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation("Recents", 0, true))
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation("Recents", 2, true))
        assertEquals(HomeShelfPresentation.RECENT_COVERS, homeShelfPresentation(" recents ", 1, false))
        assertEquals(1f, NativeMediaCardMetrics.recentCoverRatio, 0.00001f)
        assertEquals(NativeMediaCardMetrics.shelfWidth, NativeMediaCardMetrics.recentCoverWidth)
    }

    @Test fun onlyTheFirstNonRecentShelfCanBeEditorialHero() {
        assertEquals(HomeShelfPresentation.EDITORIAL_HERO, homeShelfPresentation("Lançamentos", 0, true))
        assertEquals(HomeShelfPresentation.SQUARE_SHELF, homeShelfPresentation("Lançamentos", 0, false))
        assertEquals(HomeShelfPresentation.SQUARE_SHELF, homeShelfPresentation("Escolha a dedo", 1, true))
    }

    @Test fun pendingAndEmptySectionsNeverOccupyEditorialLayout() {
        val item = com.music.bitchord.data.model.ShelfItem("Album", "Artist", null, null, "itunes:br:album:1")
        val first = com.music.bitchord.data.model.HomeShelf("Lançamentos", listOf(item))
        val pending = com.music.bitchord.data.model.HomeShelf("Playlists", emptyList())
        val last = com.music.bitchord.data.model.HomeShelf("Mais do catálogo", listOf(item))
        assertEquals(listOf(first, last), homeReadyEditorialShelves(listOf(first, pending, last)))
        assertEquals(listOf(first, pending.copy(items = listOf(item)), last),
            homeReadyEditorialShelves(listOf(first, pending.copy(items = listOf(item)), last)))
        assertEquals(emptyList<com.music.bitchord.data.model.HomeShelf>(), homeReadyEditorialShelves(listOf(pending)))
    }

    @Test fun editorialHeroIsTallAndLeavesTheNextCardVisible() {
        assertEquals(0.75f, HERO_CARD_RATIO, 0.00001f)
        assertEquals(223.2f, heroCardWidth(360.dp).value, 0.01f)
        assertEquals(320.dp, heroCardWidth(700.dp))
    }
}
