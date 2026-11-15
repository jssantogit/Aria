package com.music.bitchord.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCatalogEntityRatingsTest {
    private val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test fun onlineArtistAndEditionUseFirstPartyIdentityWithoutAPlayableAsset() {
        val artist = canonicalCatalogEntityRatingTarget("catalog:ARTIST:$id")
        val release = canonicalCatalogEntityRatingTarget("catalog:RELEASE:$id")
        val group = canonicalCatalogEntityRatingTarget("catalog:RELEASE_GROUP:$id")
        assertEquals(CanonicalEntityRatingTarget("ARTIST", id), artist)
        assertEquals(CanonicalEntityRatingTarget("ALBUM", id), release)
        assertEquals(CanonicalEntityRatingTarget("RELEASE_GROUP", id), group)
        assertTrue(canPersistCanonicalCatalogRating(artist!!))
        assertTrue(canPersistCanonicalCatalogRating(release!!))
        assertTrue(canPersistCanonicalCatalogRating(group!!))
    }

    @Test fun exactAppleArtistAndAlbumEnableExistingNativeRatingActions() {
        val artist = canonicalCatalogEntityRatingTarget("itunes:br:artist:1652349282")
        val album = canonicalCatalogEntityRatingTarget("itunes:br:album:1234567890")
        assertEquals(CanonicalEntityRatingTarget("ARTIST", "itunes:br:artist:1652349282"), artist)
        assertEquals(CanonicalEntityRatingTarget("ALBUM", "itunes:br:album:1234567890"), album)
        assertTrue(canPersistCanonicalCatalogRating(requireNotNull(artist)))
        assertTrue(canPersistCanonicalCatalogRating(requireNotNull(album)))
        for (invalid in listOf(
            "itunes:br:artist:0", "itunes:BR:artist:1", "itunes:br:artist:01",
            "itunes:br:artist:garbage", "itunes:br:track:12", "itunes:br:album:-5",
            "itunes:br:artist:9999999999999999999999999",
        )) assertNull(invalid, canonicalCatalogEntityRatingTarget(invalid))
    }

    @Test fun onlinePlaylistCannotMasqueradeAsLocalPlaylistOrAnAlbum() {
        assertEquals(CanonicalEntityRatingTarget("PLAYLIST", "public:$id"),
            canonicalPublicPlaylistRatingTarget(id))
        assertEquals(CanonicalEntityRatingTarget("PLAYLIST", "normal:123"),
            canonicalOwnedPlaylistRatingTarget("normal:123"))
        assertNull(canonicalOwnedPlaylistRatingTarget("catalog:RELEASE:$id"))
        assertNull(canonicalPublicPlaylistRatingTarget("not-a-mbid"))
    }

    @Test fun rejectExternalUrlsNamesAndMalformedIdentities() {
        assertNull(canonicalCatalogEntityRatingTarget("catalog:ARTIST:STAYC"))
        assertNull(canonicalCatalogEntityRatingTarget("catalog:ARTIST: $id"))
        assertNull(canonicalCatalogEntityRatingTarget("catalog:RELEASE_GROUP:$id/extra"))
        assertNull(canonicalCatalogEntityRatingTarget("local:ALBUM:$id"))
        assertFalse(canPersistCanonicalCatalogRating(CanonicalEntityRatingTarget("RECORDING", id)))
        assertFalse(canPersistCanonicalCatalogRating(CanonicalEntityRatingTarget("PLAYLIST", "public:fake")))
    }
}
