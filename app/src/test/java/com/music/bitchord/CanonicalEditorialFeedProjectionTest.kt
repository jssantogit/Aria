package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.CatalogEditorialSelection
import com.music.bitchord.ui.projectCanonicalHomeDisplay
import com.music.bitchord.ui.projectCanonicalHomeEditorial
import com.music.bitchord.ui.projectCanonicalHomeState
import com.music.bitchord.ui.projectCanonicalGenreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEditorialFeedProjectionTest {
    private val apple = BuiltInProviderKeys.ITUNES
    private val mb = BuiltInProviderKeys.MUSICBRAINZ

    @Test fun homeProviderPriorityDoesNotReidentifyAnyProviderNativeAlbum() {
        val mixed = listOf(HomeShelf("Releases", listOf(
            ShelfItem("MusicBrainz Release", "Artist", null, null,
                "catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111"),
            ShelfItem("Apple Release", "Artist", null, null, "itunes:br:album:55"),
        )))
        val picked = projectCanonicalHomeEditorial(
            mixed, UiState.Success(emptyList()), listOf(mb, apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "Releases", discoveryHeading = "Discovery",
            todayIsoDate = "2026-11-17",
        )
        assertEquals(mb, picked.provider)
        assertEquals("catalog:RELEASE_GROUP:11111111-1111-4111-8111-111111111111",
            (picked.state as UiState.Success).data.single().items.single().browseId)
    }

    @Test fun homeWithLoadingPrimaryStillShowsLocalAssetsAndNoForeignCatalogCards() {
        val personal = HomeShelf("Recent", listOf(
            ShelfItem("Owned", "Artist", null, null, "local:track:1"),
        ))
        val chosen = CatalogEditorialSelection(mb, UiState.Loading)
        val result = projectCanonicalHomeDisplay(listOf(personal), chosen, null)
        assertEquals(listOf("local:track:1"),
            result.flatMap { it.items }.map { it.browseId })
        assertTrue(projectCanonicalHomeState(result, chosen) is UiState.Success)
        assertEquals(UiState.Loading, projectCanonicalHomeState(emptyList(), chosen))
    }

    @Test fun emptyOrUnauthorizedEditorialProvidersCannotPromoteAppleCache() {
        val state = projectCanonicalHomeEditorial(
            emptyList(), UiState.Loading, emptyList(),
            canonicalWorldwideLoading = false,
            releasesHeading = "Releases", discoveryHeading = "Discovery",
            todayIsoDate = "2026-11-17",
        )
        assertEquals(null, state.provider)
        assertTrue((state.state as UiState.Success).data.isEmpty())
    }

    @Test fun missingGenreKeepsOldEmptyPageAndPendingWinnerKeepsLoading() {
        val nullTag = projectCanonicalGenreState(
            tag = null, genrePlaylists = emptyList(), playlistHeading = "Playlists",
            appleByTag = emptyMap(), musicBrainzRows = UiState.Loading,
            ordered = listOf(apple, mb), releasesHeading = "Releases",
        )
        assertTrue((nullTag as UiState.Success).data.isEmpty())
        val pending = projectCanonicalGenreState(
            tag = "k-pop", genrePlaylists = emptyList(), playlistHeading = "Playlists",
            appleByTag = emptyMap(), musicBrainzRows = UiState.Success(emptyList()),
            ordered = listOf(apple, mb), releasesHeading = "Releases",
        )
        assertEquals(UiState.Loading, pending)
    }
}
