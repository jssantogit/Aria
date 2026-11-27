package com.music.aria.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHomeArtistPortraitTest {
    @Test fun typedArtistRoutesUseTheSameCircularShelfGeometry() {
        assertTrue(homeShelfUsesArtistPortrait("catalog:ARTIST:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
        assertTrue(homeShelfUsesArtistPortrait("itunes:br:artist:123"))
        assertTrue(homeShelfUsesArtistPortrait("discogs:artist:123"))
    }

    @Test fun releaseGenrePlaylistAndMalformedRoutesRetainTheirOwnGeometry() {
        for (route in listOf(null, "Artista", "local:all",
            "catalog:RELEASE:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "catalog:GENRE:k-pop", "itunes:br:album:123",
            "itunes:br:artist:0", "discogs:release:123",
            "discogs:artist:123/extra", "discogs:artist:0")) {
            assertFalse(route, homeShelfUsesArtistPortrait(route))
        }
    }
    @Test fun editorialArtworkIsDisplayOnlyAndRequiresExactDiscogsReleaseAndLargeImage() {
        val id = "discogs:release:123"
        val proof = EditorialShelfArtwork(
            "https://is1-ssl.mzstatic.com/image/thumb/example/600x600bb.jpg",
            "https://music.apple.com/br/album/example/123",
            "badge",
        )
        val map = mapOf(id to proof, "discogs:artist:123" to proof,
            "itunes:br:album:123" to proof)
        org.junit.Assert.assertEquals(proof, editorialShelfArtworkFor(id, map))
        org.junit.Assert.assertNull(editorialShelfArtworkFor("discogs:release:124", map))
        org.junit.Assert.assertNull(editorialShelfArtworkFor("discogs:artist:123", map))
        org.junit.Assert.assertNull(editorialShelfArtworkFor("itunes:br:album:123", map))
        org.junit.Assert.assertNull(editorialShelfArtworkFor(id, mapOf(id to
            proof.copy(artworkUrl = proof.artworkUrl.replace("600x600", "100x100")))))
    }

}
