package com.music.aria.ui.screens

import com.music.aria.data.model.UiState
import com.music.aria.data.model.SearchFilter
import com.music.aria.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canonical search must display through a source-neutral native row without
 * manufacturing BrowseItem, YouTube browseId or Song.videoId.
 */
class NativeSearchRowTest {
    @Test fun recordingWithoutVerifiedLocalMediaRemainsMetadataOnly() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "recording-mbid", kind = CanonicalSearchKind.RECORDING,
            title = "Armageddon", subtitle = "aespa", durationMs = 185000L,
        )
        val visual = canonicalNativeSearchRowUi(row, "Recording")
        assertEquals("Armageddon", visual.title)
        assertEquals("aespa", visual.subtitle)
        // MusicBrainz retains duration metadata, but Apple-style track rows do not show it.
        assertEquals(185000L, row.durationMs)
        assertFalse(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
    }

    @Test fun nativePaginationOnlyAnchorsWhenLastResultsAreVisible() {
        assertFalse(nativeCatalogPaginationAtEnd(-1, 0))
        assertFalse(nativeCatalogPaginationAtEnd(3, 40))
        assertTrue(nativeCatalogPaginationAtEnd(0, 1))
        assertTrue(nativeCatalogPaginationAtEnd(38, 40))
        assertTrue(nativeCatalogPaginationAtEnd(39, 40))
    }

    @Test fun searchArtistUsesCircularArtworkAndNoFakeDownloadBadge() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "artist-mbid", kind = CanonicalSearchKind.ARTIST,
            title = "aespa", subtitle = null, hasVerifiedLocalAsset = true,
        )
        val visual = canonicalNativeSearchRowUi(row, "Artista")
        assertTrue(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
        assertEquals("Artista", visual.subtitle)
    }

    @Test fun mixedSearchUsesExplicitCreditsAndDoesNotAttachAmbiguousNamesakes() {
        val primary = CanonicalCatalogSearchRow("a", CanonicalSearchKind.ARTIST, "Beyoncé", null)
        val homonym = primary.copy(musicBrainzId = "b")
        val work = CanonicalCatalogSearchRow("r", CanonicalSearchKind.RECORDING,
            "Song", "Beyoncé", artistCreditMbids = listOf("a"))
        val contradictory = work.copy(musicBrainzId = "wrong", artistCreditMbids = listOf("b"),
            artistScopeMbid = "a")
        val unknown = work.copy(musicBrainzId = "unknown", artistCreditMbids = null)
        val rows = listOf(primary, homonym, work, contradictory, unknown)
        assertEquals(listOf("a", "r"), canonicalRowsForQuery(rows, "Beyonce",
            SearchFilter.ALL).map { it.musicBrainzId })
        assertEquals(listOf("a", "b"), canonicalRowsForQuery(rows, "Beyonce",
            SearchFilter.ARTISTS).map { it.musicBrainzId })
        assertEquals(setOf("a", "r", "unknown"), canonicalRowsForQuery(
            rows - homonym, "Beyonce", SearchFilter.ALL).map { it.musicBrainzId }.toSet())
    }

    @Test fun unverifiedGroupNeverClaimsThatAnotherEditionCoverIsOfficial() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "group-mbid", kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "Unknown", subtitle = "aespa",
            releaseKind = null, artworkUri = "https://example.org/cover.jpg",
        )
        assertNull(canonicalNativeSearchRowUi(row, "Álbum").artworkUri)
        val classified = row.copy(releaseKind = "Album")
        assertEquals("https://example.org/cover.jpg",
            canonicalNativeSearchRowUi(classified, "Álbum").artworkUri)
    }

    @Test fun serverAssetPresentationCarriesNoPrivateStreamUrl() {
        val row = CanonicalServerSearchRow(
            recordingId = "recording", mediaAssetId = "asset",
            providerKey = "opensubsonic:server", title = "Track", artistDisplay = "Artist",
        )
        val visual = canonicalServerNativeSearchRowUi(row)
        assertEquals("Track", visual.title)
        assertEquals("Artist", visual.subtitle)
        assertFalse(visual.artistPortrait)
        assertFalse(visual.downloadBadge)
        assertNull(visual.artworkUri)
    }
    @Test fun recordingWithoutCreditUsesItsOwnLocalizedKindNotLegacyAlbumFallback() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "recording-mbid",
            kind = CanonicalSearchKind.RECORDING,
            title = "Hype Boy",
            subtitle = null,
        )
        assertEquals("Gravação", canonicalNativeSearchRowUi(row, "Gravação").subtitle)
    }

    @Test fun searchKindFallbackAvoidsProviderEntityOrVideoIdentifiers() {
        val row = CanonicalCatalogSearchRow(
            musicBrainzId = "mbid",
            kind = CanonicalSearchKind.RELEASE_GROUP,
            title = "EP",
            subtitle = null,
        )
        assertEquals("Álbum", canonicalNativeSearchRowUi(row, "Álbum").subtitle)
    }

    @Test fun serverFailuresAndLoadingNeverMasqueradeAsAnEmptyResult() {
        val noLegacyMatches = UiState.Success(emptyList<Song>())
        val noServerMatches = UiState.Success(emptyList<CanonicalServerSearchRow>())
        assertTrue(canonicalServerSearchPendingWithoutFallback(
            UiState.Loading, noLegacyMatches,
        ))
        assertFalse(canonicalServerSearchPendingWithoutFallback(
            noServerMatches, noLegacyMatches,
        ))
        assertEquals("Servidor indisponível", canonicalServerSearchErrorWithoutFallback(
            UiState.Error("Servidor indisponível"), noLegacyMatches,
        ))
        assertNull(canonicalServerSearchErrorWithoutFallback(
            noServerMatches, noLegacyMatches,
        ))
        // Legacy fetching has not finished; do not hide its legitimate
        // matches behind a premature remote-only loading/error message.
        assertFalse(canonicalServerSearchPendingWithoutFallback(
            UiState.Loading, UiState.Loading,
        ))
        assertNull(canonicalServerSearchErrorWithoutFallback(
            UiState.Error("Servidor indisponível"), UiState.Loading,
        ))
    }

    @Test fun appleStyleSearchRowsHaveOnlyTitleAndSourceSubtitle() {
        val catalogRecording = NativeSearchRowUi(
            title = "Armageddon", subtitle = "aespa", artworkUri = null,
            artistPortrait = false,
        )
        val serverRecording = catalogRecording.copy(
            artworkUri = "https://example.org/server-cover",
        )
        val artist = NativeSearchRowUi(
            title = "aespa", subtitle = "Artista", artworkUri = null,
            artistPortrait = true,
        )
        assertEquals("aespa", catalogRecording.subtitle)
        assertEquals("aespa", serverRecording.subtitle)
        assertEquals("Artista", artist.subtitle)
        assertFalse("durationLabel" in NativeSearchRowUi::class.java.declaredFields.map { it.name })
    }

    @Test fun nativeSearchPresentationExposesNoLegacyNavigationOrPlaybackIdFields() {
        val fields = NativeSearchRowUi::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("browseId" in fields)
        assertFalse("videoId" in fields)
        assertFalse("mediaAssetId" in fields)
        assertFalse("providerKey" in fields)
        assertFalse("streamUrl" in fields)
        assertFalse("appleStoreBadgeUrl" in fields)
        assertFalse("appleStoreUrl" in fields)
        assertTrue("title" in fields)
        assertTrue("artworkUri" in fields)
    }

}
