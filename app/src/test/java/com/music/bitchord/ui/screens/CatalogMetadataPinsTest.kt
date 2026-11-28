package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CatalogMetadataPinsTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val release = CatalogMetadataPin(
        "catalog:RELEASE_GROUP:$id", "STAY ALIVE", "STAYC",
        "https://coverartarchive.org/release/$id/front-250",
    )

    @Test fun pinCatalogItemWithoutPretendingItIsAnAvailableMediaAsset() {
        assertTrue(validCatalogMetadataPin(release))
        val saved = toggleCatalogMetadataPin(emptyList(), release)
        assertEquals(listOf(release), decodeCatalogMetadataPins(encodeCatalogMetadataPins(saved)))
        assertTrue(toggleCatalogMetadataPin(saved, release).isEmpty())
    }

    @Test fun ratingCapabilityDoesNotAuthorizeUnsupportedPinReopening() {
        for (route in listOf("itunes:br:album:123","discogs:release:123")) {
            assertTrue(canonicalCatalogEntityRatingTarget(route) != null)
            val pin = CatalogMetadataPin(route,"Album")
            assertFalse(validCatalogMetadataPin(pin))
            assertTrue(toggleCatalogMetadataPin(emptyList(),pin).isEmpty())
        }
        assertTrue(validCatalogMetadataPin(CatalogMetadataPin("apple_music_web:br:album:123","Album")))
    }

    @Test fun refuseFakeIdsAndUntrustedCoverUris() {
        assertFalse(validCatalogMetadataPin(release.copy(browseId = "catalog:RELEASE_GROUP:STAYC")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "file:///private/song.mp3")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "https://evil.example/a.png")))
        assertFalse(validCatalogMetadataPin(release.copy(artworkUrl = "https://coverartarchive.org@evil.example/a")))
    }
    @Test fun pinnedReleaseCanOpenAlbumWithoutCurrentMusicBrainzSearchResults() {
        val target = catalogMetadataPinNativeTarget(release)
        assertEquals(release.browseId, target?.browseId)
        assertEquals("STAY ALIVE", target?.title)
        assertEquals("STAYC", target?.subtitle)
        assertEquals(com.music.bitchord.data.model.BrowseType.ALBUM, target?.type)
    }

    @Test fun pinnedRecordingUsesItsOwnTypedMetadataRouteRatherThanFakeAlbum() {
        val pin = CatalogMetadataPin(
            browseId = "catalog:RECORDING:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            title = "Hype Boy", subtitle = "NewJeans",
        )
        org.junit.Assert.assertTrue(validCatalogMetadataPin(pin))
        val target = catalogMetadataPinNativeTarget(pin)
        assertEquals(com.music.bitchord.data.model.BrowseType.OTHER, target?.type)
        assertEquals(pin.browseId, target?.browseId)
    }

    @Test fun missingArtistPortraitMayReuseExistingArtistPageArtWithoutChangingMusicBrainzId() {
        val mbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val artist = CatalogMetadataPin(
            browseId = "catalog:ARTIST:$mbid",
            title = "STAYC",
        )
        val cachedAppleHero = "https://is1-ssl.mzstatic.com/image/thumb/artist.jpg"
        val photoCache = mapOf("STAYC" to cachedAppleHero)
        assertTrue(validCatalogMetadataPin(artist))
        assertEquals(cachedAppleHero, pinnedCatalogArtworkUrl(artist, photoCache))
        // A pin with a verified, explicitly stored artwork URL keeps priority.
        val own = artist.copy(artworkUrl = "https://is1-ssl.mzstatic.com/own.jpg")
        assertEquals(own.artworkUrl, pinnedCatalogArtworkUrl(own, photoCache))
        // MBID identity, opening route and playback restrictions stay untouched.
        assertEquals(artist.browseId, catalogMetadataPinNativeTarget(artist)?.browseId)
    }

    @Test fun artistPhotoLookupNeverFillsReleaseRecordingOrInvalidCatalogPins() {
        val mbid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val sameNamePhoto = mapOf("STAYC" to "https://is1-ssl.mzstatic.com/artist.jpg")
        val releaseWithArtistName = CatalogMetadataPin("catalog:RELEASE_GROUP:$mbid", "STAYC")
        val recordingWithArtistName = CatalogMetadataPin("catalog:RECORDING:$mbid", "STAYC")
        assertEquals(null, pinnedCatalogArtworkUrl(releaseWithArtistName, sameNamePhoto))
        assertEquals(null, pinnedCatalogArtworkUrl(recordingWithArtistName, sameNamePhoto))
        assertEquals(null, pinnedCatalogArtworkUrl(
            CatalogMetadataPin("catalog:ARTIST:unverified", "STAYC"), sameNamePhoto,
        ))
    }

    @Test fun pinnedArtistOpensArtistDetailAndRejectsFakeIdentities() {
        val artist = release.copy(
            browseId = "catalog:ARTIST:$id",
            title = "STAYC",
            subtitle = "",
            artworkUrl = null,
        )
        assertEquals(com.music.bitchord.data.model.BrowseType.ARTIST,
            catalogMetadataPinNativeTarget(artist)?.type)
        assertEquals(null, catalogMetadataPinNativeTarget(
            release.copy(browseId = "catalog:RELEASE_GROUP:STAY ALIVE"),
        ))
    }

}
