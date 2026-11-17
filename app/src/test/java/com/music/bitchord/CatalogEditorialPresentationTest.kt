package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.ProviderKey
import com.music.bitchord.ui.musicBrainzHomeEditorialShelves
import com.music.bitchord.ui.personalHomeShelves
import com.music.bitchord.ui.selectCatalogEditorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogEditorialPresentationTest {
    private val mb = ProviderKey("musicbrainz")
    private val apple = ProviderKey("itunes")
    private fun shelf(id: String): List<HomeShelf> =
        listOf(HomeShelf("Releases", listOf(ShelfItem(
            title = "Album", subtitle = "Artist", thumbnailUrl = null,
            videoId = null, browseId = id,
        ))))

    @Test fun priorityChoosesProviderNativeCardsAndRespectsReorder() {
        val states = mapOf<ProviderKey, UiState<List<HomeShelf>>>(
            mb to UiState.Success(shelf("catalog:RELEASE_GROUP:123")),
            apple to UiState.Success(shelf("itunes:br:album:456")),
        )
        assertEquals(mb, selectCatalogEditorial(listOf(mb, apple), states).provider)
        assertEquals("catalog:RELEASE_GROUP:123",
            (selectCatalogEditorial(listOf(mb, apple), states).state as UiState.Success)
                .data.single().items.single().browseId)
        assertEquals(apple, selectCatalogEditorial(listOf(apple, mb), states).provider)
    }

    @Test fun loadingPrimaryCannotLeakPreviouslyCachedSecondary() {
        val selected = selectCatalogEditorial(listOf(mb, apple), mapOf(
            mb to UiState.Loading, apple to UiState.Success(shelf("itunes:br:album:5")),
        ))
        assertEquals(mb, selected.provider)
        assertTrue(selected.state == UiState.Loading)
    }

    @Test fun emptyResultAllowsOnlyAuthorizedOrderedSibling() {
        val states = mapOf<ProviderKey, UiState<List<HomeShelf>>>(
            mb to UiState.Success(emptyList()),
            apple to UiState.Success(shelf("itunes:br:album:5")),
        )
        assertEquals(apple, selectCatalogEditorial(listOf(mb, apple), states).provider)
        assertNull(selectCatalogEditorial(emptyList(), states).provider)
        assertTrue((selectCatalogEditorial(emptyList(), states).state as UiState.Success)
            .data.isEmpty())
    }

    @Test fun personalAndCatalogEditorialStayPartitioned() {
        val mixed = listOf(HomeShelf("Recent", listOf(
            ShelfItem("Local", "Artist", null, null, "local:pick:a"),
            ShelfItem("MB", "Artist", null, null, "catalog:RELEASE_GROUP:b"),
            ShelfItem("Apple", "Artist", null, null, "itunes:br:album:3"),
        )))
        assertEquals(listOf("local:pick:a"),
            personalHomeShelves(mixed).flatMap { it.items }.map { it.browseId })
        val releases = listOf(HomeShelf("Releases", listOf(
            ShelfItem("MB", "Artist", null, null, "catalog:RELEASE_GROUP:b"),
            ShelfItem("Apple", "Artist", null, null, "itunes:br:album:3"),
        )))
        assertEquals(listOf("catalog:RELEASE_GROUP:b"),
            musicBrainzHomeEditorialShelves(releases, "Releases")
                .flatMap { it.items }.map { it.browseId })
    }
}
