package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ApplePublicSearchSource
import com.music.bitchord.data.catalog.metadata.ItunesCatalogEntity
import com.music.bitchord.data.catalog.metadata.ItunesCatalogFailure
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesCatalogResult
import com.music.bitchord.data.catalog.metadata.ItunesCatalogSearchAttribute
import com.music.bitchord.ui.ApplePr212SearchSession
import com.music.bitchord.ui.screens.CanonicalSearchKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePr212SearchSessionTest {
    private fun product(
        kind: ItunesCatalogItemType,
        id: Long,
        title: String,
        collectionId: Long? = null,
    ) = ItunesCatalogItem(
        type = kind, id = id, storefront = "br", title = title,
        artistName = "NewJeans", artistId = 99L, collectionId = collectionId,
        storeUrl = null, releaseDate = "2024-05-24", genre = "K-Pop",
        durationMs = null, trackNumber = null, discNumber = null,
    )

    private class Fake(
        val artists: List<ItunesCatalogItem> = emptyList(),
        val albums: List<ItunesCatalogItem> = emptyList(),
        val songs: List<ItunesCatalogItem> = emptyList(),
        val failures: Set<ItunesCatalogEntity> = emptySet(),
    ) : ApplePublicSearchSource {
        var calls = 0
        override suspend fun search(
            query: String,
            entity: ItunesCatalogEntity,
            country: String,
            attribute: ItunesCatalogSearchAttribute?,
        ): ItunesCatalogResult {
            calls++
            if (entity in failures) return ItunesCatalogResult.Failure(
                ItunesCatalogFailure.UNAVAILABLE)
            return ItunesCatalogResult.Success(
                when (entity) {
                    ItunesCatalogEntity.ARTIST -> artists
                    ItunesCatalogEntity.ALBUM -> albums
                    ItunesCatalogEntity.SONG -> songs
                },
            )
        }
    }

    @Test fun distinctStorefrontIdsAndExactTrackAlbumRoutesSurviveAllThreePagers() = runBlocking {
        val fake = Fake(
            artists = listOf(product(ItunesCatalogItemType.ARTIST, 99, "NewJeans")),
            albums = listOf(product(ItunesCatalogItemType.ALBUM, 123, "How Sweet - EP")),
            songs = listOf(product(ItunesCatalogItemType.TRACK, 333, "Bubble Gum", 123)),
        )
        val session = ApplePr212SearchSession("NewJeans", source = fake)
        val snapshots = mutableListOf<Int>()
        val result = requireNotNull(session.start { snapshots.add(it.rows.size) })
        assertEquals(3, fake.calls)
        assertEquals(listOf(1, 2, 3), snapshots)
        assertEquals(listOf("itunes:br:artist:99", "itunes:br:album:123",
            "itunes:br:track:333"), result.rows.map { it.musicBrainzId })
        assertEquals("itunes:br:album:123",
            result.trackAlbums["itunes:br:track:333"]?.scopedId)
        assertEquals(listOf(CanonicalSearchKind.ARTIST,
            CanonicalSearchKind.RELEASE, CanonicalSearchKind.RECORDING),
            result.rows.map { it.kind })
        assertTrue(result.rows.all { !it.hasCatalogedAsset })
        assertFalse(result.hasMore)
    }

    @Test fun localPagesAppendWithoutRefetchingOrRenumberingPriorAppleArtistIds() = runBlocking {
        val fake = Fake(artists = (1L..50L).map {
            product(ItunesCatalogItemType.ARTIST, it, "Artist $it")
        })
        val session = ApplePr212SearchSession("Artist", source = fake, pageSize = 24)
        val first = requireNotNull(session.start())
        assertTrue(first.hasMore)
        assertEquals(24, first.rows.size)
        val second = requireNotNull(session.loadMore())
        assertEquals(48, second.rows.size)
        assertEquals(first.rows.map { it.musicBrainzId },
            second.rows.take(24).map { it.musicBrainzId })
        val third = requireNotNull(session.loadMore())
        assertEquals(50, third.rows.size)
        assertFalse(third.hasMore)
        assertEquals(3, fake.calls)
    }

    @Test fun failedArtistProviderDoesNotEraseIndependentAppleAlbumProof() = runBlocking {
        val fake = Fake(
            albums = listOf(product(ItunesCatalogItemType.ALBUM, 44, "OMG - Single")),
            failures = setOf(ItunesCatalogEntity.ARTIST),
        )
        val session = ApplePr212SearchSession("OMG", source = fake)
        val result = requireNotNull(session.start())
        assertEquals(listOf("itunes:br:album:44"), result.rows.map { it.musicBrainzId })
        assertFalse(result.rows.any { it.musicBrainzId.startsWith("catalog:") })
    }
    @Test fun failedSingleEntityCanRetryWithoutErasingAlreadyLoadedAppleProducts() = runBlocking {
        val attempts = mutableMapOf<ItunesCatalogEntity, Int>()
        val provider = object : ApplePublicSearchSource {
            override suspend fun search(
                query: String, entity: ItunesCatalogEntity, country: String,
                attribute: ItunesCatalogSearchAttribute?,
            ): ItunesCatalogResult {
                val n = (attempts[entity] ?: 0) + 1
                attempts[entity] = n
                return when (entity) {
                    ItunesCatalogEntity.ARTIST -> ItunesCatalogResult.Success(
                        listOf(product(ItunesCatalogItemType.ARTIST, 99, "NewJeans")),
                    )
                    ItunesCatalogEntity.ALBUM -> if (n == 1)
                        ItunesCatalogResult.Failure(ItunesCatalogFailure.UNAVAILABLE)
                    else ItunesCatalogResult.Success(
                        listOf(product(ItunesCatalogItemType.ALBUM, 123, "Get Up - EP")),
                    )
                    ItunesCatalogEntity.SONG ->
                        ItunesCatalogResult.Success(emptyList())
                }
            }
        }
        val session = ApplePr212SearchSession("NewJeans", source = provider)
        val partial = requireNotNull(session.start())
        assertEquals(listOf("itunes:br:artist:99"), partial.rows.map { it.musicBrainzId })
        assertTrue(partial.hasMore)
        val complete = requireNotNull(session.loadMore())
        assertEquals(listOf("itunes:br:artist:99", "itunes:br:album:123"),
            complete.rows.map { it.musicBrainzId })
        assertFalse(complete.hasMore)
        assertEquals(2, attempts[ItunesCatalogEntity.ALBUM])
        assertEquals(1, attempts[ItunesCatalogEntity.ARTIST])
    }

    @Test fun addingNewerAlbumCannotReorderAlreadyDisplayedAppleResults() = runBlocking {
        val older = product(ItunesCatalogItemType.ALBUM, 1, "First - EP")
            .copy(releaseDate = "2021-05-01")
        val newer = product(ItunesCatalogItemType.ALBUM, 2, "Second - EP")
            .copy(releaseDate = "2026-05-01")
        val fake = Fake(albums = listOf(older, newer))
        val session = ApplePr212SearchSession("album", source = fake, pageSize = 1)
        val first = requireNotNull(session.start())
        assertEquals(listOf("itunes:br:album:1"), first.rows.map { it.musicBrainzId })
        val more = requireNotNull(session.loadMore())
        assertEquals(listOf("itunes:br:album:1", "itunes:br:album:2"),
            more.rows.map { it.musicBrainzId })
        assertEquals(3, fake.calls)
    }

}
