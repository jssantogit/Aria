package com.music.bitchord

import com.music.bitchord.data.catalog.metadata.ItunesCatalogItem
import com.music.bitchord.data.catalog.metadata.ItunesCatalogItemType
import com.music.bitchord.data.catalog.metadata.ItunesEditorialRelease
import com.music.bitchord.data.catalog.metadata.ItunesEditorialReleaseFormat
import com.music.bitchord.ui.ApplePr212CatalogRoute
import com.music.bitchord.ui.ApplePr212CatalogRows
import com.music.bitchord.ui.screens.CanonicalSearchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePr212CatalogRowsTest {
    @Test fun routesRejectMalformedAndMixedSourceKeys() {
        assertEquals(123L, ApplePr212CatalogRoute.parse("itunes:br:album:123")?.id)
        assertEquals(ItunesCatalogItemType.ARTIST,
            ApplePr212CatalogRoute.parse("itunes:br:artist:123")?.kind)
        for (value in listOf("catalog:RELEASE:123", "itunes:us:album:0",
            "itunes:br:album:-3", "itunes:BR:album:1",
            "itunes:br:track:9999999999999999999999999", "itunes:br:album:1:other",
            "itunes:br:album:01", "itunes:br:release:2")) {
            assertNull(value, ApplePr212CatalogRoute.parse(value))
        }
    }

    @Test fun appleRowsRetainExactCommercialIdsWithoutPlayableOrMbAssets() {
        val artist = ItunesCatalogItem(
            ItunesCatalogItemType.ARTIST, 123, "br", "NewJeans",
            "NewJeans", 123, null, null, null, null, null, null,
            null, null, null,
        )
        val appleArtist = requireNotNull(ApplePr212CatalogRows.artist(artist))
        assertEquals("itunes:br:artist:123", appleArtist.musicBrainzId)
        assertEquals(CanonicalSearchKind.ARTIST, appleArtist.kind)
        assertFalse(appleArtist.hasCatalogedAsset)

        val album = ItunesEditorialRelease(
            appleScopedId = "itunes:br:album:456", appleCollectionId = 456,
            storefront = "br", title = "How Sweet - EP", artistName = "NewJeans",
            artistId = 123, releaseDate = "2024-05-24", genre = "K-Pop",
            format = ItunesEditorialReleaseFormat.EP,
            artworkUrl = "https://is1-ssl.mzstatic.com/test.jpg",
            trackCount = 2, tracks = emptyList(),
            musicBrainzReleaseGroupId = null, musicBrainzArtistId = null,
            musicBrainzRecordingIds = emptyMap(),
        )
        val row = requireNotNull(ApplePr212CatalogRows.album(album))
        assertEquals("itunes:br:album:456", row.musicBrainzId)
        assertEquals("EP", row.releaseKind)
        assertEquals(CanonicalSearchKind.RELEASE, row.kind)
        assertFalse(row.hasCatalogedAsset)
        assertNull(ApplePr212CatalogRows.album(album.copy(appleScopedId =
            "itunes:us:album:456")))
    }

    @Test fun AppleSearchTrackHasExactAlbumLinkButCannotBecomePlayable() {
        val track = ItunesCatalogItem(
            type = ItunesCatalogItemType.TRACK,
            id = 789L, storefront = "br", title = "How Sweet",
            artistName = "NewJeans", artistId = 123L, collectionId = 456L,
            storeUrl = null, releaseDate = null, genre = "K-Pop",
            durationMs = 205_000L, trackNumber = 1, discNumber = 1,
        )
        val row = requireNotNull(ApplePr212CatalogRows.track(track))
        assertEquals("itunes:br:track:789", row.musicBrainzId)
        assertEquals("NewJeans", row.subtitle)
        assertEquals(CanonicalSearchKind.RECORDING, row.kind)
        assertFalse(row.hasCatalogedAsset)
        assertFalse(row.hasVerifiedLocalAsset)
        assertNull(ApplePr212CatalogRows.track(track.copy(collectionId = null)))
        assertNull(ApplePr212CatalogRows.track(track.copy(title = " ")))
    }

    @Test fun tracksAreMetadataOnlyAndNeverClaimToBeMusicBrainzIds() {
        val page = com.music.bitchord.ui.ApplePr212AlbumDetailData(
            page = com.music.bitchord.data.model.DetailPage(
                "itunes:br:album:456", "How Sweet", "NewJeans", null,
                com.music.bitchord.data.model.UiState.Success(emptyList()),
            ),
            tracks = listOf(com.music.bitchord.ui.ApplePr212TrackMetadata(
                "itunes:br:track:777", "How Sweet", "NewJeans", 1, 1,
            )),
        )
        val tracks = ApplePr212CatalogRows.tracks(page)
        assertEquals(listOf("itunes:br:track:777"), tracks.map { it.musicBrainzId })
        assertTrue(tracks.all { !it.hasCatalogedAsset && !it.hasVerifiedLocalAsset })
        assertTrue(tracks.all { it.kind == CanonicalSearchKind.RECORDING })
    }
}
