package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.ApplePr212DetailProjection
import com.music.bitchord.ui.applePr212ArtistReleasesWithVerifiedCovers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePr212DetailProjectionTest {
    private fun item(type: ItunesCatalogItemType, id: Long, collectionId: Long? = null) =
        ItunesCatalogItem(
            type = type, id = id, storefront = "br", title = "NewJeans",
            artistName = "NewJeans", artistId = 99, collectionId = collectionId,
            storeUrl = null, releaseDate = "2024-05-24", genre = "Pop",
            durationMs = null, trackNumber = null, discNumber = null,
        )

    private fun release(id: Long, format: ItunesEditorialReleaseFormat,
                        title: String = "Release $id", artistId: Long = 99) =
        ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:$id", appleCollectionId = id,
            storefront = "br", title = title, artistName = "NewJeans",
            artistId = artistId, releaseDate = "2024-05-24", genre = "Pop",
            format = format, artworkUrl = "https://is1-ssl.mzstatic.com/image/test/600x600bb.jpg",
            trackCount = null, tracks = emptyList(), musicBrainzReleaseGroupId = null,
            musicBrainzArtistId = null, musicBrainzRecordingIds = emptyMap(),
        )

    @Test fun artistShelvesKeepAppleProductIdsAndTheExistingDetailPage() {
        val result = requireNotNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ARTIST, 99),
            listOf(release(1, ItunesEditorialReleaseFormat.ALBUM),
                   release(2, ItunesEditorialReleaseFormat.EP),
                   release(3, ItunesEditorialReleaseFormat.SINGLE),
                   release(4, ItunesEditorialReleaseFormat.UNCLASSIFIED)),
        ))
        assertEquals(BrowseType.ARTIST, result.type)
        assertEquals("itunes:br:artist:99", result.browseId)
        assertEquals(listOf("Álbuns", "Singles e EPs", "Outros lançamentos"),
            result.sections.map { it.title })
        assertEquals(listOf("itunes:br:album:1", "itunes:br:album:2",
            "itunes:br:album:3", "itunes:br:album:4"),
            result.sections.flatMap { it.items }.map { it.browseId })
        assertTrue(result.sections.flatMap { it.items }.all { it.videoId == null })
        assertTrue((result.songs as UiState.Success).data.isEmpty())
        assertNull(result.subscription)
    }

    @Test fun exactSameNamedAppleEditionsDoNotDuplicateTheNativeArtistShelf() {
        val original = release(21, ItunesEditorialReleaseFormat.UNCLASSIFIED, "NJWMX")
            .copy(releaseDate = "2023-12-19", trackCount = 12)
        val redundant = release(22, ItunesEditorialReleaseFormat.UNCLASSIFIED, "NJWMX")
            .copy(releaseDate = "2023-12-21", trackCount = 12)
        val anotherYear = release(23, ItunesEditorialReleaseFormat.UNCLASSIFIED, "NJWMX")
            .copy(releaseDate = "2024-12-19")
        val page = requireNotNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ARTIST, 99),
            listOf(redundant, original, anotherYear),
        ))
        val ids = page.sections.single().items.mapNotNull { it.browseId }
        assertEquals(2, ids.size)
        assertTrue(ids.contains("itunes:br:album:23"))
        assertEquals(1, ids.count { it == "itunes:br:album:21" ||
            it == "itunes:br:album:22" })
    }

    @Test fun repeatedInternalWhitespaceDoesNotDuplicateIndistinguishableAppleEdition() {
        val original = release(201, ItunesEditorialReleaseFormat.ALBUM, "NJW MX")
            .copy(releaseDate = "2023-12-19")
        val extraSpaces = release(202, ItunesEditorialReleaseFormat.ALBUM, "NJW    MX")
            .copy(releaseDate = "2023-12-21")
        val page = requireNotNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ARTIST, 99),
            listOf(original, extraSpaces),
        ))
        assertEquals(1, page.sections.single().items.size)
        assertEquals("itunes:br:album:201", page.sections.single().items.single().browseId)
    }

    @Test fun artistArtworkPatchFollowsExactAppleProductAndOriginalCdnVersion() {
        val original = "https://is1-ssl.mzstatic.com/image/test/100x100bb.jpg"
        val upgraded = "https://is1-ssl.mzstatic.com/image/test/600x600bb.jpg"
        val first = release(51, ItunesEditorialReleaseFormat.EP, "Get Up")
            .copy(artworkUrl = original)
        val unrelated = release(52, ItunesEditorialReleaseFormat.EP, "New Jeans")
            .copy(artworkUrl = original)
        val changedAppleSnapshot = first.copy(
            artworkUrl = "https://is1-ssl.mzstatic.com/image/other/100x100bb.jpg",
        )
        val proofs = mapOf(first.appleScopedId to (original to upgraded))
        val rows = applePr212ArtistReleasesWithVerifiedCovers(
            listOf(first, unrelated, changedAppleSnapshot), proofs,
        )
        assertEquals(upgraded, rows[0].artworkUrl)
        assertEquals(original, rows[1].artworkUrl)
        assertEquals(changedAppleSnapshot.artworkUrl, rows[2].artworkUrl)
    }

    @Test fun singleAndEpUseOneNativeSectionAndNeverCollapseDifferentFormats() {
        val page = requireNotNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ARTIST, 99),
            listOf(
                release(32, ItunesEditorialReleaseFormat.SINGLE, "EASY - Single"),
                release(31, ItunesEditorialReleaseFormat.EP, "EASY - EP"),
                release(33, ItunesEditorialReleaseFormat.SINGLE, "EASY - Single"),
            ),
        ))
        assertEquals(listOf("Singles e EPs"), page.sections.map { it.title })
        assertEquals(setOf("itunes:br:album:31", "itunes:br:album:32"),
            page.sections.single().items.mapNotNull { it.browseId }.toSet())
        assertEquals(2, page.sections.single().items.size)
    }

    @Test fun unrelatedArtistsAndConflictingDuplicatesNeverEnterAnArtistPage() {
        val valid = release(1, ItunesEditorialReleaseFormat.EP)
        val conflicting = valid.copy(title = "Unrelated edition")
        val soundtrack = release(4, ItunesEditorialReleaseFormat.ALBUM)
            .copy(artistName = "Various Artists")
        val result = requireNotNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ARTIST, 99),
            listOf(valid, conflicting, release(2, ItunesEditorialReleaseFormat.ALBUM,
                artistId = 123), soundtrack,
                release(3, ItunesEditorialReleaseFormat.SINGLE)),
        ))
        assertEquals(listOf("itunes:br:album:3"),
            result.sections.flatMap { it.items }.mapNotNull { it.browseId })
    }

    @Test fun albumTracksRemainNonPlayableExactAppleMetadata() {
        val track = item(ItunesCatalogItemType.TRACK, 101, collectionId = 10)
            .copy(title = "How Sweet", trackNumber = 1, discNumber = 1)
        val wrongAlbum = item(ItunesCatalogItemType.TRACK, 102, collectionId = 11)
        val album = release(10, ItunesEditorialReleaseFormat.EP, "How Sweet - EP")
            .copy(tracks = listOf(track, wrongAlbum))
        val result = requireNotNull(ApplePr212DetailProjection.albumPage(album))
        assertEquals(BrowseType.ALBUM, result.page.type)
        assertEquals("itunes:br:album:10", result.page.browseId)
        assertTrue((result.page.songs as UiState.Success).data.isEmpty())
        assertEquals(listOf("itunes:br:track:101"), result.tracks.map { it.scopedId })
        assertEquals(listOf(1), result.tracks.map { it.trackNumber })
        assertFalse(result.page.browseId.startsWith("catalog:"))
        assertNull(result.page.library)
    }

    @Test fun AppleOnlyAlbumWithoutArtistCrosswalkStillOpensWithoutPlayback() {
        val album = release(12, ItunesEditorialReleaseFormat.UNCLASSIFIED)
            .copy(artistId = null)
        val page = requireNotNull(ApplePr212DetailProjection.albumPage(album))
        assertEquals("itunes:br:album:12", page.page.browseId)
        assertTrue((page.page.songs as UiState.Success).data.isEmpty())
    }

    @Test fun malformedOrCrossStorefrontIdentitiesAreRejected() {
        assertNull(ApplePr212DetailProjection.artistPage(
            item(ItunesCatalogItemType.ALBUM, 99), emptyList()))
        assertNull(ApplePr212DetailProjection.albumPage(
            release(10, ItunesEditorialReleaseFormat.ALBUM)
                .copy(appleScopedId = "itunes:us:album:10")))
    }
}
