package com.music.bitchord.ui.screens

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
}
