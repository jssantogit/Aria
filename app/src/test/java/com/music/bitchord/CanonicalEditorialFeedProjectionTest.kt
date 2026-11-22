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
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEditorialFeedProjectionTest {
    private val apple = BuiltInProviderKeys.ITUNES
    private val mb = BuiltInProviderKeys.MUSICBRAINZ

    @Test
    fun homeIncludesOnlyVerifiedOptionalCommunityAndPersonalShelves() {
        val empty = CatalogEditorialSelection(
            state = UiState.Success(emptyList()),
            provider = null,
        )
        val community = HomeShelf("Comunidade", listOf(
            ShelfItem("Música A", "Artista", null, null, "catalog:RECORDING:a"),
        ))
        val personal = HomeShelf("Para você", listOf(
            ShelfItem("Música B", "Artista", null, null, "catalog:RECORDING:b"),
        ))
        val result = projectCanonicalHomeDisplay(
            canonicalHomeShelves = emptyList(),
            selection = empty,
            publicPlaylistShelf = null,
            communityShelf = community,
            personalRecommendationShelf = personal,
        )
        assertEquals(listOf("Comunidade", "Para você", "Explore gêneros"),
            result.map { it.title })
        // A sparse Home gets genuine genre-navigation destinations, never
        // fabricated community rankings, releases, audio or artwork.
        val sparse = projectCanonicalHomeDisplay(
            emptyList(), empty, null,
            communityShelf = HomeShelf("Vazio", emptyList()),
        )
        assertEquals(listOf("Explore gêneros"), sparse.map { it.title })
        assertTrue(sparse.flatMap { it.items }.all {
            it.browseId.orEmpty().startsWith("catalog:GENRE:") &&
                it.videoId == null && it.thumbnailUrl == null
        })
    }

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
        // Loading editorial content does not block verified local assets.
        // A category-navigation rail is neither foreign-provider content nor audio.
        assertEquals(listOf("Explore gêneros", "Recent"), result.map { it.title })
        assertEquals(listOf("local:track:1"),
            result.filter { it.title == "Recent" }
                .flatMap { it.items }.map { it.browseId })
        assertTrue(result.flatMap { it.items }.all {
            it.browseId.orEmpty().startsWith("catalog:GENRE:") ||
                it.browseId.orEmpty().startsWith("local:")
        })
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

    @Test fun musicBrainzPrimaryMissKeepsAppleFallbackLoadingWhileItIsRequested() {
        val selection = projectCanonicalHomeEditorial(
            canonicalHomeShelves = emptyList(),
            appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(mb, apple),
            canonicalWorldwideLoading = false,
            releasesHeading = "Releases",
            discoveryHeading = "Discovery",
            todayIsoDate = "2026-11-17",
        )
        assertEquals(apple, selection.provider)
        assertEquals(UiState.Loading, selection.state)
    }

    @Test fun pendingMusicBrainzPrimaryCannotBeBypassedByAppleFallback() {
        val selection = projectCanonicalHomeEditorial(
            canonicalHomeShelves = emptyList(),
            appleHomeReleases = UiState.Loading,
            catalogEditorialOrder = listOf(mb, apple),
            canonicalWorldwideLoading = true,
            releasesHeading = "Releases",
            discoveryHeading = "Discovery",
            todayIsoDate = "2026-11-17",
        )
        assertEquals(mb, selection.provider)
        assertEquals(UiState.Loading, selection.state)
    }

    @Test fun musicBrainzHomePublishesFullEvidenceBackedShelves() {
        val rows = (1..20).map { index ->
            CanonicalCatalogSearchRow(
                musicBrainzId = "%08d-1111-4111-8111-111111111111".format(index),
                kind = CanonicalSearchKind.RELEASE_GROUP,
                title = "Verified $index", subtitle = "Artist $index",
                releaseDate = "2026-11-%02d".format((index % 18) + 1),
                releaseKind = if (index % 4 == 0) "Single" else "Album",
            )
        }
        val hero = HomeShelf("Lançamentos", rows.take(8).map { row ->
            ShelfItem(row.title, row.subtitle.orEmpty(), null, null,
                "catalog:RELEASE_GROUP:${row.musicBrainzId}")
        })
        val selection = projectCanonicalHomeEditorial(
            canonicalHomeShelves = listOf(hero),
            appleHomeReleases = UiState.Success(emptyList()),
            catalogEditorialOrder = listOf(mb),
            canonicalWorldwideLoading = false,
            releasesHeading = "Lançamentos",
            discoveryHeading = "Descobrir",
            todayIsoDate = "2026-11-22",
            musicBrainzRows = rows,
        )
        assertEquals(mb, selection.provider)
        val shelves = (selection.state as UiState.Success).data
        assertTrue(shelves.size >= 4)
        assertTrue(shelves.any { it.title == "Álbuns" })
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        assertTrue(shelves.any { it.title == "Escolha a dedo" })
        val ids = shelves.flatMap { it.items }.mapNotNull { it.browseId }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.startsWith("catalog:RELEASE_GROUP:") })
    }

    @Test fun musicBrainzGenreKeepsMultiShelfMusicBrainzEvidence() {
        val rows = (1..30).map { index ->
            CanonicalCatalogSearchRow(
                musicBrainzId = "%08d-2222-4222-8222-222222222222".format(index),
                kind = CanonicalSearchKind.RELEASE_GROUP,
                title = "K-pop verified $index", subtitle = "Artist $index",
                releaseDate = "2026-11-%02d".format((index % 19) + 1),
                releaseKind = if (index % 3 == 0) "Single" else "Album",
            )
        }
        val state = projectCanonicalGenreState(
            tag = "k-pop", genrePlaylists = emptyList(),
            playlistHeading = "Playlists", appleByTag = emptyMap(),
            musicBrainzRows = UiState.Success(rows), ordered = listOf(mb),
            releasesHeading = "Lançamentos", musicBrainzResultTag = "k-pop",
            todayIsoDate = "2026-11-22",
        )
        val shelves = (state as UiState.Success).data
        assertTrue(shelves.size >= 4)
        assertTrue(shelves.any { it.title == "Álbuns" })
        assertTrue(shelves.any { it.title == "Singles e EPs" })
        val ids = shelves.flatMap { it.items }.mapNotNull { it.browseId }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.startsWith("catalog:RELEASE_GROUP:") })
    }

    @Test fun switchingGenreNeverDisplaysPreviousMusicBrainzTagResults() {
        val previousRows = UiState.Success(listOf(CanonicalCatalogSearchRow(
            musicBrainzId = "11111111-1111-4111-8111-111111111111",
            kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Old rock release", subtitle = "Previous artist",
            releaseKind = "Album",
        )))
        val state = projectCanonicalGenreState(
            tag = "jazz",
            genrePlaylists = emptyList(),
            playlistHeading = "Playlists",
            appleByTag = emptyMap(),
            musicBrainzRows = previousRows,
            ordered = listOf(mb),
            releasesHeading = "Releases",
            musicBrainzResultTag = "rock",
        )
        assertEquals(UiState.Loading, state)
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
