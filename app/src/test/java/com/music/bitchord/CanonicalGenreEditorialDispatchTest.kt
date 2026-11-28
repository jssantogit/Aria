package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.*
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.providers.BuiltInProviderKeys
import com.music.bitchord.ui.shouldDispatchCanonicalGenreSource
import com.music.bitchord.ui.screens.CanonicalCatalogSearchRow
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.*
import org.junit.Test

class CanonicalGenreEditorialDispatchTest {
    private val apple = BuiltInProviderKeys.ITUNES
    private val mb = BuiltInProviderKeys.MUSICBRAINZ
    private val discogs = BuiltInProviderKeys.DISCOGS
    private val emptyApple = UiState.Success(emptyList<ItunesEditorialRelease>())
    private val emptyMb: UiState<List<CanonicalCatalogSearchRow>> =
        UiState.Success(emptyList())

    private fun ready(
        target: com.music.bitchord.data.providers.ProviderKey,
        ordered: List<com.music.bitchord.data.providers.ProviderKey>,
        tag: String = "k-pop",
        mbTag: String? = tag,
        mbRows: UiState<List<CanonicalCatalogSearchRow>> = emptyMb,
        appleRows: Map<String, UiState<List<ItunesEditorialRelease>>> = emptyMap(),
        discogsRows: Map<String, UiState<List<HomeShelf>>> = emptyMap(),
    ) = shouldDispatchCanonicalGenreSource(
        tag, target, ordered, mbTag, mbRows, appleRows, discogsRows,
    )

    @Test fun discogsAloneIsEligibleWithoutMbOrApple() {
        assertTrue(ready(discogs, listOf(discogs)))
        assertFalse(ready(mb, listOf(discogs)))
    }

    @Test fun threeWayFallbackWaitsForTheMiddleProvider() {
        val order = listOf(mb, discogs, apple)
        assertTrue(ready(discogs, order))
        assertFalse(ready(apple, order))
        assertTrue(ready(apple, order, discogsRows = mapOf(
            "k-pop" to UiState.Error("temporarily unavailable"),
        )))
        assertFalse(ready(apple, order, mbRows = UiState.Loading,
            discogsRows = mapOf("k-pop" to UiState.Error("missing"))))
        val reversed = listOf(apple, discogs, mb)
        assertFalse(ready(mb, reversed, appleRows = mapOf("k-pop" to emptyApple)))
        assertTrue(ready(mb, reversed, appleRows = mapOf("k-pop" to emptyApple),
            discogsRows = mapOf("k-pop" to UiState.Success(emptyList()))))
    }

    @Test fun staleMusicBrainzTagAndUntypedResultsAreNotReleaseSuccess() {
        val artistsOnly: UiState<List<CanonicalCatalogSearchRow>> =
            UiState.Success(listOf(CanonicalCatalogSearchRow(
                musicBrainzId = "11111111-1111-4111-8111-111111111111",
                kind = CanonicalSearchKind.ARTIST,
                title = "Verified artist, but no release",
                subtitle = "Artist",
            )))
        assertTrue(ready(discogs, listOf(mb, discogs),
            mbRows = artistsOnly))
        assertFalse(ready(discogs, listOf(mb, discogs),
            mbTag = "rock", mbRows = artistsOnly))
        val realAlbum: UiState<List<CanonicalCatalogSearchRow>> =
            UiState.Success(listOf(CanonicalCatalogSearchRow(
                musicBrainzId = "11111111-1111-4111-8111-111111111111",
                kind = CanonicalSearchKind.RELEASE_GROUP,
                title = "Verified release",
                subtitle = "Artist",
                releaseKind = "Album",
            )))
        assertFalse(ready(discogs, listOf(mb, discogs), mbRows = realAlbum))
    }


    @Test fun sparseSupportedGenreExpandsWithoutBroadeningGenreIdentity() = kotlinx.coroutines.runBlocking {
        fun album(id: Long, artistId: Long = 99L, genre: String = "K-Pop",
                  storefront: String = "br") = ItunesEditorialRelease(
            appleScopedId = "itunes:$storefront:album:$id",
            appleCollectionId = id, storefront = storefront,
            title = "Album $id", artistName = "Artist $artistId", artistId = artistId,
            releaseDate = "2026-01-01", genre = genre,
            format = ItunesEditorialReleaseFormat.UNCLASSIFIED,
            artworkUrl = null, trackCount = 1, tracks = emptyList(),
            musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )
        val probes = mutableListOf<Long>()
        val discovery = AppleExploreGenreDiscovery(nativeSource = object : AppleNativeGenreSearchSource {
            override suspend fun search(term: String, storefront: String) =
                AppleEditorialLoad.Success(listOf(album(1)), AppleEditorialReadState.COMPLETE)
            override suspend fun artistAlbums(artistId: Long, storefront: String): AppleEditorialLoad {
                probes += artistId
                return AppleEditorialLoad.Success(
                    listOf(album(2), album(3, genre = "Pop"), album(4, artistId = 88),
                        album(5, storefront = "us")),
                    AppleEditorialReadState.COMPLETE,
                )
            }
        })
        val result = discovery.discoverNative("k-pop", expandSparseCapacity = true)
            as AppleEditorialLoad.Success
        assertEquals(listOf(99L), probes)
        assertEquals(listOf(1L, 2L), result.releases.map { it.appleCollectionId })
        assertEquals(0, result.probeReport?.incompleteResponses)
        probes.clear()
        val home = discovery.discoverNative("k-pop", expandSparseCapacity = false)
            as AppleEditorialLoad.Success
        assertEquals(listOf(1L), home.releases.map { it.appleCollectionId })
        assertTrue(probes.isEmpty())
    }

    @Test fun unsupportedAppleTagCannotHoldTheRestOfTheChainLoading() {
        assertTrue(ready(discogs, listOf(apple, discogs),
            tag = "an-unsupported-genre", appleRows = emptyMap()))
    }
}
